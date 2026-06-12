package kh.karazin.foodwise.order.client;

import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.common.money.MoneyJacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Currency;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies that the order→payment request body serializes {@link Money} using the
 * Money-aware Jackson 3 converter from the auto-configured {@code RestClient.Builder}
 * (registered via {@code MoneyJacksonConfig} in fw-common), NOT the raw record form.
 *
 * <p>This is a regression test for the CRITICAL bug where {@code RestClientConfig}
 * used the static {@code RestClient.builder()} factory (which carries only the default
 * converters without {@code MoneyJacksonModule}), causing a {@code Money} in the
 * {@code createStripeIntent} body to serialize as
 * {@code {"amountMinor":30000,"currency":{"currencyCode":"UAH",...}}} instead of the
 * correct wire form {@code {"amount":"300.00","currency":"UAH"}}.
 *
 * <p>The test loads a minimal slice with only web/Jackson auto-config active
 * (JPA / Kafka / Flyway / Security excluded). It injects the auto-configured
 * {@code RestClient.Builder} bean, clones it (exactly as {@code RestClientConfig}
 * does in production), binds a {@link MockRestServiceServer} before {@code .build()},
 * and asserts the captured request body for the correct Money wire shape.
 */
@SpringBootTest(
        classes = PaymentServiceClientMoneySerializationTest.MinimalWebConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "internal.service.secret=test-secret",
        "foodwise.order.currency=UAH",
        "foodwise.order.stripe-currency=uah",
        "foodwise.order.demo-auto-advance=false",
        // Exclude heavy autoconfigs to keep this slice light.
        // Flyway / Kafka / Security / JPA via property — avoids class-not-found risks
        // when some auto-config classes live in separate Spring Boot 4 submodule jars.
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
                + ",org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"
                + ",org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
                + ",org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
                + ",org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
                + ",org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
                + ",org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
                + ",org.springframework.boot.sql.init.autoconfigure.SqlInitializationAutoConfiguration"
})
class PaymentServiceClientMoneySerializationTest {

    private static final String BASE_URL = "http://payment-service:8087";

    /**
     * Minimal bootstrap configuration: enables Jackson + RestClient auto-configuration
     * and imports {@link MoneyJacksonConfig} so the Money module registers itself into
     * the auto-configured {@code JsonMapper} (and thus into {@code RestClient.Builder}).
     * No component scan that would pull in JPA / Kafka / Security / RestClientConfig.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MoneyJacksonConfig.class)
    static class MinimalWebConfig {
        // intentionally empty — auto-config provides RestClient.Builder with Money converter
    }

    /**
     * The auto-configured {@code RestClient.Builder} — carries the Money-aware
     * Jackson 3 converter registered by {@code MoneyJacksonConfig} (fw-common).
     * This is the same builder that {@code RestClientConfig} now clones for each
     * downstream bean. Injecting it here lets the test exercise the real converter
     * path without any noise from the full application context.
     */
    @Autowired
    private RestClient.Builder autoConfiguredBuilder;

    private MockRestServiceServer mockServer;
    private PaymentServiceClient paymentServiceClient;

    @BeforeEach
    void setUp() {
        // Clone the auto-configured builder (same as RestClientConfig does in production),
        // then bind MockRestServiceServer BEFORE build() so its mock factory intercepts calls.
        RestClient.Builder builderForPayment = autoConfiguredBuilder.clone()
                .baseUrl(BASE_URL)
                .defaultHeader("X-Internal-Token", "test-secret");

        mockServer = MockRestServiceServer.bindTo(builderForPayment).build();

        // Phase 8.2: PaymentServiceClient now uses programmatic Resilience4j
        // (ADR 0006) — its constructor takes the RestClient plus a
        // CircuitBreakerRegistry. We pass a default registry here; this test is
        // about request-body serialization, not breaker behaviour.
        paymentServiceClient = new PaymentServiceClient(
                builderForPayment.build(),
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults());
    }

    /**
     * CRITICAL: {@link Money} serialized via the auto-configured builder must
     * produce the wire form {@code {"amount":"300.00","currency":"UAH"}}, not
     * the raw Java record form {@code {"amountMinor":30000,"currency":{...}}}.
     *
     * <p>Assertions:
     * <ul>
     *   <li>{@code $.amount.amount == "300.00"} — major-unit decimal string from
     *       {@link kh.karazin.foodwise.common.money.MoneyJacksonModule}</li>
     *   <li>{@code $.amount.currency == "UAH"} — ISO code string</li>
     *   <li>no {@code $.amount.amountMinor} field — proves wire form, not raw record</li>
     * </ul>
     */
    @Test
    @DisplayName("Money in createStripeIntent body serializes as wire form {amount,currency}, not raw record {amountMinor,...}")
    void createStripeIntent_serializesMoneyAsWireForm() {
        UUID orderId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID profileId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID paymentId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        // 30 000 minor units UAH = 300.00 UAH
        Money amount = Money.ofMinor(30_000L, Currency.getInstance("UAH"));

        // Stub the payment-service endpoint with a valid ApiResponse<InternalPaymentIntentDto>
        String responseJson = """
                {
                  "success": true,
                  "data": {
                    "paymentId": "%s",
                    "paymentIntentId": "pi_test_12345",
                    "clientSecret": "pi_test_12345_secret_xyz",
                    "status": "PENDING",
                    "amount": { "amount": "300.00", "currency": "UAH" }
                  }
                }
                """.formatted(paymentId);

        mockServer.expect(requestTo(BASE_URL + "/internal/payments/stripe-intent"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // ── CRITICAL assertions on the Money wire shape ──────────────────────
                // $.amount.amount must be the major-unit decimal string "300.00"
                .andExpect(jsonPath("$.amount.amount").value("300.00"))
                // $.amount.currency must be the ISO code string "UAH"
                .andExpect(jsonPath("$.amount.currency").value("UAH"))
                // There must be NO $.amount.amountMinor — its presence would mean
                // the raw record form leaked through (MoneyJacksonModule not applied)
                .andExpect(jsonPath("$.amount.amountMinor").doesNotExist())
                // ─────────────────────────────────────────────────────────────────────
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        var result = paymentServiceClient.createStripeIntent(orderId, profileId, amount, "Order " + orderId);

        mockServer.verify();
        assert result != null : "createStripeIntent must return a non-null InternalPaymentIntentDto";
        assert "pi_test_12345".equals(result.paymentIntentId()) : "paymentIntentId must match stub";
        assert "pi_test_12345_secret_xyz".equals(result.clientSecret()) : "clientSecret must match stub";
    }
}
