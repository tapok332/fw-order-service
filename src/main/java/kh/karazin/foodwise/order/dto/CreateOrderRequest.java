package kh.karazin.foodwise.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating a new order.
 */
public record CreateOrderRequest(
        @NotNull(message = "Store ID is required")
        UUID storeId,

        @NotEmpty(message = "Order must contain at least one item")
        @Valid
        List<OrderItemRequest> items,

        @NotNull(message = "Payment type is required")
        String paymentType,

        @NotNull(message = "Delivery type is required")
        String deliveryType,

        String deliveryAddress
) {}
