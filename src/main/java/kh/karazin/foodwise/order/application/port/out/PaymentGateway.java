package kh.karazin.foodwise.order.application.port.out;

import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.ProfileId;

/**
 * Outbound port for payment-service (Stripe intent creation and refunds).
 */
public interface PaymentGateway {

    /**
     * Creates a Stripe PaymentIntent for the order. Idempotent on {@code orderId}.
     * Throws a typed {@code PAYMENT_FAILED} / {@code INVALID_REQUEST} on a business
     * 4xx, or {@code SERVICE_UNAVAILABLE} on a transient failure / open breaker.
     */
    PaymentIntent createStripeIntent(OrderId orderId, ProfileId profileId, Money amount, String description);

    /** Triggers a Stripe refund by order. The refund settles via webhook. */
    void refundByOrder(OrderId orderId, Integer amount, String reason);

    /** The intent handles the frontend needs to confirm the payment. */
    record PaymentIntent(String paymentIntentId, String clientSecret) {
    }
}
