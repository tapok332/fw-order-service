package kh.karazin.foodwise.order.adapter.in.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request DTO for a single order item.
 *
 * <p>{@code name} and {@code price} are intentionally NOT accepted from the
 * client (Pack 1, task 1.2). Both are resolved server-side from the
 * authoritative {@code surprise-box-service} response in the placement use case
 * to prevent price tampering — see
 * {@code docs/decisions/0005-server-side-price-recompute.md}.
 */
public record OrderItemRequest(
        @NotNull(message = "Surprise box ID is required")
        UUID surpriseBoxId,

        @Min(value = 1, message = "Quantity must be at least 1")
        int quantity
) {}
