package kh.karazin.foodwise.order.application.usecase;

import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.order.application.port.in.OrderPage;
import kh.karazin.foodwise.order.application.port.in.OrderUseCase;
import kh.karazin.foodwise.order.application.port.in.PlaceOrderCommand;
import kh.karazin.foodwise.order.application.port.in.PlaceOrderResult;
import kh.karazin.foodwise.order.application.port.out.OrderEventPublisher;
import kh.karazin.foodwise.order.application.port.out.OrderRepository;
import kh.karazin.foodwise.order.application.port.out.PaymentGateway;
import kh.karazin.foodwise.order.application.port.out.ProfileGateway;
import kh.karazin.foodwise.order.application.port.out.StoreGateway;
import kh.karazin.foodwise.order.application.port.out.SurpriseBoxGateway;
import kh.karazin.foodwise.order.domain.DeliveryType;
import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderAccessDeniedException;
import kh.karazin.foodwise.order.domain.OrderBelowMinimumException;
import kh.karazin.foodwise.order.domain.OrderDraft;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.OrderNotCancellableException;
import kh.karazin.foodwise.order.domain.OrderNotRefundableException;
import kh.karazin.foodwise.order.domain.OrderStatus;
import kh.karazin.foodwise.order.domain.PaymentType;
import kh.karazin.foodwise.order.domain.ProfileId;
import kh.karazin.foodwise.order.domain.ResolvedOrderLine;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application service orchestrating user- and admin-driven order use cases.
 * The {@link Order} aggregate decides; this layer resolves data through the
 * outbound ports and translates domain failures into the service-level error
 * contract.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class OrderService implements OrderUseCase {

    private final OrderRepository orderRepository;
    private final StoreGateway storeGateway;
    private final ProfileGateway profileGateway;
    private final SurpriseBoxGateway surpriseBoxGateway;
    private final PaymentGateway paymentGateway;
    private final OrderEventPublisher eventPublisher;

    @Override
    @Transactional
    public PlaceOrderResult placeOrder(ProfileId profileId, PlaceOrderCommand command) {
        if (!profileGateway.profileExists(profileId)) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.ENTITY_NOT_FOUND,
                    "Profile not found: " + profileId.value());
        }

        // One round-trip: store name (denormalised onto the order) + minOrderAmount
        // (the placement guard). getStore propagates a typed 404 for an unknown
        // store; null means a downstream outage — fail closed (ADR 0006).
        StoreGateway.StoreSnapshot store = storeGateway.getStore(command.storeId());
        if (store == null) {
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.SERVICE_UNAVAILABLE, "Store lookup unavailable, please retry");
        }

        List<ResolvedOrderLine> lines = resolveLines(command);

        PaymentType paymentType = parsePaymentType(command.paymentType());
        DeliveryType deliveryType = DeliveryType.valueOf(command.deliveryType());

        Order order;
        try {
            order = Order.place(new OrderDraft(
                    profileId, command.storeId(), store.name(), paymentType, deliveryType,
                    command.deliveryAddress(), lines, store.minOrderAmount()));
        } catch (OrderBelowMinimumException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.ORDER_BELOW_MINIMUM, e.getMessage());
        }

        order = orderRepository.save(order);

        // Stripe intent — atomic with order create. Failure rolls back everything,
        // so an order is never left without a settled intent.
        String paymentClientSecret = null;
        String paymentIntentId = null;
        if (paymentType == PaymentType.STRIPE) {
            // Hold stock before charging: the order-linked reservation expires and
            // cancels the order if payment is not secured in time (ADR 0015). An
            // out-of-stock / unknown box rejects the order before any intent; a
            // reservation orphaned by a later rollback self-heals via expiry.
            reserveBoxes(order, profileId, command);

            PaymentGateway.PaymentIntent intent = paymentGateway.createStripeIntent(
                    order.id(), profileId, order.totalPrice(), "Order " + order.id().value());
            order.attachStripeIntent(intent.paymentIntentId());
            order = orderRepository.save(order);
            paymentClientSecret = intent.clientSecret();
            paymentIntentId = intent.paymentIntentId();
        }

        eventPublisher.orderCreated(order);
        log.info("Order created: {} (paymentType={}, intent={})",
                order.id().value(), order.paymentType(), paymentIntentId);
        return new PlaceOrderResult(order, paymentClientSecret, paymentIntentId);
    }

    /**
     * Resolves every line's name and unit price server-side from
     * surprise-box-service. The client supplies only {@code (boxId, quantity)};
     * any price the caller might attempt to inject is structurally absent and
     * physically ignored here (ADR 0005). A box that cannot be priced rejects the
     * whole order with a generic 503 so the response cannot probe box existence.
     */
    private List<ResolvedOrderLine> resolveLines(PlaceOrderCommand command) {
        Map<SurpriseBoxId, SurpriseBoxGateway.ResolvedBox> resolved = new LinkedHashMap<>();
        for (PlaceOrderCommand.Line line : command.items()) {
            if (resolved.containsKey(line.boxId())) {
                continue;
            }
            SurpriseBoxGateway.ResolvedBox box = surpriseBoxGateway.resolve(line.boxId());
            if (box == null) {
                log.warn("Order rejected: surprise-box {} unresolved (pricing unavailable)", line.boxId().value());
                throw FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.SERVICE_UNAVAILABLE, "Pricing unavailable, please retry");
            }
            resolved.put(line.boxId(), box);
        }
        return command.items().stream()
                .map(line -> {
                    SurpriseBoxGateway.ResolvedBox box = resolved.get(line.boxId());
                    return new ResolvedOrderLine(line.boxId(), box.title(), box.price(), line.quantity());
                })
                .toList();
    }

    /**
     * Reserves each distinct box of the order in surprise-box-service, holding
     * stock for the order awaiting payment (ADR 0015). A box that cannot be
     * reserved because the downstream is unhealthy rejects the order with a
     * generic 503; an out-of-stock (409) or unknown box (404) propagates as the
     * gateway's typed failure. Any failure rolls back the order transaction.
     */
    private void reserveBoxes(Order order, ProfileId profileId, PlaceOrderCommand command) {
        Set<SurpriseBoxId> reserved = new LinkedHashSet<>();
        for (PlaceOrderCommand.Line line : command.items()) {
            if (!reserved.add(line.boxId())) {
                continue;
            }
            if (!surpriseBoxGateway.reserve(line.boxId(), order.id(), profileId)) {
                log.warn("Order {} rejected: reservation unavailable for box {}",
                        order.id().value(), line.boxId().value());
                throw FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.SERVICE_UNAVAILABLE, "Reservation unavailable, please retry");
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Order getForUser(OrderId orderId, ProfileId profileId) {
        Order order = requireOrder(orderId);
        try {
            order.assertVisibleTo(profileId);
        } catch (OrderAccessDeniedException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.FORBIDDEN, e.getMessage());
        }
        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public Order getUnscoped(OrderId orderId) {
        return requireOrder(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage listForProfile(ProfileId profileId, int page, int size) {
        return orderRepository.findByProfile(profileId, page, size);
    }

    @Override
    @Transactional
    public Order cancel(OrderId orderId, ProfileId profileId) {
        Order order = requireOrder(orderId);
        try {
            order.cancelBy(profileId);
        } catch (OrderAccessDeniedException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.FORBIDDEN, e.getMessage());
        } catch (OrderNotCancellableException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST, e.getMessage());
        }
        order = orderRepository.save(order);
        eventPublisher.orderCancelled(order, "Cancelled by user");
        log.info("Order cancelled: {}", orderId.value());
        return order;
    }

    @Override
    @Transactional
    public Order changeStatus(OrderId orderId, OrderStatus newStatus) {
        Order order = requireOrder(orderId);
        order.changeStatus(newStatus);
        order = orderRepository.save(order);
        eventPublisher.orderStatusChanged(order);
        log.info("Order {} status updated to {}", orderId.value(), newStatus);
        return order;
    }

    @Override
    @Transactional
    public Order refund(OrderId orderId, Integer amount, String reason) {
        Order order = requireOrder(orderId);
        try {
            order.assertRefundable();
        } catch (OrderNotRefundableException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST, e.getMessage());
        }
        paymentGateway.refundByOrder(orderId, amount, reason);
        log.info("Refund requested for order {} (amount={}, reason={})", orderId.value(), amount, reason);
        return order;
    }

    private PaymentType parsePaymentType(String raw) {
        try {
            return PaymentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw FoodWiseException.errorWithDescription(FoodWiseErrorCode.INVALID_REQUEST,
                    "Unsupported paymentType: " + raw);
        }
    }

    private Order requireOrder(OrderId orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.ENTITY_NOT_FOUND, "Order not found: " + orderId.value()));
    }
}
