package kh.karazin.foodwise.order.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of an {@link Order} aggregate.
 */
public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "value");
    }
}
