package kh.karazin.foodwise.order.service;

import kh.karazin.foodwise.common.event.EventTopics;
import kh.karazin.foodwise.common.event.payload.OrderCancelledPayload;
import kh.karazin.foodwise.common.event.payload.OrderCompletedPayload;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.common.outbox.OutboxPublisher;
import kh.karazin.foodwise.order.entity.OrderEntity;
import kh.karazin.foodwise.order.entity.OrderPaymentStatus;
import kh.karazin.foodwise.order.entity.OrderStatus;
import kh.karazin.foodwise.order.entity.OrderStatusHistoryEntity;
import kh.karazin.foodwise.order.repository.OrderRepository;
import kh.karazin.foodwise.order.repository.OrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Handles choreography saga events for the order lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaHandler {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OutboxPublisher outboxPublisher;
    private final OrderService orderService;

    /**
     * Handles payment.completed event: marks order PAID, moves to PROCESSING, publishes order.completed.
     *
     * <p>Idempotent — re-deliveries see {@code paymentStatus == PAID} and bail out.
     */
    @Transactional
    public void onPaymentCompleted(UUID orderId, UUID profileId, Money amount) {
        OrderEntity order = findOrder(orderId);

        if (order.getPaymentStatus() == OrderPaymentStatus.PAID) {
            log.debug("Order {} already PAID — webhook redelivery, ignoring", orderId);
            return;
        }

        order.setPaymentStatus(OrderPaymentStatus.PAID);
        order.setPaidAt(Instant.now());
        order.setFailureCode(null);
        order.setFailureMessage(null);
        order.setStatus(OrderStatus.PROCESSING);
        orderRepository.save(order);

        statusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(orderId)
                .status(OrderStatus.PROCESSING.name())
                .build());

        // Publish order.completed — totalPrice flows as Money end-to-end (ADR 0012).
        var payload = new OrderCompletedPayload(
                order.getId(),
                order.getProfileId(),
                order.getStoreId(),
                order.getTotalPrice(),
                order.getItems().size()
        );

        outboxPublisher.saveEvent(
                EventTopics.ORDER_COMPLETED,
                orderId.toString(),
                "order.completed",
                payload,
                orderId
        );

        log.info("Order {} PAID; status → PROCESSING", orderId);
    }

    /**
     * Handles payment.failed event: marks paymentStatus=FAILED, cancels the order,
     * publishes order.cancelled. Idempotent.
     */
    @Transactional
    public void onPaymentFailed(UUID orderId, UUID profileId, String reason) {
        OrderEntity order = findOrder(orderId);

        if (order.getPaymentStatus() == OrderPaymentStatus.FAILED
                || order.getStatus() == OrderStatus.CANCELLED) {
            log.debug("Order {} already in failed/cancelled state — ignoring redelivery", orderId);
            return;
        }

        order.setPaymentStatus(OrderPaymentStatus.FAILED);
        // We only have a string reason on the wire. The Stripe error code lives in
        // payment-service / its webhook trail; surface the human message here.
        order.setFailureMessage(reason != null && reason.length() > 1000
                ? reason.substring(0, 1000) : reason);
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        statusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(orderId)
                .status(OrderStatus.CANCELLED.name())
                .build());

        // Publish order.cancelled
        var cancelledItems = order.getItems().stream()
                .map(item -> new OrderCancelledPayload.CancelledItemPayload(
                        item.getSurpriseBoxId(),
                        item.getQuantity()
                ))
                .toList();

        var payload = new OrderCancelledPayload(
                order.getId(),
                order.getProfileId(),
                order.getStoreId(),
                "Payment failed: " + reason,
                cancelledItems
        );

        outboxPublisher.saveEvent(
                EventTopics.ORDER_CANCELLED,
                orderId.toString(),
                "order.cancelled",
                payload,
                orderId
        );

        log.info("Order {} cancelled due to payment failure: {}", orderId, reason);
    }

    /**
     * Handles surprise-box.reserved event: logs the reservation (no state change in happy path).
     */
    public void onSurpriseBoxReserved(UUID orderId, UUID boxId, int quantity) {
        log.info("Surprise box {} reserved for order {} (quantity: {})", boxId, orderId, quantity);
    }

    /**
     * Handles reservation.expired event: cancels the order.
     */
    @Transactional
    public void onReservationExpired(UUID orderId, UUID boxId) {
        OrderEntity order = findOrder(orderId);

        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.COMPLETED) {
            log.info("Order {} already in terminal status {}, ignoring reservation expiry", orderId, order.getStatus());
            return;
        }

        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        statusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(orderId)
                .status(OrderStatus.CANCELLED.name())
                .build());

        var cancelledItems = order.getItems().stream()
                .map(item -> new OrderCancelledPayload.CancelledItemPayload(
                        item.getSurpriseBoxId(),
                        item.getQuantity()
                ))
                .toList();

        var payload = new OrderCancelledPayload(
                order.getId(),
                order.getProfileId(),
                order.getStoreId(),
                "Reservation expired for box: " + boxId,
                cancelledItems
        );

        outboxPublisher.saveEvent(
                EventTopics.ORDER_CANCELLED,
                orderId.toString(),
                "order.cancelled",
                payload,
                orderId
        );

        log.info("Order {} cancelled due to reservation expiry for box {}", orderId, boxId);
    }

    private OrderEntity findOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.ENTITY_NOT_FOUND, "Order not found: " + orderId));
    }
}
