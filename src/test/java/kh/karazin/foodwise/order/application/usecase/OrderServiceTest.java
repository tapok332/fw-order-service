package kh.karazin.foodwise.order.application.usecase;

import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.money.Money;
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
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.OrderPaymentStatus;
import kh.karazin.foodwise.order.domain.OrderStatus;
import kh.karazin.foodwise.order.domain.PaymentType;
import kh.karazin.foodwise.order.domain.ProfileId;
import kh.karazin.foodwise.order.domain.StoreId;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Currency UAH = Currency.getInstance("UAH");

    @Mock private OrderRepository orderRepository;
    @Mock private StoreGateway storeGateway;
    @Mock private ProfileGateway profileGateway;
    @Mock private SurpriseBoxGateway surpriseBoxGateway;
    @Mock private PaymentGateway paymentGateway;
    @Mock private OrderEventPublisher eventPublisher;

    @InjectMocks private OrderService orderService;

    /**
     * Pack 1, task 1.1 — IDOR closure on {@code GET /orders/{orderId}}.
     * The user-facing entry point scopes the lookup to the calling profile and
     * throws FORBIDDEN (not 404) on a cross-user request.
     */
    @Nested
    @DisplayName("getForUser — ownership enforcement (Pack 1, task 1.1)")
    class GetForUser {

        @Test
        @DisplayName("returns the order when the caller owns it")
        void returnsOrder_whenProfileMatches() {
            OrderId orderId = new OrderId(UUID.randomUUID());
            ProfileId owner = new ProfileId(UUID.randomUUID());
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(orderOwnedBy(orderId, owner)));

            Order result = orderService.getForUser(orderId, owner);

            assertThat(result.id()).isEqualTo(orderId);
            assertThat(result.profileId()).isEqualTo(owner);
        }

        @Test
        @DisplayName("throws FORBIDDEN (not 404) when another user requests the order — IDOR closed")
        void throwsForbidden_whenProfileMismatch() {
            OrderId orderId = new OrderId(UUID.randomUUID());
            ProfileId owner = new ProfileId(UUID.randomUUID());
            ProfileId attacker = new ProfileId(UUID.randomUUID());
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(orderOwnedBy(orderId, owner)));

            assertThatThrownBy(() -> orderService.getForUser(orderId, attacker))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                            e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                    .containsExactly(FoodWiseErrorCode.FORBIDDEN.getMessage(),
                            FoodWiseErrorCode.FORBIDDEN.getHttpStatus());
        }

        @Test
        @DisplayName("throws ENTITY_NOT_FOUND when the order does not exist")
        void throwsNotFound_whenOrderMissing() {
            OrderId missing = new OrderId(UUID.randomUUID());
            when(orderRepository.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getForUser(missing, new ProfileId(UUID.randomUUID())))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                            e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                    .containsExactly(FoodWiseErrorCode.ENTITY_NOT_FOUND.getMessage(),
                            FoodWiseErrorCode.ENTITY_NOT_FOUND.getHttpStatus());
        }
    }

    /**
     * Pack 1, task 1.2 — server-side price recompute on {@code POST /orders}.
     * Name and price come from surprise-box-service via the gateway, never the
     * client; an unpriceable box rejects the order with 503.
     */
    @Nested
    @DisplayName("placeOrder — server-side price recompute (Pack 1, task 1.2)")
    class PlaceOrderPriceRecompute {

        @BeforeEach
        void stubProfileStoreAndSave() {
            lenient().when(profileGateway.profileExists(any(ProfileId.class))).thenReturn(true);
            // default store: no minimum configured
            lenient().when(storeGateway.getStore(any(StoreId.class)))
                    .thenReturn(new StoreGateway.StoreSnapshot("Test store", null));
            stubSaveAssignsId();
        }

        @Test
        @DisplayName("total and item prices come from surprise-box-service, not from the client")
        void recomputesPriceFromSurpriseBoxService() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            Money unitPrice = Money.ofMinor(250L, UAH);
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Authoritative title", unitPrice));

            PlaceOrderResult result = orderService.placeOrder(owner, command(boxId, 3, PaymentType.CASH));

            assertThat(result.order().totalPrice()).isEqualTo(unitPrice.times(3));
            assertThat(result.order().items()).singleElement().satisfies(item -> {
                assertThat(item.price()).isEqualTo(unitPrice);
                assertThat(item.name()).isEqualTo("Authoritative title");
            });
        }

        @Test
        @DisplayName("cash orders start in PROCESSING — straight to the kitchen")
        void cashOrderStartsInKitchen() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Box", Money.ofMinor(100L, UAH)));

            PlaceOrderResult result = orderService.placeOrder(owner, command(boxId, 1, PaymentType.CASH));

            assertThat(result.order().status()).isEqualTo(OrderStatus.PROCESSING);
        }

        @Test
        @DisplayName("non-cash orders stay PENDING until payment settles")
        void cardOrderStaysPending() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Box", Money.ofMinor(100L, UAH)));

            PlaceOrderResult result = orderService.placeOrder(owner, command(boxId, 1, PaymentType.CARD));

            assertThat(result.order().status()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("rejects with 503 when the box cannot be priced (gateway returns null)")
        void rejectsWith503_whenBoxUnresolved() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            // null = infra failure OR incomplete payload, collapsed by the gateway
            when(surpriseBoxGateway.resolve(boxId)).thenReturn(null);

            assertThatThrownBy(() -> orderService.placeOrder(owner, command(boxId, 2, PaymentType.CASH)))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message())
                    .isEqualTo(FoodWiseErrorCode.SERVICE_UNAVAILABLE.getMessage());
        }

        @Test
        @DisplayName("STRIPE order: createStripeIntent receives the Money total; clientSecret flows back")
        void stripeOrder_callsPaymentGatewayWithMoneyTotal() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            Money unitPrice = Money.ofMinor(25000L, UAH);
            Money expectedTotal = unitPrice.times(2);
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Box S", unitPrice));
            // STRIPE orders hold stock before charging (ADR 0015).
            when(surpriseBoxGateway.reserve(eq(boxId), any(OrderId.class), eq(owner))).thenReturn(true);
            when(paymentGateway.createStripeIntent(any(OrderId.class), any(ProfileId.class),
                    eq(expectedTotal), any(String.class)))
                    .thenReturn(new PaymentGateway.PaymentIntent("pi_fake", "pi_fake_secret"));

            PlaceOrderResult result = orderService.placeOrder(owner, command(boxId, 2, PaymentType.STRIPE));

            verify(paymentGateway).createStripeIntent(any(OrderId.class), any(ProfileId.class),
                    eq(expectedTotal), any(String.class));
            verify(surpriseBoxGateway).reserve(eq(boxId), any(OrderId.class), eq(owner));
            assertThat(result.order().totalPrice()).isEqualTo(expectedTotal);
            assertThat(result.paymentClientSecret()).isEqualTo("pi_fake_secret");
        }

        @Test
        @DisplayName("STRIPE order: out-of-stock reservation rejects the order before charging")
        void stripeOrder_rejectsWhenReservationOutOfStock() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Box S", Money.ofMinor(25000L, UAH)));
            when(surpriseBoxGateway.reserve(eq(boxId), any(OrderId.class), eq(owner)))
                    .thenThrow(FoodWiseException.errorWithDescription(
                            FoodWiseErrorCode.RESOURCE_UNAVAILABLE, "Surprise box out of stock: " + boxId.value()));

            assertThatThrownBy(() -> orderService.placeOrder(owner, command(boxId, 1, PaymentType.STRIPE)))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message())
                    .isEqualTo(FoodWiseErrorCode.RESOURCE_UNAVAILABLE.getMessage());

            verify(paymentGateway, never()).createStripeIntent(any(), any(), any(), any());
        }
    }

    /**
     * Phase 8.2 — min-order-amount guard. Under-minimum orders are rejected with
     * 422 before any Stripe round-trip, for every payment type.
     */
    @Nested
    @DisplayName("placeOrder — min-order-amount guard (Phase 8.2)")
    class PlaceOrderMinAmountGuard {

        @BeforeEach
        void stubProfileAndSave() {
            lenient().when(profileGateway.profileExists(any(ProfileId.class))).thenReturn(true);
            stubSaveAssignsId();
        }

        @Test
        @DisplayName("total below the store minimum → ORDER_BELOW_MINIMUM (422), payment NEVER called")
        void rejectsBelowMinimum_andNeverCallsPayment() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            when(storeGateway.getStore(any(StoreId.class)))
                    .thenReturn(new StoreGateway.StoreSnapshot("Min store", Money.ofMinor(10_000L, UAH)));
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Box", Money.ofMinor(250L, UAH)));

            assertThatThrownBy(() -> orderService.placeOrder(owner, command(boxId, 1, PaymentType.STRIPE)))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                            e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                    .containsExactly(FoodWiseErrorCode.ORDER_BELOW_MINIMUM.getMessage(),
                            FoodWiseErrorCode.ORDER_BELOW_MINIMUM.getHttpStatus());

            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("guard fires for CASH orders too")
        void rejectsBelowMinimum_forCashOrders() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            when(storeGateway.getStore(any(StoreId.class)))
                    .thenReturn(new StoreGateway.StoreSnapshot("Min store", Money.ofMinor(10_000L, UAH)));
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Box", Money.ofMinor(250L, UAH)));

            assertThatThrownBy(() -> orderService.placeOrder(owner, command(boxId, 1, PaymentType.CASH)))
                    .isInstanceOf(FoodWiseException.class)
                    .extracting(e -> ((FoodWiseException) e).getErrorDetails().message())
                    .isEqualTo(FoodWiseErrorCode.ORDER_BELOW_MINIMUM.getMessage());

            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("total exactly at the minimum is accepted (boundary)")
        void acceptsAtExactMinimum() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            when(storeGateway.getStore(any(StoreId.class)))
                    .thenReturn(new StoreGateway.StoreSnapshot("Min store", Money.ofMinor(10_000L, UAH)));
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Box", Money.ofMinor(10_000L, UAH)));

            PlaceOrderResult result = orderService.placeOrder(owner, command(boxId, 1, PaymentType.CASH));

            assertThat(result.order().totalPrice()).isEqualTo(Money.ofMinor(10_000L, UAH));
            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("null store minimum → no guard, order proceeds")
        void noGuard_whenMinimumNull() {
            ProfileId owner = new ProfileId(UUID.randomUUID());
            SurpriseBoxId boxId = new SurpriseBoxId(UUID.randomUUID());
            when(storeGateway.getStore(any(StoreId.class)))
                    .thenReturn(new StoreGateway.StoreSnapshot("No-min store", null));
            when(surpriseBoxGateway.resolve(boxId))
                    .thenReturn(new SurpriseBoxGateway.ResolvedBox("Box", Money.ofMinor(50L, UAH)));

            PlaceOrderResult result = orderService.placeOrder(owner, command(boxId, 1, PaymentType.CASH));

            assertThat(result.order().totalPrice()).isEqualTo(Money.ofMinor(50L, UAH));
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void stubSaveAssignsId() {
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            return o.id() == null ? withId(o) : o;
        });
    }

    private static PlaceOrderCommand command(SurpriseBoxId boxId, int quantity, PaymentType paymentType) {
        return new PlaceOrderCommand(
                new StoreId(UUID.randomUUID()),
                List.of(new PlaceOrderCommand.Line(boxId, quantity)),
                paymentType.name(),
                "PICKUP",
                null);
    }

    private static Order orderOwnedBy(OrderId id, ProfileId owner) {
        return Order.restore(id, owner, new StoreId(UUID.randomUUID()), "Test store",
                OrderStatus.PENDING, PaymentType.STRIPE, OrderPaymentStatus.PENDING, DeliveryType.PICKUP,
                null, null, List.of(), Money.ofMinor(10_000L, UAH), null, null, null, null, null, null);
    }

    /** Mirrors the input aggregate with a persistence-assigned id (mock save). */
    private static Order withId(Order o) {
        return Order.restore(new OrderId(UUID.randomUUID()), o.profileId(), o.storeId(), o.storeName(),
                o.status(), o.paymentType(), o.paymentStatus(), o.deliveryType(), o.deliveryAddress(),
                o.pickupCode(), o.items(), o.totalPrice(), o.stripePaymentIntentId(), o.paidAt(),
                o.failureCode(), o.failureMessage(), o.createdAt(), o.updatedAt());
    }
}
