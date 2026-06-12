package kh.karazin.foodwise.order.dto;

import jakarta.validation.constraints.Positive;

/**
 * Admin request to refund an order.
 *
 * @param amount minor units to refund (null = full refund)
 * @param reason free-form reason recorded in metadata
 */
public record RefundOrderRequest(
        @Positive Integer amount,
        String reason
) {}
