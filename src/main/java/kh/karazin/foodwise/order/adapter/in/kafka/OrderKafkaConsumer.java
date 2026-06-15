package kh.karazin.foodwise.order.adapter.in.kafka;

import kh.karazin.foodwise.common.event.DomainEvent;
import kh.karazin.foodwise.common.event.EventTopics;
import kh.karazin.foodwise.common.event.payload.BoxReservedPayload;
import kh.karazin.foodwise.common.event.payload.PaymentCompletedPayload;
import kh.karazin.foodwise.common.event.payload.PaymentFailedPayload;
import kh.karazin.foodwise.common.event.payload.ReservationExpiredPayload;
import kh.karazin.foodwise.common.idempotency.IdempotentConsumer;
import kh.karazin.foodwise.order.application.port.in.OrderSagaUseCase;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka adapter for order saga events. Deserializes payloads, deduplicates by
 * event id and delegates the business decision to {@link OrderSagaUseCase}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OrderKafkaConsumer {

    private final OrderSagaUseCase sagaUseCase;
    private final IdempotentConsumer idempotentConsumer;
    private final JsonMapper objectMapper;

    @KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = "order-service-group")
    public void onPaymentCompleted(@Payload DomainEvent<Object> event, Acknowledgment ack) {
        idempotentConsumer.processIfNew(event.eventId(), event.eventType(), () -> {
            var payload = objectMapper.convertValue(event.payload(), PaymentCompletedPayload.class);
            sagaUseCase.onPaymentCompleted(new OrderId(payload.orderId()));
        });
        ack.acknowledge();
    }

    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = "order-service-group")
    public void onPaymentFailed(@Payload DomainEvent<Object> event, Acknowledgment ack) {
        idempotentConsumer.processIfNew(event.eventId(), event.eventType(), () -> {
            var payload = objectMapper.convertValue(event.payload(), PaymentFailedPayload.class);
            sagaUseCase.onPaymentFailed(new OrderId(payload.orderId()), payload.reason());
        });
        ack.acknowledge();
    }

    @KafkaListener(topics = EventTopics.SURPRISE_BOX_RESERVED, groupId = "order-service-group")
    public void onSurpriseBoxReserved(@Payload DomainEvent<Object> event, Acknowledgment ack) {
        idempotentConsumer.processIfNew(event.eventId(), event.eventType(), () -> {
            var payload = objectMapper.convertValue(event.payload(), BoxReservedPayload.class);
            // TODO(reserve-then-order saga): surprise-box reservations are currently
            // profile-scoped, not order-scoped — the publisher always sends orderId=null
            // and there is no reservation→order link yet. Skip such events instead of
            // failing on a null OrderId (would otherwise NPE → DLT). When the
            // reserve-then-order flow is built, surprisebox will populate orderId and
            // these events will flow through to the saga unchanged.
            if (payload.orderId() == null) {
                log.debug("surprise-box.reserved without orderId (standalone reservation) — skipping");
                return;
            }
            sagaUseCase.onSurpriseBoxReserved(
                    new OrderId(payload.orderId()), new SurpriseBoxId(payload.boxId()), payload.quantity());
        });
        ack.acknowledge();
    }

    @KafkaListener(topics = EventTopics.RESERVATION_EXPIRED, groupId = "order-service-group")
    public void onReservationExpired(@Payload DomainEvent<Object> event, Acknowledgment ack) {
        idempotentConsumer.processIfNew(event.eventId(), event.eventType(), () -> {
            var payload = objectMapper.convertValue(event.payload(), ReservationExpiredPayload.class);
            // TODO(reserve-then-order saga): see onSurpriseBoxReserved. Until reservations
            // are linked to orders the publisher sends orderId=null; skip rather than
            // NPE → DLT. Wiring orderId later re-enables order cancellation on expiry.
            if (payload.orderId() == null) {
                log.debug("reservation.expired without orderId (standalone reservation) — skipping");
                return;
            }
            sagaUseCase.onReservationExpired(
                    new OrderId(payload.orderId()), new SurpriseBoxId(payload.boxId()));
        });
        ack.acknowledge();
    }
}
