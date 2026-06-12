package kh.karazin.foodwise.order.application.port.out;

import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;

/**
 * Outbound port for resolving surprise-box pricing from surprise-box-service.
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

    /** Always complete when non-null: title and price are both present. */
    record ResolvedBox(String title, Money price) {
    }
}
