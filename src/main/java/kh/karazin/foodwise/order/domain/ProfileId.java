package kh.karazin.foodwise.order.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of the user profile that owns an order.
 */
public record ProfileId(UUID value) {

    public ProfileId {
        Objects.requireNonNull(value, "value");
    }
}
