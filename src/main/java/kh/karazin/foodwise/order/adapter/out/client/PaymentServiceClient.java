package kh.karazin.foodwise.order.adapter.out.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kh.karazin.foodwise.common.dto.internal.InternalPaymentIntentDto;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.UUID;

/**
 * Calls payment-service for Stripe intent creation and refund triggers.
 *
 * <p>Critical: we send a fresh {@code Idempotency-Key} per logical attempt so
 * that network retries don't create duplicate intents (Stripe-side guarantee).
 * The key derives from the {@code orderId} — if order-service retries the same
 * order creation, payment-service / Stripe both return the original intent.
 *
 * <p><b>Phase 8.2 — error classification.</b> Uses Resilience4j's programmatic
 * API ({@code executeSupplier}) instead of the {@code @CircuitBreaker}
 * annotation, matching {@link StoreServiceClient} and ADR 0006. This lets us
 * split a non-retryable <em>business</em> 4xx from a <em>transient</em> 5xx:
 * <ul>
 *   <li><b>4xx</b> (Stripe {@code amount_too_small} / {@code card_declined} =
 *       402, {@code 400}, {@code 409}, …) → typed {@link FoodWiseException}
 *       ({@code PAYMENT_FAILED} for declines, {@code INVALID_REQUEST} for a
 *       malformed request). Caught <em>outside</em> the supplier so the breaker
 *       never counts them — a declined card is a domain answer, not an outage.</li>
 *   <li><b>5xx / connection refused / timeout</b> →
 *       {@code SERVICE_UNAVAILABLE} (retryable). The underlying exception
 *       propagates through {@code executeSupplier} first, so the breaker counts
 *       it toward the open-circuit threshold.</li>
 *   <li><b>{@link CallNotPermittedException}</b> (breaker already OPEN) →
 *       {@code SERVICE_UNAVAILABLE}. Fail closed — never create an order without
 *       a settled payment intent.</li>
 * </ul>
 *
 * <p>4xx never trip the breaker because {@link HttpClientErrorException} is
 * caught here, outside the supplier, before Resilience4j sees it.
 * {@code application.yml} additionally lists {@code FoodWiseException} under
 * {@code ignoreExceptions} so the translated typed exception isn't counted
 * either. See ADR 0006.
 */
@Slf4j
@Component
class PaymentServiceClient {

    private static final String BREAKER_NAME = "paymentService";

    private static final ParameterizedTypeReference<ApiResponse<InternalPaymentIntentDto>> INTENT_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final RestClient paymentRestClient;
    private final CircuitBreaker circuitBreaker;

    PaymentServiceClient(RestClient paymentRestClient,
                         CircuitBreakerRegistry circuitBreakerRegistry) {
        this.paymentRestClient = paymentRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(BREAKER_NAME);
    }

