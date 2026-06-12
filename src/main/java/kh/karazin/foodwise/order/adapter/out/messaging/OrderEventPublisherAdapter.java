package kh.karazin.foodwise.order.adapter.out.messaging;

import kh.karazin.foodwise.common.event.EventTopics;
import kh.karazin.foodwise.common.event.payload.OrderCancelledPayload;
import kh.karazin.foodwise.common.event.payload.OrderCompletedPayload;
import kh.karazin.foodwise.common.event.payload.OrderCreatedPayload;
import kh.karazin.foodwise.common.outbox.OutboxPublisher;
import kh.karazin.foodwise.order.adapter.in.rest.OrderRestMapper;
import kh.karazin.foodwise.order.application.port.out.OrderEventPublisher;
import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Messaging adapter implementing the {@link OrderEventPublisher} outbound port.
 * Builds the fw-common event payloads from the domain order and writes them to
 * the transactional outbox.
 *
 * <p>The {@code order.status-changed} event historically carries the full REST
 * {@code OrderDto} as its payload. To keep that event's JSON byte-identical
 * after the hexagonal split, this adapter reuses {@link OrderRestMapper#toDto}
 * — a deliberate, documented cross-adapter reference (both sit in the
 * {@code adapter} layer; see ADR 0014).
 */
@Component
@RequiredArgsConstructor
class OrderEventPublisherAdapter implements OrderEventPublisher {

    private final OutboxPublisher outboxPublisher;

    @Override
    public void orderCreated(Order order) {
        UUID orderId = order.id().value();
        List<OrderCreatedPayload.OrderItemPayload> items = order.items().stream()
                .map(item -> new OrderCreatedPayload.OrderItemPayload(
                        item.id() != null ? item.id().value() : null,
                        item.surpriseBoxId() != null ? item.surpriseBoxId().value() : null,
                        item.name(),
                        item.price(),
                        item.quantity()))
                .toList();
        var payload = new OrderCreatedPayload(
                orderId,
                order.profileId().value(),
                order.storeId().value(),
                order.storeName(),
                items,
                order.totalPrice(),
                order.paymentType().name(),
                order.deliveryType().name(),
                order.deliveryAddress());
        outboxPublisher.saveEvent(EventTopics.ORDER_CREATED, orderId.toString(),
                "order.created", payload, orderId);
    }

    @Override
    public void orderCancelled(Order order, String reason) {
        UUID orderId = order.id().value();
        List<OrderCancelledPayload.CancelledItemPayload> items = order.items().stream()
                .map(item -> new OrderCancelledPayload.CancelledItemPayload(
                        item.surpriseBoxId() != null ? item.surpriseBoxId().value() : null,
                        item.quantity()))
                .toList();
        var payload = new OrderCancelledPayload(
                orderId,
                order.profileId().value(),
                order.storeId().value(),
                reason,
                items);
        outboxPublisher.saveEvent(EventTopics.ORDER_CANCELLED, orderId.toString(),
                "order.cancelled", payload, orderId);
    }

    @Override
    public void orderStatusChanged(Order order) {
        UUID orderId = order.id().value();
        outboxPublisher.saveEvent(EventTopics.ORDER_STATUS_CHANGED, orderId.toString(),
                "order.status-changed", OrderRestMapper.toDto(order), orderId);
    }

    @Override
    public void orderCompleted(Order order) {
        UUID orderId = order.id().value();
        var payload = new OrderCompletedPayload(
                orderId,
                order.profileId().value(),
                order.storeId().value(),
                order.totalPrice(),
                order.items().size());
        outboxPublisher.saveEvent(EventTopics.ORDER_COMPLETED, orderId.toString(),
                "order.completed", payload, orderId);
    }
}
