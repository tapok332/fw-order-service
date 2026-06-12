package kh.karazin.foodwise.order.application.port.in;

import kh.karazin.foodwise.order.domain.Order;

import java.util.List;

/**
 * A page of orders plus pagination metadata, returned by the list use case.
 */
public record OrderPage(
        List<Order> orders,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
