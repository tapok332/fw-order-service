package kh.karazin.foodwise.order.adapter.in.kafka;

import kh.karazin.foodwise.common.event.DomainEvent;
import kh.karazin.foodwise.common.event.payload.BoxReservedPayload;
import kh.karazin.foodwise.common.event.payload.ReservationExpiredPayload;
import kh.karazin.foodwise.common.idempotency.IdempotentConsumer;
import kh.karazin.foodwise.order.application.port.in.OrderSagaUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reserve-then-order saga consumers (ADR 0015).
 *
 * <p>Order-driven reservations carry an {@code orderId} and flow through to the
 * saga. Standalone (profile-scoped) {@code /reserve} reservations still emit
 * {@code orderId=null}; those events must be skipped rather than wrap a null in
 * {@code OrderId} (which would NPE → DLT). Both paths are covered here.
 */
@ExtendWith(MockitoExtension.class)
class OrderKafkaConsumerTest {

    @Mock OrderSagaUseCase sagaUseCase;
    @Mock IdempotentConsumer idempotentConsumer;
    @Mock JsonMapper objectMapper;

    private OrderKafkaConsumer consumer() {
        // Run the action inside processIfNew so the guard executes.
        when(idempotentConsumer.processIfNew(any(), any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
        return new OrderKafkaConsumer(sagaUseCase, idempotentConsumer, objectMapper);
    }

    @SuppressWarnings("unchecked")
    private static DomainEvent<Object> event() {
        DomainEvent<Object> event = mock(DomainEvent.class);
        when(event.eventId()).thenReturn(UUID.randomUUID());
        when(event.eventType()).thenReturn("evt");
        when(event.payload()).thenReturn(new Object());
        return event;
    }

    @Test
    void onSurpriseBoxReserved_skips_whenOrderIdNull() {
        var ack = mock(Acknowledgment.class);
        when(objectMapper.convertValue(any(), eq(BoxReservedPayload.class)))
                .thenReturn(new BoxReservedPayload(
                        UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), 1, Instant.now()));

        consumer().onSurpriseBoxReserved(event(), ack);

        verify(sagaUseCase, never()).onSurpriseBoxReserved(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(ack).acknowledge();
    }

    @Test
    void onSurpriseBoxReserved_delegates_whenOrderIdPresent() {
        var ack = mock(Acknowledgment.class);
        UUID orderId = UUID.randomUUID();
        when(objectMapper.convertValue(any(), eq(BoxReservedPayload.class)))
                .thenReturn(new BoxReservedPayload(
                        UUID.randomUUID(), UUID.randomUUID(), orderId, UUID.randomUUID(), 2, Instant.now()));

        consumer().onSurpriseBoxReserved(event(), ack);

        verify(sagaUseCase).onSurpriseBoxReserved(any(), any(), eq(2));
        verify(ack).acknowledge();
    }

    @Test
    void onReservationExpired_skips_whenOrderIdNull() {
        var ack = mock(Acknowledgment.class);
        when(objectMapper.convertValue(any(), eq(ReservationExpiredPayload.class)))
                .thenReturn(new ReservationExpiredPayload(
                        UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID()));

        consumer().onReservationExpired(event(), ack);

        verify(sagaUseCase, never()).onReservationExpired(any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void onReservationExpired_delegates_whenOrderIdPresent() {
        var ack = mock(Acknowledgment.class);
        when(objectMapper.convertValue(any(), eq(ReservationExpiredPayload.class)))
                .thenReturn(new ReservationExpiredPayload(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        consumer().onReservationExpired(event(), ack);

        verify(sagaUseCase).onReservationExpired(any(), any());
        verify(ack).acknowledge();
    }
}
