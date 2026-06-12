package kh.karazin.foodwise.order.kafka;

import kh.karazin.foodwise.common.event.DomainEvent;
import kh.karazin.foodwise.common.event.EventTopics;
import kh.karazin.foodwise.common.event.payload.BoxReservedPayload;
import kh.karazin.foodwise.common.event.payload.PaymentCompletedPayload;
import kh.karazin.foodwise.common.event.payload.PaymentFailedPayload;
import kh.karazin.foodwise.common.event.payload.ReservationExpiredPayload;
import kh.karazin.foodwise.common.idempotency.IdempotentConsumer;
import kh.karazin.foodwise.order.service.OrderSagaHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Kafka consumers for order saga events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderKafkaConsumer {

    private final OrderSagaHandler sagaHandler;
    private final IdempotentConsumer idempotentConsumer;
    private final JsonMapper objectMapper;

    @KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = "order-service-group")
    public void onPaymentCompleted(@Payload DomainEvent<Object> event, Acknowledgment ack) {
        idempotentConsumer.processIfNew(event.eventId(), event.eventType(), () -> {
            var payload = objectMapper.convertValue(event.payload(), PaymentCompletedPayload.class);
            sagaHandler.onPaymentCompleted(payload.orderId(), payload.profileId(), payload.amount());
        });
        ack.acknowledge();
    }

    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = "order-service-group")
    public void onPaymentFailed(@Payload DomainEvent<Object> event, Acknowledgment ack) {
        idempotentConsumer.processIfNew(event.eventId(), event.eventType(), () -> {
            var payload = objectMapper.convertValue(event.payload(), PaymentFailedPayload.class);
            sagaHandler.onPaymentFailed(payload.orderId(), payload.profileId(), payload.reason());
        });
        ack.acknowledge();
    }

    @KafkaListener(topics = EventTopics.SURPRISE_BOX_RESERVED, groupId = "order-service-group")
    public void onSurpriseBoxReserved(@Payload DomainEvent<Object> event, Acknowledgment ack) {
        idempotentConsumer.processIfNew(event.eventId(), event.eventType(), () -> {
            var payload = objectMapper.convertValue(event.payload(), BoxReservedPayload.class);
            sagaHandler.onSurpriseBoxReserved(payload.orderId(), payload.boxId(), payload.quantity());
        });
        ack.acknowledge();
    }

    @KafkaListener(topics = EventTopics.RESERVATION_EXPIRED, groupId = "order-service-group")
    public void onReservationExpired(@Payload DomainEvent<Object> event, Acknowledgment ack) {
        idempotentConsumer.processIfNew(event.eventId(), event.eventType(), () -> {
            var payload = objectMapper.convertValue(event.payload(), ReservationExpiredPayload.class);
            sagaHandler.onReservationExpired(payload.orderId(), payload.boxId());
        });
        ack.acknowledge();
    }
}
