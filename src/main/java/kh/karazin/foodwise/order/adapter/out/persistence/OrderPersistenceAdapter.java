package kh.karazin.foodwise.order.adapter.out.persistence;

import kh.karazin.foodwise.order.application.port.in.OrderPage;
import kh.karazin.foodwise.order.application.port.out.OrderRepository;
import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.OrderItem;
import kh.karazin.foodwise.order.domain.OrderItemId;
import kh.karazin.foodwise.order.domain.OrderStatus;
import kh.karazin.foodwise.order.domain.ProfileId;
import kh.karazin.foodwise.order.domain.StoreId;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing the {@link OrderRepository} outbound port.
 * Maps between the domain aggregate and the JPA model explicitly; the domain
 * never sees JPA types.
 *
 * <p>On {@link #save} the adapter drains the aggregate's pending status-history
 * transitions into the audit table in the same transaction, so writing history
 * is automatic and co-located with the status change that produced it.
 */
@Component
@RequiredArgsConstructor
class OrderPersistenceAdapter implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;
    private final OrderStatusHistoryJpaRepository statusHistoryJpaRepository;

    @Override
    public Optional<Order> findById(OrderId orderId) {
        return orderJpaRepository.findById(orderId.value()).map(OrderPersistenceAdapter::toDomain);
    }

    @Override
    public OrderPage findByProfile(ProfileId profileId, int page, int size) {
        Page<OrderEntity> result = orderJpaRepository.findByProfileIdOrderByCreatedAtDesc(
                profileId.value(), PageRequest.of(page, size));
        List<Order> orders = result.getContent().stream()
                .map(OrderPersistenceAdapter::toDomain)
                .toList();
        return new OrderPage(orders, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Override
    public List<Order> findByStatusIn(List<OrderStatus> statuses) {
        return orderJpaRepository.findByStatusInWithItems(statuses).stream()
                .map(OrderPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Order save(Order order) {
        OrderEntity entity = order.id() == null ? newEntity(order) : reconcile(order);
        OrderEntity saved = orderJpaRepository.save(entity);

        UUID orderId = saved.getId();
        for (OrderStatus status : order.drainPendingStatusHistory()) {
            statusHistoryJpaRepository.save(OrderStatusHistoryEntity.builder()
                    .orderId(orderId)
                    .status(status.name())
                    .build());
        }
        return toDomain(saved);
    }

    private static OrderEntity newEntity(Order order) {
        OrderEntity entity = OrderEntity.builder()
                .profileId(order.profileId().value())
                .storeId(order.storeId().value())
                .storeName(order.storeName())
                .status(order.status())
                .paymentType(order.paymentType())
                .paymentStatus(order.paymentStatus())
                .deliveryType(order.deliveryType())
                .deliveryAddress(order.deliveryAddress())
                .pickupCode(order.pickupCode())
                .totalPrice(order.totalPrice())
                .stripePaymentIntentId(order.stripePaymentIntentId())
                .paidAt(order.paidAt())
                .failureCode(order.failureCode())
                .failureMessage(order.failureMessage())
                .build();
        order.items().forEach(item -> entity.getItems().add(newItemEntity(entity, item)));
        return entity;
    }

    /**
     * Writes the aggregate's mutable scalar state onto the managed entity loaded
     * in the current transaction. Order lines are immutable after placement, so
     * they are not reconciled. {@code updatedAt} is left to the database trigger.
     */
    private OrderEntity reconcile(Order order) {
        OrderEntity entity = orderJpaRepository.findById(order.id().value())
                .orElseThrow(() -> new IllegalStateException(
                        "Order disappeared during transaction: " + order.id().value()));
        entity.setStatus(order.status());
        entity.setPaymentStatus(order.paymentStatus());
        entity.setStoreName(order.storeName());
        entity.setPaymentType(order.paymentType());
        entity.setDeliveryType(order.deliveryType());
        entity.setDeliveryAddress(order.deliveryAddress());
        entity.setPickupCode(order.pickupCode());
        entity.setTotalPrice(order.totalPrice());
        entity.setStripePaymentIntentId(order.stripePaymentIntentId());
        entity.setPaidAt(order.paidAt());
        entity.setFailureCode(order.failureCode());
        entity.setFailureMessage(order.failureMessage());
        return entity;
    }

    private static OrderItemEntity newItemEntity(OrderEntity order, OrderItem item) {
        return OrderItemEntity.builder()
                .order(order)
                .surpriseBoxId(item.surpriseBoxId() != null ? item.surpriseBoxId().value() : null)
                .name(item.name())
                .price(item.price())
                .quantity(item.quantity())
                .imageUrl(item.imageUrl())
                .build();
    }

    private static Order toDomain(OrderEntity entity) {
        List<OrderItem> items = entity.getItems().stream()
                .map(OrderPersistenceAdapter::toDomainItem)
                .toList();
        return Order.restore(
                new OrderId(entity.getId()),
                new ProfileId(entity.getProfileId()),
                new StoreId(entity.getStoreId()),
                entity.getStoreName(),
                entity.getStatus(),
                entity.getPaymentType(),
                entity.getPaymentStatus(),
                entity.getDeliveryType(),
                entity.getDeliveryAddress(),
                entity.getPickupCode(),
                items,
                entity.getTotalPrice(),
                entity.getStripePaymentIntentId(),
                entity.getPaidAt(),
                entity.getFailureCode(),
                entity.getFailureMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static OrderItem toDomainItem(OrderItemEntity entity) {
        return OrderItem.restore(
                new OrderItemId(entity.getId()),
                entity.getSurpriseBoxId() != null ? new SurpriseBoxId(entity.getSurpriseBoxId()) : null,
                entity.getName(),
                entity.getPrice(),
                entity.getQuantity(),
                entity.getImageUrl());
    }
}
