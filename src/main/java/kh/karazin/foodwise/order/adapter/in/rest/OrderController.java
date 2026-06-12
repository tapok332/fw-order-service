package kh.karazin.foodwise.order.adapter.in.rest;

import jakarta.validation.Valid;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.response.ApiResponse;
import kh.karazin.foodwise.order.application.port.in.OrderUseCase;
import kh.karazin.foodwise.order.application.port.in.PlaceOrderResult;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.OrderStatus;
import kh.karazin.foodwise.order.domain.ProfileId;
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
 * REST adapter for order operations.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderUseCase orderUseCase;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody CreateOrderRequest request) {
        PlaceOrderResult result = orderUseCase.placeOrder(parseUserId(userId), OrderRestMapper.toCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(OrderRestMapper.toCreateResponse(result)));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PaginatedOrdersResponse>> getOrders(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var ordersPage = orderUseCase.listForProfile(parseUserId(userId), page, size);
        return ResponseEntity.ok(ApiResponse.success(OrderRestMapper.toPage(ordersPage)));
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderDto>> getOrderById(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID orderId) {
        var order = orderUseCase.getForUser(new OrderId(orderId), parseUserId(userId));
        return ResponseEntity.ok(ApiResponse.success(OrderRestMapper.toDto(order)));
    }

    @PutMapping("/{orderId}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderDto>> cancelOrder(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID orderId) {
        var order = orderUseCase.cancel(new OrderId(orderId), parseUserId(userId));
        return ResponseEntity.ok(ApiResponse.success(OrderRestMapper.toDto(order)));
    }

    @PutMapping("/{orderId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<OrderDto>> updateOrderStatus(
            @PathVariable UUID orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        OrderStatus status = OrderStatus.valueOf(request.status());
        var order = orderUseCase.changeStatus(new OrderId(orderId), status);
        return ResponseEntity.ok(ApiResponse.success(OrderRestMapper.toDto(order)));
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
        var order = orderUseCase.refund(new OrderId(orderId), amount, reason);
        return ResponseEntity.accepted().body(ApiResponse.success(OrderRestMapper.toDto(order)));
    }

    private ProfileId parseUserId(String userId) {
        try {
            return new ProfileId(UUID.fromString(userId));
        } catch (IllegalArgumentException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST,
                    "Invalid X-User-Id header: " + userId);
        }
    }
}
