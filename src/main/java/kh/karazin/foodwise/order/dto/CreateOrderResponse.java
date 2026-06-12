package kh.karazin.foodwise.order.dto;

/**
 * Response to {@code POST /orders}.
 *
 * <p>For Stripe orders, {@code paymentClientSecret} and {@code paymentIntentId}
 * are non-null and the frontend should immediately call
 * {@code stripe.confirmPayment(paymentClientSecret, ...)}. For non-Stripe
 * payment types (CASH / CARD / ONLINE legacy) both are {@code null}.
 */
public record CreateOrderResponse(
        OrderDto order,
        String paymentClientSecret,
        String paymentIntentId
) {}
