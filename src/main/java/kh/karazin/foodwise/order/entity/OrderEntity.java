package kh.karazin.foodwise.order.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.dto.OrderDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entity representing an order.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "store_name")
    private String storeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type")
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type")
    private DeliveryType deliveryType;

    @Column(name = "delivery_address", length = 500)
    private String deliveryAddress;

    @Column(name = "pickup_code", length = 10)
    private String pickupCode;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItemEntity> items = new ArrayList<>();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amountMinor", column = @Column(name = "total_price_amount_minor", nullable = false)),
            @AttributeOverride(name = "currency",    column = @Column(name = "total_price_currency",     nullable = false, length = 3))
    })
    private Money totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    @Builder.Default
    private OrderPaymentStatus paymentStatus = OrderPaymentStatus.PENDING;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * Converts this entity to a DTO.
     */
    public OrderDto toDto() {
        var storeInfo = new OrderDto.StoreInfoDto(storeId, storeName);
        var itemDtos = items.stream()
                .map(item -> new OrderDto.OrderItemDto(
                        item.getId(),
                        item.getSurpriseBoxId(),
                        item.getName(),
                        item.getPrice(),   // Money
                        item.getQuantity(),
                        item.getImageUrl()
                ))
                .toList();
        return new OrderDto(
                id,
                profileId,
                storeInfo,
                status.name(),
                paymentType != null ? paymentType.name() : null,
                deliveryType != null ? deliveryType.name() : null,
                deliveryAddress,
                pickupCode,
                itemDtos,
                totalPrice,   // Money
                paymentStatus != null ? paymentStatus.name() : null,
                stripePaymentIntentId,
                paidAt,
                failureCode,
                failureMessage,
                createdAt,
                updatedAt
        );
    }
}