    /**
     * Creates a Stripe PaymentIntent for the given order. Idempotent on {@code orderId}.
     *
     * <p>The {@code amount} Money is passed directly in the request body under key {@code "amount"}.
     * Jackson 3 serializes it as {@code {"amount":"300.00","currency":"UAH"}} via the Money module
     * carried by the auto-configured {@code RestClient.Builder} (registered in fw-common's
     * {@code MoneyJacksonConfig} and picked up by Spring Boot auto-configuration) —
     * matching payment-service's {@code InternalCreateIntentRequest.amount}.
     *
     * @return intent details, never {@code null} on success
     * @throws FoodWiseException {@code PAYMENT_FAILED} / {@code INVALID_REQUEST}
     *         on a business 4xx; {@code SERVICE_UNAVAILABLE} on a transient 5xx,
     *         network failure, or open breaker
     */
    InternalPaymentIntentDto createStripeIntent(UUID orderId,
                                                UUID profileId,
                                                Money amount,
                                                String description) {
        var body = Map.of(
                "orderId", orderId.toString(),
                "profileId", profileId.toString(),
                "amount", amount,
                "description", description != null ? description : ""
        );
        try {
            ApiResponse<InternalPaymentIntentDto> response = circuitBreaker.executeSupplier(() ->
                    paymentRestClient.post()
                            .uri("/internal/payments/stripe-intent")
                            .header("Idempotency-Key", "order:" + orderId)
                            .body(body)
                            .retrieve()
                            .body(INTENT_RESPONSE)
            );
            if (response == null || response.getData() == null) {
                throw FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                        "payment-service returned empty intent response");
            }
            return response.getData();
        } catch (HttpClientErrorException e) {
            // Business 4xx — a domain answer (declined card, amount too small,
            // malformed request). Translate to a typed FoodWiseException; do NOT
            // let it count toward the breaker (caught outside the supplier).
            log.warn("payment-service rejected intent creation for order {}: status={} body={}",
                    orderId, e.getStatusCode(), e.getResponseBodyAsString());
            throw classifyClientError(e.getStatusCode(),
                    "Payment provider error: " + e.getStatusCode());
        } catch (CallNotPermittedException e) {
            // Breaker already OPEN — fail closed, never create an order without a settled intent.
            log.warn("payment-service circuit breaker open for order {}: {}", orderId, e.getMessage());
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                    "Payment provider unavailable, please retry");
        } catch (RestClientException e) {
            // Transient: 5xx, connection refused, timeout. The exception already
            // propagated through executeSupplier, so the breaker counted it.
            log.error("payment-service intent creation failed for order {} (transient): {}",
                    orderId, e.getMessage());
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                    "Payment provider unavailable, please retry");
        }
    }

    /**
     * Triggers a Stripe refund by {@code orderId}. The actual {@code RefundEntity}
     * lands in payment-service when Stripe webhook fires {@code charge.refunded}.
     *
     * <p>Same 4xx-vs-5xx classification as {@link #createStripeIntent}: a 4xx
     * (e.g. refund already issued, amount exceeds charge) is a business answer,
     * a 5xx / network failure is a transient outage.
     */
    void refundByOrder(UUID orderId, Integer amount, String reason) {
        var body = amount != null
                ? Map.of("amount", (Object) amount, "reason", reason != null ? reason : "")
                : Map.of("reason", (Object) (reason != null ? reason : ""));
        try {
            circuitBreaker.executeRunnable(() ->
                    paymentRestClient.post()
                            .uri("/internal/payments/order/{orderId}/stripe-refund", orderId)
                            .body(body)
                            .retrieve()
                            .toBodilessEntity()
            );
        } catch (HttpClientErrorException e) {
            log.warn("payment-service refund rejected for order {}: status={} body={}",
                    orderId, e.getStatusCode(), e.getResponseBodyAsString());
            throw classifyClientError(e.getStatusCode(),
                    "Refund failed: " + e.getStatusCode());
        } catch (CallNotPermittedException e) {
            log.warn("payment-service circuit breaker open for refund of order {}: {}",
                    orderId, e.getMessage());
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                    "Refund provider unavailable, please retry");
        } catch (RestClientException e) {
            log.error("payment-service refund failed for order {} (transient): {}",
                    orderId, e.getMessage());
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                    "Refund provider unavailable, please retry");
        }
    }

    /**
     * Maps a downstream 4xx to a typed business {@link FoodWiseException}:
     * {@code 400} → {@code INVALID_REQUEST} (malformed request);
     * everything else (402 amount_too_small, 409 card_declined, …) →
     * {@code PAYMENT_FAILED}. Both are non-retryable domain answers.
     */
    private FoodWiseException classifyClientError(HttpStatusCode status, String detail) {
        if (status.isSameCodeAs(org.springframework.http.HttpStatus.BAD_REQUEST)) {
            return FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST, detail);
        }
        return FoodWiseException.errorWithDescription(FoodWiseErrorCode.PAYMENT_FAILED, detail);
    }
}
