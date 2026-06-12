package kh.karazin.foodwise.order.application.port.in;

import kh.karazin.foodwise.order.domain.Order;

/**
 * Result of placing an order. For Stripe orders {@code paymentClientSecret} and
 * {@code paymentIntentId} are non-null; for other payment types both are null.
 */
public record PlaceOrderResult(
        Order order,
        String paymentClientSecret,
        String paymentIntentId
) {
}
