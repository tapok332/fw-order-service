package kh.karazin.foodwise.order.adapter.in.rest;

import kh.karazin.foodwise.order.application.port.in.OrderPage;
import kh.karazin.foodwise.order.application.port.in.PlaceOrderCommand;
import kh.karazin.foodwise.order.application.port.in.PlaceOrderResult;
import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderItem;
import kh.karazin.foodwise.order.domain.StoreId;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;

import java.util.List;

/**
 * Maps between the domain order model and the REST wire shapes. Explicit
 * mapping — the wire contract is pinned independently of the domain model.
 *
 * <p>{@link #toDto(Order)} is {@code public} because the {@code order.status-changed}
 * event reuses {@link OrderDto} as its payload; the messaging adapter calls it
 * to keep that event's JSON byte-identical to the REST representation.
 */
public final class OrderRestMapper {

    private OrderRestMapper() {
    }

    public static OrderDto toDto(Order order) {
        var store = new OrderDto.StoreInfoDto(order.storeId().value(), order.storeName());
        List<OrderDto.OrderItemDto> items = order.items().stream()
                .map(OrderRestMapper::toItemDto)
                .toList();
        return new OrderDto(
                order.id().value(),
                order.profileId().value(),
                store,
                order.status().name(),
                order.paymentType() != null ? order.paymentType().name() : null,
                order.deliveryType() != null ? order.deliveryType().name() : null,
                order.deliveryAddress(),
                order.pickupCode(),
                items,
                order.totalPrice(),
                order.paymentStatus() != null ? order.paymentStatus().name() : null,
                order.stripePaymentIntentId(),
                order.paidAt(),
                order.failureCode(),
                order.failureMessage(),
                order.createdAt(),
                order.updatedAt()
        );
    }

    public static CreateOrderResponse toCreateResponse(PlaceOrderResult result) {
        return new CreateOrderResponse(
                toDto(result.order()),
                result.paymentClientSecret(),
                result.paymentIntentId());
    }

    public static PaginatedOrdersResponse toPage(OrderPage page) {
        List<OrderDto> orders = page.orders().stream()
                .map(OrderRestMapper::toDto)
                .toList();
        return new PaginatedOrdersResponse(
                orders, page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    static PlaceOrderCommand toCommand(CreateOrderRequest request) {
        List<PlaceOrderCommand.Line> lines = request.items().stream()
                .map(item -> new PlaceOrderCommand.Line(new SurpriseBoxId(item.surpriseBoxId()), item.quantity()))
                .toList();
        return new PlaceOrderCommand(
                new StoreId(request.storeId()),
                lines,
                request.paymentType(),
                request.deliveryType(),
                request.deliveryAddress());
    }

    private static OrderDto.OrderItemDto toItemDto(OrderItem item) {
        return new OrderDto.OrderItemDto(
                item.id() != null ? item.id().value() : null,
                item.surpriseBoxId() != null ? item.surpriseBoxId().value() : null,
                item.name(),
                item.price(),
                item.quantity(),
                item.imageUrl());
    }
}
