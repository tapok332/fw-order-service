package kh.karazin.foodwise.order.domain;

import kh.karazin.foodwise.common.money.Money;

import java.util.Objects;

/**
 * A single line within an {@link Order}: a surprise box with the server-resolved
 * name and unit price captured at placement time, plus a quantity.
 *
 * <p>Lines are immutable once created — there is no flow that re-prices or
 * re-quantifies an order line after the order is placed.
 */
public class OrderItem {

    /** Null until the line is persisted; assigned by the persistence adapter. */
    private final OrderItemId id;
    private final SurpriseBoxId surpriseBoxId;
    private final String name;
    private final Money price;
    private final int quantity;
    private final String imageUrl;

    private OrderItem(OrderItemId id, SurpriseBoxId surpriseBoxId, String name,
                      Money price, int quantity, String imageUrl) {
        this.id = id;
        // surpriseBoxId is nullable to mirror the nullable surprise_box_id column;
        // every order placed through the service carries one, but restore must
        // tolerate any persisted row.
        this.surpriseBoxId = surpriseBoxId;
        this.name = Objects.requireNonNull(name, "name");
        this.price = Objects.requireNonNull(price, "price");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1, got " + quantity);
        }
        this.quantity = quantity;
        this.imageUrl = imageUrl;
    }

    /** Creates a new, not-yet-persisted line from a server-resolved order line. */
    static OrderItem newLine(ResolvedOrderLine line) {
        return new OrderItem(null, line.boxId(), line.name(), line.price(), line.quantity(), null);
    }

    /** Restores a persisted line; used by the persistence adapter. */
    public static OrderItem restore(OrderItemId id, SurpriseBoxId surpriseBoxId, String name,
                                    Money price, int quantity, String imageUrl) {
        Objects.requireNonNull(id, "id");
        return new OrderItem(id, surpriseBoxId, name, price, quantity, imageUrl);
    }

    Money lineTotal() {
        return price.times(quantity);
    }

    public OrderItemId id() {
        return id;
    }

    public SurpriseBoxId surpriseBoxId() {
        return surpriseBoxId;
    }

    public String name() {
        return name;
    }

    public Money price() {
        return price;
    }

    public int quantity() {
        return quantity;
    }

    public String imageUrl() {
        return imageUrl;
    }
}
