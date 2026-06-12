package kh.karazin.foodwise.order.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Reference to the surprise box an order line was placed against.
 */
public record SurpriseBoxId(UUID value) {

    public SurpriseBoxId {
        Objects.requireNonNull(value, "value");
    }
}
