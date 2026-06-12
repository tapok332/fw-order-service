package kh.karazin.foodwise.order.entity;

/**
 * Payment-side lifecycle of an order. Driven by Kafka events from payment-service.
 *
 * <p>Distinct from {@link OrderStatus} (fulfillment lifecycle):
 * an order may be {@code paymentStatus = PAID, status = PROCESSING} (paid and
 * being prepared) or {@code paymentStatus = PENDING, status = PENDING}
 * (awaiting card confirmation).
 */
public enum OrderPaymentStatus {
    /** Intent created, awaiting customer confirmation / webhook. */
    PENDING,
    /** Authorised but not captured (manual capture flow — currently unused). */
    AUTHORIZED,
    /** {@code payment_intent.succeeded} received. */
    PAID,
    /** {@code payment_intent.payment_failed} or canceled. */
    FAILED,
    /** {@code charge.refunded} received. */
    REFUNDED
}
