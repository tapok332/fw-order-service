package kh.karazin.foodwise.order.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Persistence identity of a single {@link OrderItem} line.
 */
public record OrderItemId(UUID value) {

    public OrderItemId {
        Objects.requireNonNull(value, "value");
    }
}
