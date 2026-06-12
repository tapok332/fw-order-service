package kh.karazin.foodwise.order.service;

import kh.karazin.foodwise.order.config.OrderProperties;
import kh.karazin.foodwise.order.entity.OrderStatus;
import kh.karazin.foodwise.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mock fulfillment scheduler. Once per minute it walks every order that is
 * already in the kitchen one step forward (PROCESSING -> READY -> COMPLETED),
 * so order progress is observable end-to-end without a real restaurant.
 *
 * <p>PENDING is deliberately excluded: an order only enters the kitchen after
 * payment is settled. Stripe orders cross PENDING -> PROCESSING via the
 * {@code payment.completed} saga; cash orders start in PROCESSING at creation.
 * The mock never moves an unpaid order.
 *
 * <p>Each transition is routed through {@link OrderService#updateOrderStatus},
 * which writes the status-history row and publishes {@code order.status-changed}
 * via the transactional outbox — so a mock transition is indistinguishable from
 * a real one to downstream consumers. Per-order isolation: a single failing
 * order is logged and skipped, it never blocks the rest of the batch.
 *
 * <p>Disable with {@code ORDER_DEMO_AUTO_ADVANCE=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusScheduler {

    private static final List<OrderStatus> FULFILLMENT_STAGES =
            List.of(OrderStatus.PROCESSING, OrderStatus.READY);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OrderProperties orderProperties;

    @Scheduled(fixedDelay = 60_000)
    public void advanceOrders() {
        if (!orderProperties.demoAutoAdvance()) {
            return;
        }

        var orders = orderRepository.findByStatusIn(FULFILLMENT_STAGES);
        if (orders.isEmpty()) {
            return;
        }

        int advanced = 0;
        for (var order : orders) {
            OrderStatus current = order.getStatus();
            OrderStatus next = current.next();
            try {
                orderService.updateOrderStatus(order.getId(), next);
                advanced++;
            } catch (RuntimeException e) {
                log.warn("Demo auto-advance failed for order {} ({} -> {}): {}",
                        order.getId(), current, next, e.getMessage());
            }
        }

        log.info("Demo auto-advance: moved {}/{} orders one status step forward", advanced, orders.size());
    }
}
