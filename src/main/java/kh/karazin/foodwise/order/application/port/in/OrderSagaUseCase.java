package kh.karazin.foodwise.order.application.port.in;

import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;

/**
 * Inbound port for choreography-saga events driving the order lifecycle
 * (Kafka adapter). All operations are idempotent.
 *
 * <p>The handlers take only the fields they act on: the {@code payment.completed}
 * amount and the event {@code profileId} were carried but never used by the
 * original saga (order totals and ownership come from the persisted order), so
 * they are not threaded through here.
 */
public interface OrderSagaUseCase {

    /** payment.completed → mark PAID, move to PROCESSING, publish order.completed. */
    void onPaymentCompleted(OrderId orderId);

    /** payment.failed → mark FAILED, cancel the order, publish order.cancelled. */
    void onPaymentFailed(OrderId orderId, String reason);

    /** surprise-box.reserved → log the reservation (no state change in happy path). */
    void onSurpriseBoxReserved(OrderId orderId, SurpriseBoxId boxId, int quantity);

    /** reservation.expired → cancel the order. */
    void onReservationExpired(OrderId orderId, SurpriseBoxId boxId);
}
