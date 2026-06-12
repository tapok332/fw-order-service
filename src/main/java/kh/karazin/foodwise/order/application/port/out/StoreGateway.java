package kh.karazin.foodwise.order.application.port.out;

import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.domain.StoreId;

/**
 * Outbound port for resolving store data from store-service.
 */
public interface StoreGateway {

    /**
     * Fetches the store snapshot needed to place an order, or {@code null} when
     * the store cannot be reached for infrastructure reasons (breaker open /
     * 5xx) so the caller can fail closed. A genuinely unknown store (upstream
     * 404) surfaces as a typed {@code ENTITY_NOT_FOUND}.
     */
    StoreSnapshot getStore(StoreId storeId);

    /**
     * The subset of store data an order needs: display name (denormalised onto
     * the order) and the optional minimum order amount.
     */
    record StoreSnapshot(String name, Money minOrderAmount) {
    }
}
