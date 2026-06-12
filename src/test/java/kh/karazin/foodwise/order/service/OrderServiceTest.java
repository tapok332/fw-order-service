package kh.karazin.foodwise.order.service;

import kh.karazin.foodwise.common.dto.internal.InternalSurpriseBoxDto;
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
import kh.karazin.foodwise.order.dto.OrderItemRequest;
import kh.karazin.foodwise.order.entity.OrderEntity;
import kh.karazin.foodwise.order.entity.OrderStatus;
import kh.karazin.foodwise.order.entity.PaymentType;
import kh.karazin.foodwise.order.repository.OrderRepository;
import kh.karazin.foodwise.order.repository.OrderStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderStatusHistoryRepository statusHistoryRepository;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private StoreServiceClient storeServiceClient;
    @Mock private ProfileServiceClient profileServiceClient;
    @Mock private PaymentServiceClient paymentServiceClient;
    @Mock private SurpriseBoxServiceClient surpriseBoxServiceClient;
    // A plain @Spy on the record so we don't have to mock individual getters.
    @Spy private OrderProperties orderProperties = new OrderProperties("UAH", "uah", true);

    @InjectMocks private OrderService orderService;

    private static final Currency UAH = Currency.getInstance("UAH");

    private OrderEntity orderOwnedBy(UUID profileId) {
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .profileId(profileId)
                .storeId(UUID.randomUUID())
                .storeName("Test store")
                .status(OrderStatus.PENDING)
                .totalPrice(Money.ofMinor(10000L, UAH))
                .items(new ArrayList<>())
                .build();
    }

    /**
     * Pack 1, task 1.1 — IDOR closure on {@code GET /orders/{orderId}}.
     *
     * <p>Before this pack, {@link OrderService#getOrderById(UUID)} returned
     * the full {@code OrderDto} (delivery address, pickup code, totalPrice,
     * line items) to any authenticated caller who guessed or scraped the
     * order's UUID. The new {@link OrderService#getOrderByIdForUser(UUID, UUID)}
     * variant — used by the public {@code OrderController} — enforces
     * profile-id ownership and is the only entry point reachable from the
     * gateway. The unscoped {@code getOrderById} remains, but only the
     * internal-only {@code InternalOrderController} (protected by
     * {@code X-Internal-Token}) calls it.
     */
    @Nested
    @DisplayName("getOrderByIdForUser — ownership enforcement (Pack 1, task 1.1)")
    class GetOrderByIdForUser {

        @Test
        @DisplayName("returns the order when the caller owns it")
        void returnsOrder_whenProfileMatches() {
            UUID owner = UUID.randomUUID();
            OrderEntity order = orderOwnedBy(owner);
            when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

            var result = orderService.getOrderByIdForUser(order.getId(), owner);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(order.getId());
            assertThat(result.profileId()).isEqualTo(owner);
        }

        @Test
        @DisplayName("throws FORBIDDEN (not 404) when another user requests the order — IDOR closed")
        void throwsForbidden_whenProfileMismatch() {
            UUID owner = UUID.randomUUID();
            UUID attacker = UUID.randomUUID();
            OrderEntity order = orderOwnedBy(owner);
            when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.getOrderByIdForUser(order.getId(), attacker))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                            e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                    .containsExactly(FoodWiseErrorCode.FORBIDDEN.getMessage(),
                            FoodWiseErrorCode.FORBIDDEN.getHttpStatus());
        }

        @Test
        @DisplayName("throws ENTITY_NOT_FOUND when the order does not exist")
        void throwsNotFound_whenOrderMissing() {
            UUID anyUser = UUID.randomUUID();
            UUID missing = UUID.randomUUID();
            when(orderRepository.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getOrderByIdForUser(missing, anyUser))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                            e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                    .containsExactly(FoodWiseErrorCode.ENTITY_NOT_FOUND.getMessage(),
                            FoodWiseErrorCode.ENTITY_NOT_FOUND.getHttpStatus());
        }
    }

    /**
     * Pack 1, task 1.2 — server-side price recompute on {@code POST /orders}.
     *
     * <p>Before this pack, {@code OrderItemRequest} carried {@code name} and
     * {@code price} fields the client could set freely, and
     * {@code OrderService.createOrder} summed those values straight into
     * {@code totalPrice} and used them as the Stripe charge amount. A client
     * sending {@code {price: 1}} for a 200-UAH box would be billed 1 kopeck.
     * The fix removes those fields from the wire DTO entirely and resolves
     * both from {@code surprise-box-service}; if that service is unhealthy
     * (circuit breaker fallback returns {@code null}) the order is rejected
     * with {@code 503} rather than created at an unknown price.
     */
    @Nested
    @DisplayName("createOrder — server-side price recompute (Pack 1, task 1.2)")
    class CreateOrderPriceRecompute {

        private CreateOrderRequest cashRequest(UUID storeId, UUID boxId, int quantity) {
            return requestWithPayment(storeId, boxId, quantity, PaymentType.CASH);
        }

        private CreateOrderRequest requestWithPayment(UUID storeId, UUID boxId, int quantity, PaymentType type) {
            return new CreateOrderRequest(
                    storeId,
                    List.of(new OrderItemRequest(boxId, quantity)),
                    type.name(),
                    "PICKUP",
                    null);
        }

        @BeforeEach
        void stubProfileAndStore() {
            // Shared happy-path stubs for the upstream pre-checks. Failure-path
            // tests bail out before reaching save / store-name lookups, so the
            // stubs would otherwise be flagged as unused — mark them lenient.
            org.mockito.Mockito.lenient()
                    .when(profileServiceClient.profileExists(any(UUID.class))).thenReturn(true);
            // Phase 8.2: OrderService now fetches the full store (name + minOrderAmount)
            // via a single getStore call. Default stub: no minimum configured
            // (minOrderAmount = null) so existing happy-path tests are unaffected.
            org.mockito.Mockito.lenient()
                    .when(storeServiceClient.getStore(any(UUID.class)))
                    .thenReturn(new kh.karazin.foodwise.common.dto.internal.InternalStoreDto(
                            UUID.randomUUID(), "Test store", null, null, null, null));
            // Persist-equivalent: assign a UUID the first time the entity is saved
            // so subsequent saved.getId() reads (outbox key, payload) don't NPE.
            org.mockito.Mockito.lenient()
                    .when(orderRepository.save(any(OrderEntity.class)))
                    .thenAnswer(inv -> {
                        OrderEntity e = inv.getArgument(0);
                        if (e.getId() == null) {
                            e.setId(UUID.randomUUID());
                        }
                        return e;
                    });
        }

        @Test
        @DisplayName("totalPrice and item prices come from surprise-box-service, not from the client")
        void recomputesPriceFromSurpriseBoxService() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();

            // Server-authoritative price returned by surprise-box-service as Money (ADR 0012).
            // A 2.50 UAH box is 250 minor units (kopecks).
            Money authoritativeUnitPrice = Money.ofMinor(250L, UAH);
            InternalSurpriseBoxDto box = new InternalSurpriseBoxDto(
                    boxId, "Authoritative title", authoritativeUnitPrice, 10,
                    null, null, null);
            when(surpriseBoxServiceClient.getSurpriseBox(boxId)).thenReturn(box);

            int quantity = 3;
            var response = orderService.createOrder(owner, cashRequest(storeId, boxId, quantity));

            // totalPrice must equal the authoritative unit price × quantity, regardless
            // of what the wire might have carried.
            Money expectedTotal = authoritativeUnitPrice.times(quantity);
            Money expectedItemPrice = authoritativeUnitPrice;
            assertThat(response.order().totalPrice()).isEqualTo(expectedTotal);
            assertThat(response.order().items())
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.price()).isEqualTo(expectedItemPrice);
                        assertThat(item.name()).isEqualTo("Authoritative title");
                    });
        }

        @Test
        @DisplayName("cash orders start in PROCESSING — straight to the kitchen, no payment gate")
        void cashOrderStartsInKitchen() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();
            when(surpriseBoxServiceClient.getSurpriseBox(boxId))
                    .thenReturn(new InternalSurpriseBoxDto(boxId, "Box", Money.ofMinor(100L, UAH), 10, null, null, null));

            var response = orderService.createOrder(owner,
                    requestWithPayment(storeId, boxId, 1, PaymentType.CASH));

            assertThat(response.order().status()).isEqualTo(OrderStatus.PROCESSING.name());
        }

        @Test
        @DisplayName("non-cash orders stay PENDING until payment settles")
        void cardOrderStaysPending() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();
            when(surpriseBoxServiceClient.getSurpriseBox(boxId))
                    .thenReturn(new InternalSurpriseBoxDto(boxId, "Box", Money.ofMinor(100L, UAH), 10, null, null, null));

            var response = orderService.createOrder(owner,
                    requestWithPayment(storeId, boxId, 1, PaymentType.CARD));

            assertThat(response.order().status()).isEqualTo(OrderStatus.PENDING.name());
        }

        @Test
        @DisplayName("rejects with 503 when the circuit breaker fallback returns null for the box")
        void rejectsWith503_whenSurpriseBoxFallback() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();

            // null = circuit breaker fallback fired (downstream unhealthy).
            when(surpriseBoxServiceClient.getSurpriseBox(boxId)).thenReturn(null);

            assertThatThrownBy(() -> orderService.createOrder(owner, cashRequest(storeId, boxId, 2)))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message())
                    .isEqualTo(FoodWiseErrorCode.SERVICE_UNAVAILABLE.getMessage());
        }

        @Test
        @DisplayName("rejects with 503 when the upstream box payload is missing price")
        void rejectsWith503_whenBoxHasMissingPrice() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();

            InternalSurpriseBoxDto incomplete = new InternalSurpriseBoxDto(
                    boxId, "Some title", /* price = */ null, 10, null, null, null);
            when(surpriseBoxServiceClient.getSurpriseBox(boxId)).thenReturn(incomplete);

            assertThatThrownBy(() -> orderService.createOrder(owner, cashRequest(storeId, boxId, 1)))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message())
                    .isEqualTo(FoodWiseErrorCode.SERVICE_UNAVAILABLE.getMessage());
        }

        /**
         * Verifies that for STRIPE orders, {@code createStripeIntent} is called
         * with a {@link Money} object whose {@code amountMinor} equals the
         * server-computed total and whose currency is UAH.
         *
         * <p>This closes the wire mismatch where the old signature passed separate
         * {@code int amountMinorUnits} and {@code String currency} — now currency
         * is folded inside the Money value (ADR 0012).
         */
        @Test
        @DisplayName("STRIPE order: createStripeIntent receives Money total (not int+String)")
        void stripeOrder_callsPaymentClientWithMoneyTotal() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();

            // 2 units × 25000 minor (UAH) → expected total 50000 minor
            Money unitPrice = Money.ofMinor(25000L, UAH);
            int quantity = 2;
            Money expectedTotal = unitPrice.times(quantity);

            when(surpriseBoxServiceClient.getSurpriseBox(boxId))
                    .thenReturn(new InternalSurpriseBoxDto(boxId, "Box S", unitPrice, 5, null, null, null));

            kh.karazin.foodwise.common.dto.internal.InternalPaymentIntentDto fakeIntent =
                    new kh.karazin.foodwise.common.dto.internal.InternalPaymentIntentDto(
                            UUID.randomUUID(), "pi_fake", "pi_fake_secret", "PENDING",
                            expectedTotal);
            when(paymentServiceClient.createStripeIntent(
                    any(UUID.class), any(UUID.class), eq(expectedTotal), any(String.class)))
                    .thenReturn(fakeIntent);

            var response = orderService.createOrder(owner,
                    requestWithPayment(storeId, boxId, quantity, PaymentType.STRIPE));

            // The intent must have been called with the Money total (not a raw int)
            verify(paymentServiceClient).createStripeIntent(
                    any(UUID.class), any(UUID.class), eq(expectedTotal), any(String.class));

            assertThat(response.order().totalPrice()).isEqualTo(expectedTotal);
            assertThat(response.paymentClientSecret()).isEqualTo("pi_fake_secret");
        }
    }

    /**
     * Phase 8.2 — min-order-amount guard.
     *
     * <p>An order whose server-computed total is below the store's
     * {@code minOrderAmount} is invalid regardless of payment type. The guard
     * runs right after {@code totalPrice} is computed and BEFORE any Stripe
     * round-trip, so under-minimum orders are rejected with
     * {@code 422 orderBelowMinimum} without ever calling payment-service.
     */
    @Nested
    @DisplayName("createOrder — min-order-amount guard (Phase 8.2)")
    class CreateOrderMinAmountGuard {

        private CreateOrderRequest request(UUID storeId, UUID boxId, int quantity, PaymentType type) {
            return new CreateOrderRequest(
                    storeId,
                    List.of(new OrderItemRequest(boxId, quantity)),
                    type.name(),
                    "PICKUP",
                    null);
        }

        @BeforeEach
        void stubProfileAndSave() {
            org.mockito.Mockito.lenient()
                    .when(profileServiceClient.profileExists(any(UUID.class))).thenReturn(true);
            org.mockito.Mockito.lenient()
                    .when(orderRepository.save(any(OrderEntity.class)))
                    .thenAnswer(inv -> {
                        OrderEntity e = inv.getArgument(0);
                        if (e.getId() == null) {
                            e.setId(UUID.randomUUID());
                        }
                        return e;
                    });
        }

        private void stubStoreWithMinimum(UUID storeId, Money minOrderAmount) {
            when(storeServiceClient.getStore(storeId)).thenReturn(
                    new kh.karazin.foodwise.common.dto.internal.InternalStoreDto(
                            storeId, "Min store", null, null, null, minOrderAmount));
        }

        @Test
        @DisplayName("total below store.minOrderAmount → ORDER_BELOW_MINIMUM (422), payment NEVER called")
        void rejectsBelowMinimum_andNeverCallsPayment() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();

            // 1 box × 250 minor (2.50 UAH) total; store minimum 100.00 UAH.
            Money unitPrice = Money.ofMinor(250L, UAH);
            stubStoreWithMinimum(storeId, Money.ofMinor(10_000L, UAH));
            when(surpriseBoxServiceClient.getSurpriseBox(boxId))
                    .thenReturn(new InternalSurpriseBoxDto(boxId, "Box", unitPrice, 10, null, null, null));

            assertThatThrownBy(() -> orderService.createOrder(owner,
                    request(storeId, boxId, 1, PaymentType.STRIPE)))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                            e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                    .containsExactly(FoodWiseErrorCode.ORDER_BELOW_MINIMUM.getMessage(),
                            FoodWiseErrorCode.ORDER_BELOW_MINIMUM.getHttpStatus());

            // No Stripe round-trip for an order that is invalid on its face.
            org.mockito.Mockito.verifyNoInteractions(paymentServiceClient);
        }

        @Test
        @DisplayName("guard fires for CASH orders too (under-minimum is invalid regardless of payment type)")
        void rejectsBelowMinimum_forCashOrders() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();

            stubStoreWithMinimum(storeId, Money.ofMinor(10_000L, UAH));
            when(surpriseBoxServiceClient.getSurpriseBox(boxId))
                    .thenReturn(new InternalSurpriseBoxDto(boxId, "Box", Money.ofMinor(250L, UAH), 10, null, null, null));

            assertThatThrownBy(() -> orderService.createOrder(owner,
                    request(storeId, boxId, 1, PaymentType.CASH)))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message())
                    .isEqualTo(FoodWiseErrorCode.ORDER_BELOW_MINIMUM.getMessage());

            org.mockito.Mockito.verifyNoInteractions(paymentServiceClient);
        }

        @Test
        @DisplayName("total exactly at the minimum is accepted (boundary)")
        void acceptsAtExactMinimum() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();

            // total == minimum (10 000 minor) — must NOT be rejected.
            stubStoreWithMinimum(storeId, Money.ofMinor(10_000L, UAH));
            when(surpriseBoxServiceClient.getSurpriseBox(boxId))
                    .thenReturn(new InternalSurpriseBoxDto(boxId, "Box", Money.ofMinor(10_000L, UAH), 10, null, null, null));

            var response = orderService.createOrder(owner, request(storeId, boxId, 1, PaymentType.CASH));

            assertThat(response.order().totalPrice()).isEqualTo(Money.ofMinor(10_000L, UAH));
            org.mockito.Mockito.verifyNoInteractions(paymentServiceClient);
        }

        @Test
        @DisplayName("null store.minOrderAmount → no guard, order proceeds")
        void noGuard_whenMinimumNull() {
            UUID owner = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            UUID boxId = UUID.randomUUID();

            // minOrderAmount = null → guard disabled.
            when(storeServiceClient.getStore(storeId)).thenReturn(
                    new kh.karazin.foodwise.common.dto.internal.InternalStoreDto(
                            storeId, "No-min store", null, null, null, null));
            when(surpriseBoxServiceClient.getSurpriseBox(boxId))
                    .thenReturn(new InternalSurpriseBoxDto(boxId, "Box", Money.ofMinor(50L, UAH), 10, null, null, null));

            var response = orderService.createOrder(owner, request(storeId, boxId, 1, PaymentType.CASH));

            assertThat(response.order().totalPrice()).isEqualTo(Money.ofMinor(50L, UAH));
        }
    }
}
