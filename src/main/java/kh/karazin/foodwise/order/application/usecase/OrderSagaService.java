package kh.karazin.foodwise.order.application.usecase;

import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.order.application.port.in.OrderSagaUseCase;
import kh.karazin.foodwise.order.application.port.out.OrderEventPublisher;
import kh.karazin.foodwise.order.application.port.out.OrderRepository;
import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.OrderStatus;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Choreography-saga use cases for the order lifecycle. The aggregate owns the
 * idempotent transition logic; this layer loads/saves it and publishes the
 * resulting downstream events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class OrderSagaService implements OrderSagaUseCase {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    @Override
    @Transactional
    public void onPaymentCompleted(OrderId orderId) {
        Order order = requireOrder(orderId);
        if (!order.markPaid()) {
            log.debug("Order {} already PAID — webhook redelivery, ignoring", orderId.value());
            return;
        }
        order = orderRepository.save(order);
        eventPublisher.orderCompleted(order);
        log.info("Order {} PAID; status → PROCESSING", orderId.value());
    }

    @Override
    @Transactional
    public void onPaymentFailed(OrderId orderId, String reason) {
        Order order = requireOrder(orderId);
        if (!order.markPaymentFailed(reason)) {
            log.debug("Order {} already in failed/cancelled state — ignoring redelivery", orderId.value());
            return;
        }
        order = orderRepository.save(order);
        eventPublisher.orderCancelled(order, "Payment failed: " + reason);
        log.info("Order {} cancelled due to payment failure: {}", orderId.value(), reason);
    }

    @Override
    public void onSurpriseBoxReserved(OrderId orderId, SurpriseBoxId boxId, int quantity) {
        log.info("Surprise box {} reserved for order {} (quantity: {})",
                boxId.value(), orderId.value(), quantity);
    }

    @Override
    @Transactional
    public void onReservationExpired(OrderId orderId, SurpriseBoxId boxId) {
        Order order = requireOrder(orderId);
        OrderStatus before = order.status();
        if (!order.expireReservation()) {
            log.info("Order {} already in terminal status {}, ignoring reservation expiry",
                    orderId.value(), before);
            return;
        }
        order = orderRepository.save(order);
        eventPublisher.orderCancelled(order, "Reservation expired for box: " + boxId.value());
        log.info("Order {} cancelled due to reservation expiry for box {}",
                orderId.value(), boxId.value());
    }

    private Order requireOrder(OrderId orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.ENTITY_NOT_FOUND, "Order not found: " + orderId.value()));
    }
}
