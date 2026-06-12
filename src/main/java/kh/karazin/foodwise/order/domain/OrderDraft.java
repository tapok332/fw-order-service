package kh.karazin.foodwise.order.domain;

import kh.karazin.foodwise.common.money.Money;

import java.util.List;
import java.util.Objects;

/**
 * Parameter object handed to {@link Order#place(OrderDraft)} — everything the
 * aggregate needs to construct a valid order, all already resolved server-side
 * by the placement use case.
 *
 * @param storeMinimum the store's minimum order amount, or {@code null} when the
 *                     store has no minimum (guard disabled)
 */
public record OrderDraft(
        ProfileId profileId,
        StoreId storeId,
        String storeName,
        PaymentType paymentType,
        DeliveryType deliveryType,
        String deliveryAddress,
        List<ResolvedOrderLine> lines,
        Money storeMinimum
) {

    public OrderDraft {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(paymentType, "paymentType");
        Objects.requireNonNull(deliveryType, "deliveryType");
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an order must contain at least one line");
        }
        lines = List.copyOf(lines);
    }
}
