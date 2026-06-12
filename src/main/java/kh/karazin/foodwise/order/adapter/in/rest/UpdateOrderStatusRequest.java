package kh.karazin.foodwise.order.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for updating order status (admin operation).
 */
public record UpdateOrderStatusRequest(
        @NotBlank(message = "Status is required")
        String status
) {}
