package kh.karazin.foodwise.order.application.port.out;

import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.ProfileId;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;

/**
 * Outbound port for surprise-box-service: price resolution and order-driven
 * reservation (ADR 0015).
 */
public interface SurpriseBoxGateway {

    /**
     * Resolves the authoritative title and unit price of a surprise box, or
     * {@code null} when it cannot be priced — whether because the downstream is
     * unhealthy (breaker open / 5xx) or because the payload came back without a
     * price/title. Both collapse to {@code null} so the caller rejects the order
     * uniformly with "Pricing unavailable, please retry" (ADR 0005). A genuinely
     * unknown box (upstream 404) instead surfaces as a typed
     * {@code ENTITY_NOT_FOUND}.
     */
    ResolvedBox resolve(SurpriseBoxId boxId);

    /**
     * Reserves a box for an order awaiting payment, holding stock until the order
     * is paid or the reservation expires (ADR 0015). Returns {@code true} on a
     * confirmed reservation. A downstream outage (breaker open / 5xx / network)
     * returns {@code false} so the caller rejects the order with "Reservation
     * unavailable, please retry". A box with no stock surfaces as a typed
     * {@code RESOURCE_UNAVAILABLE}; an unknown box as {@code ENTITY_NOT_FOUND}.
     */
    boolean reserve(SurpriseBoxId boxId, OrderId orderId, ProfileId profileId);

    /** Always complete when non-null: title and price are both present. */
    record ResolvedBox(String title, Money price) {
    }
}
