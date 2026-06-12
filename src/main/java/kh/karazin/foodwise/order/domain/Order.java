package kh.karazin.foodwise.order.domain;

import kh.karazin.foodwise.common.money.Money;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Order aggregate root. Owns the order invariants and the two lifecycles:
 * fulfillment ({@link OrderStatus}) and payment ({@link OrderPaymentStatus}).
 *
 * <p>Invariants enforced here:
 * <ul>
 *   <li><b>Server-resolved data only</b> — items enter exclusively through
 *       {@link #place(OrderDraft)} from {@link ResolvedOrderLine}s, so name and
 *       unit price always come from surprise-box-service, never the client
 *       (ADR 0005). {@code storeId}/{@code storeName} likewise come from the
 *       store-service lookup.</li>
 *   <li><b>Minimum order amount</b> — an order below the store minimum (or in a
 *       different currency) is rejected at placement (ADR-backed Phase 8.2).</li>
 *   <li><b>Ownership</b> — {@link #assertVisibleTo} / {@link #cancelBy} reject a
 *       caller who is not the owner (ADR 0004 — IDOR closure).</li>
 *   <li><b>Status lifecycle</b> — cancellation is only legal from PENDING;
 *       saga transitions ({@link #markPaid}, {@link #markPaymentFailed},
 *       {@link #expireReservation}) are idempotent.</li>
 * </ul>
 *
 * <p>Every status change appends to {@link #pendingStatusHistory}; the
 * persistence adapter drains it on save and writes the audit rows, so it is
 * impossible to change status without recording history.
 */
public class Order {

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("UAH");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Null until the order is persisted; assigned by the persistence adapter. */
    private final OrderId id;
    private final ProfileId profileId;
    private final StoreId storeId;
    private final String storeName;
    private final PaymentType paymentType;
    private final DeliveryType deliveryType;
    private final String deliveryAddress;
    private final List<OrderItem> items;
    private final Money totalPrice;
    private final Instant createdAt;
    private final Instant updatedAt;

    private OrderStatus status;
    private OrderPaymentStatus paymentStatus;
    private String pickupCode;
    private String stripePaymentIntentId;
    private Instant paidAt;
    private String failureCode;
    private String failureMessage;

    /** Status transitions not yet flushed to the history table. */
    private final List<OrderStatus> pendingStatusHistory = new ArrayList<>();

    private Order(OrderId id, ProfileId profileId, StoreId storeId, String storeName,
                  OrderStatus status, PaymentType paymentType, OrderPaymentStatus paymentStatus,
                  DeliveryType deliveryType, String deliveryAddress, String pickupCode,
                  List<OrderItem> items, Money totalPrice, String stripePaymentIntentId,
                  Instant paidAt, String failureCode, String failureMessage,
                  Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.storeName = storeName;
        this.status = Objects.requireNonNull(status, "status");
        this.paymentType = paymentType;
        this.paymentStatus = Objects.requireNonNull(paymentStatus, "paymentStatus");
        this.deliveryType = deliveryType;
        this.deliveryAddress = deliveryAddress;
        this.pickupCode = pickupCode;
        this.items = new ArrayList<>(items);
        this.totalPrice = Objects.requireNonNull(totalPrice, "totalPrice");
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.paidAt = paidAt;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Places a new order from a fully server-resolved draft: computes the total,
     * enforces the store minimum, sets the initial status (cash orders go
     * straight to the kitchen in PROCESSING; every other payment type waits in
     * PENDING for payment to settle), and records the opening status history.
     *
     * @throws OrderBelowMinimumException if the total is below the store minimum
     *                                    or in a different currency
     */
    public static Order place(OrderDraft draft) {
        Money total = computeTotal(draft.lines());
        enforceMinimum(total, draft.storeMinimum());

        OrderStatus initialStatus =
                draft.paymentType() == PaymentType.CASH ? OrderStatus.PROCESSING : OrderStatus.PENDING;

        List<OrderItem> items = draft.lines().stream()
                .map(OrderItem::newLine)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Order order = new Order(
                null, draft.profileId(), draft.storeId(), draft.storeName(),
                initialStatus, draft.paymentType(), OrderPaymentStatus.PENDING,
                draft.deliveryType(), draft.deliveryAddress(), null,
                items, total, null, null, null, null, null, null);
        order.recordStatus(initialStatus);
        return order;
    }

    /** Restores a persisted order; used by the persistence adapter. */
    public static Order restore(OrderId id, ProfileId profileId, StoreId storeId, String storeName,
                                OrderStatus status, PaymentType paymentType, OrderPaymentStatus paymentStatus,
                                DeliveryType deliveryType, String deliveryAddress, String pickupCode,
                                List<OrderItem> items, Money totalPrice, String stripePaymentIntentId,
                                Instant paidAt, String failureCode, String failureMessage,
                                Instant createdAt, Instant updatedAt) {
        Objects.requireNonNull(id, "id");
        return new Order(id, profileId, storeId, storeName, status, paymentType, paymentStatus,
                deliveryType, deliveryAddress, pickupCode, items, totalPrice, stripePaymentIntentId,
                paidAt, failureCode, failureMessage, createdAt, updatedAt);
    }

    // ─── invariants / commands ──────────────────────────────────────────────

    /**
     * @throws OrderAccessDeniedException if the caller is not the owner
     */
    public void assertVisibleTo(ProfileId caller) {
        if (!profileId.equals(caller)) {
            throw new OrderAccessDeniedException("You are not allowed to view this order");
        }
    }

    /**
     * Cancels the order on the owner's request. Only PENDING orders are
     * cancellable.
     *
     * @throws OrderAccessDeniedException   if the caller is not the owner
     * @throws OrderNotCancellableException if the order is not PENDING
     */
    public void cancelBy(ProfileId caller) {
        if (!profileId.equals(caller)) {
            throw new OrderAccessDeniedException("You are not allowed to cancel this order");
        }
        if (status != OrderStatus.PENDING) {
            throw new OrderNotCancellableException(
                    "Only PENDING orders can be cancelled. Current status: " + status);
        }
        status = OrderStatus.CANCELLED;
        recordStatus(OrderStatus.CANCELLED);
    }

    /**
     * Sets the order status directly (admin / mock-fulfillment scheduler).
     * Any path into READY mints a pickup code if one is not already present.
     */
    public void changeStatus(OrderStatus newStatus) {
        status = newStatus;
        if (newStatus == OrderStatus.READY && pickupCode == null) {
            pickupCode = generatePickupCode();
        }
        recordStatus(newStatus);
    }

    /** Attaches the Stripe PaymentIntent id created at order placement. */
    public void attachStripeIntent(String intentId) {
        this.stripePaymentIntentId = intentId;
    }

    /**
     * Marks the order paid and moves it to PROCESSING. Idempotent — a redelivered
     * {@code payment.completed} for an already-PAID order is a no-op.
     *
     * @return {@code true} if the order transitioned, {@code false} if it was
     *         already PAID (caller should not re-publish downstream events)
     */
    public boolean markPaid() {
        if (paymentStatus == OrderPaymentStatus.PAID) {
            return false;
        }
        paymentStatus = OrderPaymentStatus.PAID;
        paidAt = Instant.now();
        failureCode = null;
        failureMessage = null;
        status = OrderStatus.PROCESSING;
        recordStatus(OrderStatus.PROCESSING);
        return true;
    }

    /**
     * Marks payment failed and cancels the order. Idempotent — a redelivered
     * failure for an already failed/cancelled order is a no-op.
     *
     * @return {@code true} if the order transitioned, {@code false} otherwise
     */
    public boolean markPaymentFailed(String reason) {
        if (paymentStatus == OrderPaymentStatus.FAILED || status == OrderStatus.CANCELLED) {
            return false;
        }
        paymentStatus = OrderPaymentStatus.FAILED;
        // We only have a string reason on the wire. The Stripe error code lives in
        // payment-service / its webhook trail; surface the human message here.
        failureMessage = reason != null && reason.length() > 1000 ? reason.substring(0, 1000) : reason;
        status = OrderStatus.CANCELLED;
        recordStatus(OrderStatus.CANCELLED);
        return true;
    }

    /**
     * Cancels the order because its surprise-box reservation expired. No-op for
     * terminal orders.
     *
     * @return {@code true} if the order transitioned, {@code false} otherwise
     */
    public boolean expireReservation() {
        if (status == OrderStatus.CANCELLED || status == OrderStatus.COMPLETED) {
            return false;
        }
        status = OrderStatus.CANCELLED;
        recordStatus(OrderStatus.CANCELLED);
        return true;
    }

    /**
     * @throws OrderNotRefundableException if the order is not a paid Stripe order
     */
    public void assertRefundable() {
        if (paymentType != PaymentType.STRIPE) {
            throw new OrderNotRefundableException("Refund is only supported for Stripe orders");
        }
        if (paymentStatus != OrderPaymentStatus.PAID) {
            throw new OrderNotRefundableException(
                    "Only PAID orders can be refunded. Current paymentStatus: " + paymentStatus);
        }
    }

    /** Returns and clears the status transitions awaiting an audit row. */
    public List<OrderStatus> drainPendingStatusHistory() {
        List<OrderStatus> drained = List.copyOf(pendingStatusHistory);
        pendingStatusHistory.clear();
        return drained;
    }

    private void recordStatus(OrderStatus status) {
        pendingStatusHistory.add(status);
    }

    private static Money computeTotal(List<ResolvedOrderLine> lines) {
        // Reduce identity is a zero in the currency of the first line so plus()
        // never sees a currency mismatch. The UAH fallback only ever applies to
        // the empty case, which OrderDraft already forbids.
        Currency currency = lines.stream()
                .findFirst()
                .map(line -> line.price().currency())
                .orElse(DEFAULT_CURRENCY);
        Money zero = Money.ofMinor(0L, currency);
        return lines.stream()
                .map(ResolvedOrderLine::lineTotal)
                .reduce(zero, Money::plus);
    }

    private static void enforceMinimum(Money total, Money minOrderAmount) {
        if (minOrderAmount == null) {
            return;
        }
        if (!total.currency().equals(minOrderAmount.currency())) {
            throw new OrderBelowMinimumException(
                    "Order currency " + total.currency().getCurrencyCode()
                            + " does not match store minimum currency "
                            + minOrderAmount.currency().getCurrencyCode());
        }
        if (total.amountMinor() < minOrderAmount.amountMinor()) {
            throw new OrderBelowMinimumException(
                    "Order total " + total.toMajor() + " " + total.currency().getCurrencyCode()
                            + " is below the store minimum of " + minOrderAmount.toMajor()
                            + " " + minOrderAmount.currency().getCurrencyCode());
        }
    }

    private static String generatePickupCode() {
        int code = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(code);
    }

    // ─── accessors ──────────────────────────────────────────────────────────

    public OrderId id() {
        return id;
    }

    public ProfileId profileId() {
        return profileId;
    }

    public StoreId storeId() {
        return storeId;
    }

    public String storeName() {
        return storeName;
    }

    public OrderStatus status() {
        return status;
    }

    public PaymentType paymentType() {
        return paymentType;
    }

    public OrderPaymentStatus paymentStatus() {
        return paymentStatus;
    }

    public DeliveryType deliveryType() {
        return deliveryType;
    }

    public String deliveryAddress() {
        return deliveryAddress;
    }

    public String pickupCode() {
        return pickupCode;
    }

    /** Read-only view of the order lines, in placement order. */
    public List<OrderItem> items() {
        return List.copyOf(items);
    }

    public Money totalPrice() {
        return totalPrice;
    }

    public String stripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
