package kh.karazin.foodwise.order.adapter.in.scheduler;

import kh.karazin.foodwise.order.application.port.in.AdvanceOrdersUseCase;
import kh.karazin.foodwise.order.config.OrderProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter that fires the mock-fulfillment use case once per minute.
 *
 * <p>This adapter owns the infrastructure concerns — the scheduling cadence and
 * the {@code demo-auto-advance} kill switch — and delegates the batch logic to
 * {@link AdvanceOrdersUseCase}. When the switch is off the use case is never
 * invoked, so no orders are queried.
 *
 * <p>Disable with {@code ORDER_DEMO_AUTO_ADVANCE=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OrderStatusScheduler {

    private final AdvanceOrdersUseCase advanceOrders;
    private final OrderProperties orderProperties;

    @Scheduled(fixedDelay = 60_000)
    public void advanceOrders() {
        if (!orderProperties.demoAutoAdvance()) {
            return;
        }
        advanceOrders.advanceInKitchenOrders();
    }
}
