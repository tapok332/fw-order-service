package kh.karazin.foodwise.order.domain;

import kh.karazin.foodwise.common.money.Money;

import java.util.Objects;

/**
 * Authoritative, server-resolved view of a single order line at placement
 * time. Name and unit price always come from surprise-box-service, never from
 * the client request (ADR 0005). The only way item data enters an {@link Order}
 * is through these lines.
 */
public record ResolvedOrderLine(
        SurpriseBoxId boxId,
        String name,
        Money price,
        int quantity
) {

    public ResolvedOrderLine {
        Objects.requireNonNull(boxId, "boxId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(price, "price");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1, got " + quantity);
        }
    }

    Money lineTotal() {
        return price.times(quantity);
    }
}
