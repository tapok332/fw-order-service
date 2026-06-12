package kh.karazin.foodwise.order.controller;

import jakarta.validation.Valid;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.response.ApiResponse;
import kh.karazin.foodwise.order.dto.CreateOrderRequest;
import kh.karazin.foodwise.order.dto.CreateOrderResponse;
import kh.karazin.foodwise.order.dto.OrderDto;
import kh.karazin.foodwise.order.dto.PaginatedOrdersResponse;
import kh.karazin.foodwise.order.dto.RefundOrderRequest;
import kh.karazin.foodwise.order.dto.UpdateOrderStatusRequest;
import kh.karazin.foodwise.order.entity.OrderStatus;
import kh.karazin.foodwise.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for order operations.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody CreateOrderRequest request) {
        UUID profileId = parseUserId(userId);
        CreateOrderResponse response = orderService.createOrder(profileId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PaginatedOrdersResponse>> getOrders(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID profileId = parseUserId(userId);
        PaginatedOrdersResponse response = orderService.getOrdersByProfile(profileId, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderDto>> getOrderById(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID orderId) {
        UUID profileId = parseUserId(userId);
        OrderDto order = orderService.getOrderByIdForUser(orderId, profileId);
        return ResponseEntity.ok(ApiResponse.success(order));
    }

    @PutMapping("/{orderId}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderDto>> cancelOrder(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID orderId) {
        UUID profileId = parseUserId(userId);
        OrderDto order = orderService.cancelOrder(orderId, profileId);
        return ResponseEntity.ok(ApiResponse.success(order));
    }

    @PutMapping("/{orderId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<OrderDto>> updateOrderStatus(
            @PathVariable UUID orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        OrderStatus status = OrderStatus.valueOf(request.status());
        OrderDto order = orderService.updateOrderStatus(orderId, status);
        return ResponseEntity.ok(ApiResponse.success(order));
    }

    /**
     * Admin refund. Returns 202 Accepted — the actual refund completes
     * asynchronously when Stripe's {@code charge.refunded} webhook lands.
     */
    @PostMapping("/{orderId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<OrderDto>> refundOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) RefundOrderRequest request) {
        var amount = request != null ? request.amount() : null;
        var reason = request != null ? request.reason() : null;
        OrderDto order = orderService.refundOrder(orderId, amount, reason);
        return ResponseEntity.accepted().body(ApiResponse.success(order));
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST,
                    "Invalid X-User-Id header: " + userId);
        }
    }
}
