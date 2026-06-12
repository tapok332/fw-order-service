package kh.karazin.foodwise.order.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of the store an order belongs to.
 */
public record StoreId(UUID value) {

    public StoreId {
        Objects.requireNonNull(value, "value");
    }
}
