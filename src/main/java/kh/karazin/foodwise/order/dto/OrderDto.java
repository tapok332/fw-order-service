package kh.karazin.foodwise.order.dto;

import kh.karazin.foodwise.common.money.Money;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO representing a full order with nested store info and items.
 */
public record OrderDto(
        UUID id,
        UUID profileId,
        StoreInfoDto store,
        String status,
        String paymentType,
        String deliveryType,
        String deliveryAddress,
        String pickupCode,
        List<OrderItemDto> items,
        Money totalPrice,
        String paymentStatus,
        String stripePaymentIntentId,
        Instant paidAt,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt
) {

    public record StoreInfoDto(
            UUID storeId,
            String storeName
    ) {}

    public record OrderItemDto(
            UUID id,
            UUID surpriseBoxId,
            String name,
            Money price,
            int quantity,
            String imageUrl
    ) {}
}
