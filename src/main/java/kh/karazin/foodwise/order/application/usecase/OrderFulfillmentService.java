package kh.karazin.foodwise.order.application.usecase;

import kh.karazin.foodwise.order.application.port.in.AdvanceOrdersUseCase;
import kh.karazin.foodwise.order.application.port.in.OrderUseCase;
import kh.karazin.foodwise.order.application.port.out.OrderRepository;
import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Mock-fulfillment use case: advance every in-kitchen order one status step.
 * Each transition is routed through {@link OrderUseCase#changeStatus}, so it
 * writes the status-history row and publishes {@code order.status-changed} via
 * the outbox — indistinguishable from a real transition downstream.
 *
 * <p>The {@code demo-auto-advance} kill switch and the scheduling cadence live
 * in the driving scheduler adapter; this use case is the pure batch logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class OrderFulfillmentService implements AdvanceOrdersUseCase {

    private static final List<OrderStatus> FULFILLMENT_STAGES =
            List.of(OrderStatus.PROCESSING, OrderStatus.READY);

    private final OrderRepository orderRepository;
    private final OrderUseCase orderUseCase;

    @Override
    public void advanceInKitchenOrders() {
        List<Order> orders = orderRepository.findByStatusIn(FULFILLMENT_STAGES);
        if (orders.isEmpty()) {
            return;
        }

        int advanced = 0;
        for (Order order : orders) {
            OrderStatus current = order.status();
            OrderStatus next = current.next();
            try {
                orderUseCase.changeStatus(order.id(), next);
                advanced++;
            } catch (RuntimeException e) {
                log.warn("Demo auto-advance failed for order {} ({} -> {}): {}",
                        order.id().value(), current, next, e.getMessage());
            }
        }

        log.info("Demo auto-advance: moved {}/{} orders one status step forward", advanced, orders.size());
    }
}
