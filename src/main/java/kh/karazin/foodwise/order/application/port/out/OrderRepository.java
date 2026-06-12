package kh.karazin.foodwise.order.application.port.out;

import kh.karazin.foodwise.order.application.port.in.OrderPage;
import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.OrderStatus;
import kh.karazin.foodwise.order.domain.ProfileId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for order persistence.
 */
public interface OrderRepository {

    Optional<Order> findById(OrderId orderId);

    /** The caller's orders, newest first, paginated. */
    OrderPage findByProfile(ProfileId profileId, int page, int size);

    /** Orders currently in one of the given statuses (mock-fulfillment sweep). */
    List<Order> findByStatusIn(List<OrderStatus> statuses);

    /**
     * Persists the aggregate and returns it with persistence-assigned ids
     * populated. Drains the aggregate's pending status-history transitions into
     * the audit table in the same transaction.
     */
    Order save(Order order);
}
