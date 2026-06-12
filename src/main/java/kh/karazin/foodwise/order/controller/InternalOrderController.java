package kh.karazin.foodwise.order.controller;

import kh.karazin.foodwise.common.response.ApiResponse;
import kh.karazin.foodwise.order.dto.OrderDto;
import kh.karazin.foodwise.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal REST controller for inter-service communication.
 */
@RestController
@RequestMapping("/internal/orders")
@RequiredArgsConstructor
public class InternalOrderController {

    private final OrderService orderService;

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderDto>> getOrderById(@PathVariable UUID orderId) {
        // Unscoped lookup is intentional here — this endpoint is reachable only
        // from other services authenticated by X-Internal-Token, never from the
        // public gateway. See ADR 0004 and OrderService.getOrderByIdUnscoped.
        OrderDto order = orderService.getOrderByIdUnscoped(orderId);
        return ResponseEntity.ok(ApiResponse.success(order));
    }
}
