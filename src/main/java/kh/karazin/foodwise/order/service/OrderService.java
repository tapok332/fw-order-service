package kh.karazin.foodwise.order.service;

import kh.karazin.foodwise.common.dto.internal.InternalSurpriseBoxDto;
import kh.karazin.foodwise.common.event.EventTopics;
import kh.karazin.foodwise.common.event.payload.OrderCancelledPayload;
import kh.karazin.foodwise.common.event.payload.OrderCreatedPayload;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.common.outbox.OutboxPublisher;
import kh.karazin.foodwise.order.client.PaymentServiceClient;
import kh.karazin.foodwise.order.client.ProfileServiceClient;
import kh.karazin.foodwise.order.client.StoreServiceClient;
import kh.karazin.foodwise.order.client.SurpriseBoxServiceClient;
import kh.karazin.foodwise.order.config.OrderProperties;
import kh.karazin.foodwise.order.dto.CreateOrderRequest;
import kh.karazin.foodwise.order.dto.CreateOrderResponse;
import kh.karazin.foodwise.order.dto.OrderDto;
import kh.karazin.foodwise.order.dto.PaginatedOrdersResponse;
import kh.karazin.foodwise.order.entity.DeliveryType;
import kh.karazin.foodwise.order.entity.OrderEntity;
import kh.karazin.foodwise.order.entity.OrderItemEntity;
import kh.karazin.foodwise.order.entity.OrderStatus;
import kh.karazin.foodwise.order.entity.OrderStatusHistoryEntity;
import kh.karazin.foodwise.order.entity.PaymentType;
import kh.karazin.foodwise.order.repository.OrderRepository;
import kh.karazin.foodwise.order.repository.OrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core service for order operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OutboxPublisher outboxPublisher;
    private final StoreServiceClient storeServiceClient;
    private final ProfileServiceClient profileServiceClient;
    private final PaymentServiceClient paymentServiceClient;
    private final SurpriseBoxServiceClient surpriseBoxServiceClient;
    private final OrderProperties orderProperties;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Empty-cart fallback currency for the price-reduce identity only. Real order
     * totals derive their currency from the resolved box prices (Money carries its
     * own currency); this constant just gives the zero identity a currency when the
     * (already-validated-non-empty) item stream has no resolved box.
     */
    private static final Currency UAH = Currency.getInstance("UAH");

    /**
     * Creates a new order in PENDING status and publishes an order.created event via outbox.
     *
     * <p>For {@link PaymentType#STRIPE} orders: synchronously creates a Stripe
     * PaymentIntent via payment-service in the same transaction. If Stripe is
     * unavailable the whole order rolls back — never leave an order without an
     * intent.
     */
    @Transactional
    public CreateOrderResponse createOrder(UUID profileId, CreateOrderRequest request) {
        // Validate profile exists
        if (!profileServiceClient.profileExists(profileId)) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.ENTITY_NOT_FOUND,
                    "Profile not found: " + profileId);
        }

        // Fetch the store once — we need both its name (denormalized onto the
        // order) and its minOrderAmount (the Phase 8.2 guard below). A single
        // round-trip; getStore propagates a typed FoodWiseException (404 for an
        // unknown store, per ADR 0006) or returns null on a downstream outage.
        var store = storeServiceClient.getStore(request.storeId());
        if (store == null) {
            // Infra failure (breaker open / 5xx). Fail closed rather than create
            // an order against an unverified store.
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                    "Store lookup unavailable, please retry");
        }
        String storeName = store.name();

        // Resolve every line item's name and unit price server-side from
        // surprise-box-service. The client supplies only {surpriseBoxId, quantity}
        // — any price or name fields the client might attempt to inject are
        // structurally absent from OrderItemRequest and physically ignored here
        // (Pack 1, task 1.2 — closes the client-supplied price tampering vector;
        // see docs/decisions/0005-server-side-price-recompute.md).
        Map<UUID, InternalSurpriseBoxDto> resolvedBoxes = new LinkedHashMap<>();
        for (var itemReq : request.items()) {
            UUID boxId = itemReq.surpriseBoxId();
            if (resolvedBoxes.containsKey(boxId)) {
                continue;
            }
            InternalSurpriseBoxDto box = surpriseBoxServiceClient.getSurpriseBox(boxId);
            if (box == null) {
                // null means the circuit breaker fallback fired (downstream is
                // unhealthy). We refuse the order rather than charge the customer
                // based on a stale or unknown price — financial integrity wins
                // over availability here (see ADR 0005). Log discriminates the
                // cause server-side; the client gets a generic 503 so the error
                // response cannot be used to probe for surprise-box existence.
                log.warn("Order rejected: surprise-box {} unresolved (circuit breaker fallback)", boxId);
                throw FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                        "Pricing unavailable, please retry");
            }
            if (box.price() == null || box.title() == null) {
                log.warn("Order rejected: surprise-box {} payload incomplete (price/title missing)", boxId);
                throw FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                        "Pricing unavailable, please retry");
            }
            resolvedBoxes.put(boxId, box);
        }

        // Calculate total price from server-resolved unit prices, not from any
        // value the client might have tried to supply. Box prices are Money
        // end-to-end (ADR 0012); the reduce identity is a zero in the currency of
        // the first resolved box so plus() never sees a currency mismatch. The
        // UAH fallback only ever applies to the (validated-non-empty) empty case.
        Currency currency = resolvedBoxes.values().stream()
                .findFirst()
                .map(box -> box.price().currency())
                .orElse(UAH);
        Money zero = Money.ofMinor(0L, currency);
        Money totalPrice = request.items().stream()
                .map(item -> resolvedBoxes.get(item.surpriseBoxId()).price()
                        .times(item.quantity()))
                .reduce(zero, Money::plus);

        // Phase 8.2 — min-order-amount guard. An order below the store's minimum
        // is invalid for ANY payment type (cash included), so this runs before
        // the Stripe round-trip — we never ask the payment provider to create an
        // intent for an order we already know we'll reject.
        enforceMinimumOrderAmount(totalPrice, store.minOrderAmount());

        PaymentType paymentType = parsePaymentType(request.paymentType());

        // Cash orders skip the payment gate and go straight to the kitchen.
        // Every other type stays PENDING until payment settles (Stripe:
        // payment.completed saga moves it to PROCESSING). The mock fulfillment
        // scheduler only advances orders already in the kitchen.
        OrderStatus initialStatus =
                paymentType == PaymentType.CASH ? OrderStatus.PROCESSING : OrderStatus.PENDING;

        // Build order entity
        OrderEntity order = OrderEntity.builder()
                .profileId(profileId)
                .storeId(request.storeId())
                .storeName(storeName)
                .status(initialStatus)
                .paymentType(paymentType)
                .paymentStatus(kh.karazin.foodwise.order.entity.OrderPaymentStatus.PENDING)
                .deliveryType(DeliveryType.valueOf(request.deliveryType()))
                .deliveryAddress(request.deliveryAddress())
                .totalPrice(totalPrice)
                .build();

        // Build order items with server-resolved name and unit price (Money, ADR 0012).
        var items = request.items().stream()
                .map(itemReq -> {
                    InternalSurpriseBoxDto box = resolvedBoxes.get(itemReq.surpriseBoxId());
                    return OrderItemEntity.builder()
                            .order(order)
                            .surpriseBoxId(itemReq.surpriseBoxId())
                            .name(box.title())
                            .price(box.price())
                            .quantity(itemReq.quantity())
                            .build();
                })
                .toList();
        order.setItems(items);

        OrderEntity saved = orderRepository.save(order);

        // Record status history
        statusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(saved.getId())
                .status(initialStatus.name())
                .build());

        // Stripe intent — atomic with order create. Failure rolls back everything.
        String paymentClientSecret = null;
        String paymentIntentId = null;
        if (paymentType == PaymentType.STRIPE) {
            // currency is folded inside the Money value — no separate currency arg needed
            var intent = paymentServiceClient.createStripeIntent(
                    saved.getId(),
                    saved.getProfileId(),
                    saved.getTotalPrice(),
                    "Order " + saved.getId()
            );
            saved.setStripePaymentIntentId(intent.paymentIntentId());
            saved = orderRepository.save(saved);
            paymentClientSecret = intent.clientSecret();
            paymentIntentId = intent.paymentIntentId();
        }

        // Publish order.created event via outbox — price/totalPrice flow as Money
        // end-to-end; the payload no longer carries a separate currency field (ADR 0012).
        var itemPayloads = saved.getItems().stream()
                .map(item -> new OrderCreatedPayload.OrderItemPayload(
                        item.getId(),
                        item.getSurpriseBoxId(),
                        item.getName(),
                        item.getPrice(),
                        item.getQuantity()
                ))
                .toList();

        var payload = new OrderCreatedPayload(
                saved.getId(),
                saved.getProfileId(),
                saved.getStoreId(),
                saved.getStoreName(),
                itemPayloads,
                saved.getTotalPrice(),
                saved.getPaymentType().name(),
                saved.getDeliveryType().name(),
                saved.getDeliveryAddress()
        );

        outboxPublisher.saveEvent(
                EventTopics.ORDER_CREATED,
                saved.getId().toString(),
                "order.created",
                payload,
                saved.getId()
        );

        log.info("Order created: {} (paymentType={}, intent={})",
                saved.getId(), saved.getPaymentType(), paymentIntentId);
        return new CreateOrderResponse(saved.toDto(), paymentClientSecret, paymentIntentId);
    }

    /**
     * Rejects orders whose total is below the store's configured minimum.
     *
     * <p>No-op when {@code minOrderAmount} is {@code null} (store has no
     * minimum). A currency mismatch between the order total and the store
     * minimum is itself a validation error — we cannot meaningfully compare
     * across currencies, and the order has no business being created.
     *
     * @throws FoodWiseException {@code ORDER_BELOW_MINIMUM} (422) when the total
     *         is below the minimum or the currencies differ
     */
    private void enforceMinimumOrderAmount(Money total, Money minOrderAmount) {
        if (minOrderAmount == null) {
            return;
        }
        if (!total.currency().equals(minOrderAmount.currency())) {
            log.warn("Order rejected: currency mismatch total={} vs store minimum={}",
                    total.currency().getCurrencyCode(), minOrderAmount.currency().getCurrencyCode());
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.ORDER_BELOW_MINIMUM,
                    "Order currency " + total.currency().getCurrencyCode()
                            + " does not match store minimum currency "
                            + minOrderAmount.currency().getCurrencyCode());
        }
        if (total.amountMinor() < minOrderAmount.amountMinor()) {
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.ORDER_BELOW_MINIMUM,
                    "Order total " + total.toMajor() + " " + total.currency().getCurrencyCode()
                            + " is below the store minimum of " + minOrderAmount.toMajor()
                            + " " + minOrderAmount.currency().getCurrencyCode());
        }
    }

    private PaymentType parsePaymentType(String raw) {
        try {
            return PaymentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST,
                    "Unsupported paymentType: " + raw);
        }
    }

    /**
     * Returns an order by ID with no caller-identity check.
     *
     * <p><b>Internal-only.</b> Only safe to call from contexts where the caller
     * is trusted to look at any order — currently {@code InternalOrderController}
     * which sits behind the {@code X-Internal-Token} guard ({@code InternalAuthFilter})
     * and is never reachable from the public gateway. The user-facing
     * {@code OrderController.getOrderById} must use {@link #getOrderByIdForUser}
     * instead, which scopes the lookup to the calling profile.
     *
     * <p>The {@code Unscoped} suffix is deliberate friction: any new caller has
     * to type "unscoped" and so will pause to think before re-introducing the
     * IDOR closed in Pack 1.
     */
    @Transactional(readOnly = true)
    public OrderDto getOrderByIdUnscoped(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(OrderEntity::toDto)
                .orElseThrow(() -> FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.ENTITY_NOT_FOUND, "Order not found: " + orderId));
    }

    /**
     * Returns an order by ID, scoped to the caller's profile.
     *
     * <p>Pack 1, task 1.1 — closes the IDOR on {@code GET /orders/{orderId}}.
     * The lookup follows the same shape as {@link #cancelOrder}: load the
     * entity, compare {@code profileId} against the caller and throw
     * {@code FORBIDDEN} (not 404) on mismatch. Returning 403 rather than 404
     * is the explicit choice — the order exists, the caller just may not see
     * it; the response code matches the semantic and is consistent with the
     * sibling {@code cancelOrder} flow which uses the same idiom.
     */
    @Transactional(readOnly = true)
    public OrderDto getOrderByIdForUser(UUID orderId, UUID profileId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.ENTITY_NOT_FOUND, "Order not found: " + orderId));

        if (!order.getProfileId().equals(profileId)) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.FORBIDDEN,
                    "You are not allowed to view this order");
        }

        return order.toDto();
    }

    /**
     * Gets paginated orders for a profile.
     */
    @Transactional(readOnly = true)
    public PaginatedOrdersResponse getOrdersByProfile(UUID profileId, int page, int size) {
        var pageable = PageRequest.of(page, size);
        var pageResult = orderRepository.findByProfileIdOrderByCreatedAtDesc(profileId, pageable);
        var orderDtos = pageResult.getContent().stream()
                .map(OrderEntity::toDto)
                .toList();
        return new PaginatedOrdersResponse(
                orderDtos,
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages()
        );
    }

    /**
     * Cancels an order. Only PENDING orders can be cancelled.
     */
    @Transactional
    public OrderDto cancelOrder(UUID orderId, UUID profileId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.ENTITY_NOT_FOUND, "Order not found: " + orderId));

        if (!order.getProfileId().equals(profileId)) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.FORBIDDEN,
                    "You are not allowed to cancel this order");
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST,
                    "Only PENDING orders can be cancelled. Current status: " + order.getStatus());
        }

        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        statusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(orderId)
                .status(OrderStatus.CANCELLED.name())
                .build());

        // Publish order.cancelled event
        var cancelledItems = order.getItems().stream()
                .map(item -> new OrderCancelledPayload.CancelledItemPayload(
                        item.getSurpriseBoxId(),
                        item.getQuantity()
                ))
                .toList();

        var payload = new OrderCancelledPayload(
                order.getId(),
                order.getProfileId(),
                order.getStoreId(),
                "Cancelled by user",
                cancelledItems
        );

        outboxPublisher.saveEvent(
                EventTopics.ORDER_CANCELLED,
                orderId.toString(),
                "order.cancelled",
                payload,
                orderId
        );

        log.info("Order cancelled: {}", orderId);
        return order.toDto();
    }

    /**
     * Updates order status (admin operation).
     */
    @Transactional
    public OrderDto updateOrderStatus(UUID orderId, OrderStatus newStatus) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.ENTITY_NOT_FOUND, "Order not found: " + orderId));

        order.setStatus(newStatus);
        // Any path into READY must yield a pickup code; guard keeps it stable
        // if the order re-enters READY for any reason.
        if (newStatus == OrderStatus.READY && order.getPickupCode() == null) {
            order.setPickupCode(generatePickupCode());
        }
        orderRepository.save(order);

        statusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(orderId)
                .status(newStatus.name())
                .build());

        // Publish status change event via outbox
        outboxPublisher.saveEvent(
                EventTopics.ORDER_STATUS_CHANGED,
                orderId.toString(),
                "order.status-changed",
                order.toDto(),
                orderId
        );

        log.info("Order {} status updated to {}", orderId, newStatus);
        return order.toDto();
    }

    /**
     * Generates a random 6-digit pickup code.
     */
    public String generatePickupCode() {
        int code = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(code);
    }

    /**
     * Admin refund: triggers a Stripe refund via payment-service. Idempotent —
     * resulting RefundEntity is created in payment-service from the
     * {@code charge.refunded} webhook. Order.paymentStatus is updated by the
     * same webhook propagation via {@link OrderSagaHandler}.
     */
    @Transactional
    public OrderDto refundOrder(UUID orderId, Integer amount, String reason) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.ENTITY_NOT_FOUND, "Order not found: " + orderId));

        if (order.getPaymentType() != PaymentType.STRIPE) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST,
                    "Refund is only supported for Stripe orders");
        }
        if (order.getPaymentStatus() != kh.karazin.foodwise.order.entity.OrderPaymentStatus.PAID) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST,
                    "Only PAID orders can be refunded. Current paymentStatus: "
                            + order.getPaymentStatus());
        }

        paymentServiceClient.refundByOrder(orderId, amount, reason);
        log.info("Refund requested for order {} (amount={}, reason={})", orderId, amount, reason);
        return order.toDto();
    }
}
