package kh.karazin.foodwise.order.dto;

import java.util.List;

/**
 * Paginated response wrapper for orders.
 */
public record PaginatedOrdersResponse(
        List<OrderDto> orders,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
