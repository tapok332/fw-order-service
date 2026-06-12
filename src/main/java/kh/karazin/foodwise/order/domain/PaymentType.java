package kh.karazin.foodwise.order.domain;

/**
 * Supported payment types.
 *
 * <p>{@link #STRIPE} drives the order-first flow:
 * {@code POST /orders} creates the order AND a Stripe PaymentIntent atomically,
 * returning {@code clientSecret} to the frontend for {@code stripe.confirmPayment(...)}.
 *
 * <p>{@link #CARD} / {@link #CASH} / {@link #ONLINE} kept for backwards compatibility
 * with the legacy auto-simulated flow. New checkouts SHOULD pick STRIPE.
 */
public enum PaymentType {
    CARD,
    CASH,
    ONLINE,
    STRIPE
}
