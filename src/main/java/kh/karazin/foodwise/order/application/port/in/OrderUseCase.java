package kh.karazin.foodwise.order.application.port.in;

import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.OrderStatus;
import kh.karazin.foodwise.order.domain.ProfileId;

/**
 * Inbound port for user- and admin-driven order operations (REST + internal API).
 */
public interface OrderUseCase {

    /** Places a new order; for Stripe orders also creates the PaymentIntent. */
    PlaceOrderResult placeOrder(ProfileId profileId, PlaceOrderCommand command);

    /** Returns an order scoped to the caller (ADR 0004 — ownership enforced). */
    Order getForUser(OrderId orderId, ProfileId profileId);

    /**
     * Returns an order with no ownership check. Internal-only — reachable solely
     * from the {@code X-Internal-Token}-guarded internal API, never the gateway.
     */
    Order getUnscoped(OrderId orderId);

    /** Returns a page of the caller's orders, newest first. */
    OrderPage listForProfile(ProfileId profileId, int page, int size);

    /** Cancels one of the caller's orders (only PENDING orders are cancellable). */
    Order cancel(OrderId orderId, ProfileId profileId);

    /** Sets an order's status (admin / mock-fulfillment scheduler). */
    Order changeStatus(OrderId orderId, OrderStatus newStatus);

    /** Triggers an admin Stripe refund for a paid Stripe order. */
    Order refund(OrderId orderId, Integer amount, String reason);
}
