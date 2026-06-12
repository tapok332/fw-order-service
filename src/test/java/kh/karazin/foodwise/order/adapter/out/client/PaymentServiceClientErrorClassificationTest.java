package kh.karazin.foodwise.order.adapter.out.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.common.money.MoneyJacksonModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Phase 8.2 — payment error classification (business 4xx vs transient 5xx).
 * Programmatic Resilience4j (ADR 0006): a 4xx is a domain answer and must not
 * trip the breaker; a 5xx is a transient outage and is counted.
 */
class PaymentServiceClientErrorClassificationTest {

    private static final String BASE_URL = "http://payment-service:8087";
    private static final String INTENT_URI = BASE_URL + "/internal/payments/stripe-intent";
    private static final Currency UAH = Currency.getInstance("UAH");

    private MockRestServiceServer mockServer;
    private CircuitBreakerRegistry registry;
    private CircuitBreaker breaker;
    private PaymentServiceClient client;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(100f)
                .ignoreExceptions(
                        org.springframework.web.client.HttpClientErrorException.class,
                        FoodWiseException.class)
                .build();
        registry = CircuitBreakerRegistry.of(config);
        breaker = registry.circuitBreaker("paymentService");

        JsonMapper jsonMapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .messageConverters(converters ->
                        converters.add(0, new JacksonJsonHttpMessageConverter(jsonMapper)));
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new PaymentServiceClient(builder.build(), registry);
    }

    @Test
    @DisplayName("402 amount_too_small → PAYMENT_FAILED (402), breaker NOT tripped")
    void createStripeIntent_throwsPaymentFailed_andDoesNotTripBreaker_on402() {
        UUID orderId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        mockServer.expect(requestTo(INTENT_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .body("{\"detail\":\"amount_too_small\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createStripeIntent(
                orderId, profileId, Money.ofMinor(50L, UAH), "Order " + orderId))
                .isInstanceOf(FoodWiseException.class)
                .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                        e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                .containsExactly(FoodWiseErrorCode.PAYMENT_FAILED.getMessage(),
                        FoodWiseErrorCode.PAYMENT_FAILED.getHttpStatus());

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        mockServer.verify();
    }

    @Test
    @DisplayName("500 → SERVICE_UNAVAILABLE (retryable), breaker counts the failure")
    void createStripeIntent_throwsServiceUnavailable_andCountsBreaker_on500() {
        UUID orderId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        mockServer.expect(requestTo(INTENT_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.createStripeIntent(
                orderId, profileId, Money.ofMinor(30_000L, UAH), "Order " + orderId))
                .isInstanceOf(FoodWiseException.class)
                .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                        e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                .containsExactly(FoodWiseErrorCode.SERVICE_UNAVAILABLE.getMessage(),
                        FoodWiseErrorCode.SERVICE_UNAVAILABLE.getHttpStatus());

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        mockServer.verify();
    }

    @Test
    @DisplayName("400 → INVALID_REQUEST, breaker NOT tripped")
    void createStripeIntent_throwsInvalidRequest_on400() {
        UUID orderId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        mockServer.expect(requestTo(INTENT_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.createStripeIntent(
                orderId, profileId, Money.ofMinor(30_000L, UAH), "Order " + orderId))
                .isInstanceOf(FoodWiseException.class)
                .extracting(e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                .isEqualTo(FoodWiseErrorCode.INVALID_REQUEST.getHttpStatus());

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        mockServer.verify();
    }

    @Test
    @DisplayName("409 card_declined → PAYMENT_FAILED, breaker NOT tripped")
    void createStripeIntent_throwsPaymentFailed_on409() {
        UUID orderId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        mockServer.expect(requestTo(INTENT_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("{\"detail\":\"card_declined\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createStripeIntent(
                orderId, profileId, Money.ofMinor(30_000L, UAH), "Order " + orderId))
                .isInstanceOf(FoodWiseException.class)
                .extracting(e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                .isEqualTo(FoodWiseErrorCode.PAYMENT_FAILED.getHttpStatus());

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        mockServer.verify();
    }
}
