package kh.karazin.foodwise.order.application.port.out;

import kh.karazin.foodwise.order.domain.Order;

/**
 * Outbound port for publishing order lifecycle events (transactional outbox).
 */
public interface OrderEventPublisher {

    /** order.created — emitted once a new order (and its Stripe intent) is persisted. */
    void orderCreated(Order order);

    /** order.cancelled — with the human-readable cancellation reason. */
    void orderCancelled(Order order, String reason);

    /** order.status-changed — carries the full order view (admin / scheduler transitions). */
    void orderStatusChanged(Order order);

    /** order.completed — emitted when payment settles and the order enters PROCESSING. */
    void orderCompleted(Order order);
}
