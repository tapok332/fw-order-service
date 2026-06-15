package kh.karazin.foodwise.order.domain;

import kh.karazin.foodwise.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link Order} aggregate: total computation, minimum-amount
 * enforcement, ownership, the status lifecycle and the idempotent saga
 * transitions — all the invariants that moved into the domain during the
 * hexagonal refactor. Pure, no Spring, no mocks.
 */
class OrderTest {

    private static final Currency UAH = Currency.getInstance("UAH");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Nested
    @DisplayName("place")
    class Place {

        @Test
        @DisplayName("computes the total from server-resolved line prices and records the opening status")
        void place_computesTotalAndRecordsHistory() {
            // 2 × 250 + 1 × 300 = 800 minor (UAH)
            Order order = Order.place(draft(PaymentType.CASH, null,
                    line("Box A", Money.ofMinor(250L, UAH), 2),
                    line("Box B", Money.ofMinor(300L, UAH), 1)));

            assertThat(order.totalPrice()).isEqualTo(Money.ofMinor(800L, UAH));
            assertThat(order.items()).hasSize(2);
            // cash → straight to the kitchen
            assertThat(order.status()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(order.paymentStatus()).isEqualTo(OrderPaymentStatus.PENDING);
            assertThat(order.drainPendingStatusHistory()).containsExactly(OrderStatus.PROCESSING);
        }

        @Test
        @DisplayName("non-cash orders open in PENDING until payment settles")
        void place_nonCashStartsPending() {
            Order order = Order.place(draft(PaymentType.STRIPE, null,
                    line("Box", Money.ofMinor(100L, UAH), 1)));

            assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.drainPendingStatusHistory()).containsExactly(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("rejects a total below the store minimum")
        void place_rejectsBelowMinimum() {
            // total 250, minimum 10 000
            assertThatThrownBy(() -> Order.place(draft(PaymentType.CASH, Money.ofMinor(10_000L, UAH),
                    line("Box", Money.ofMinor(250L, UAH), 1))))
                    .isInstanceOf(OrderBelowMinimumException.class);
        }

        @Test
        @DisplayName("accepts a total exactly at the minimum (boundary)")
        void place_acceptsAtExactMinimum() {
            Order order = Order.place(draft(PaymentType.CASH, Money.ofMinor(10_000L, UAH),
                    line("Box", Money.ofMinor(10_000L, UAH), 1)));

            assertThat(order.totalPrice()).isEqualTo(Money.ofMinor(10_000L, UAH));
        }

        @Test
        @DisplayName("rejects when total and store minimum are in different currencies")
        void place_rejectsCurrencyMismatch() {
            assertThatThrownBy(() -> Order.place(draft(PaymentType.CASH, Money.ofMinor(100L, JPY),
                    line("Box", Money.ofMinor(10_000L, UAH), 1))))
                    .isInstanceOf(OrderBelowMinimumException.class)
                    .hasMessageContaining("does not match store minimum currency");
        }

        @Test
        @DisplayName("a null store minimum disables the guard")
        void place_noGuardWhenMinimumNull() {
            Order order = Order.place(draft(PaymentType.CASH, null,
                    line("Box", Money.ofMinor(50L, UAH), 1)));

            assertThat(order.totalPrice()).isEqualTo(Money.ofMinor(50L, UAH));
        }
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("assertVisibleTo throws for a non-owner")
        void assertVisibleTo_throwsForNonOwner() {
            Order order = pendingOrder();
            assertThatThrownBy(() -> order.assertVisibleTo(new ProfileId(UUID.randomUUID())))
                    .isInstanceOf(OrderAccessDeniedException.class)
                    .hasMessageContaining("view");
        }

        @Test
        @DisplayName("cancelBy a non-owner throws OrderAccessDeniedException")
        void cancelBy_throwsForNonOwner() {
            Order order = pendingOrder();
            assertThatThrownBy(() -> order.cancelBy(new ProfileId(UUID.randomUUID())))
                    .isInstanceOf(OrderAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("status lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("cancelBy the owner moves a PENDING order to CANCELLED and records history")
        void cancelBy_owner_cancelsPending() {
            Order order = pendingOrder();
            ProfileId owner = order.profileId();
            order.drainPendingStatusHistory();

            order.cancelBy(owner);

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.drainPendingStatusHistory()).containsExactly(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancelBy a non-PENDING order throws OrderNotCancellableException")
        void cancelBy_nonPending_throws() {
            Order order = pendingOrder();
            ProfileId owner = order.profileId();
            order.changeStatus(OrderStatus.PROCESSING);

            assertThatThrownBy(() -> order.cancelBy(owner))
                    .isInstanceOf(OrderNotCancellableException.class)
                    .hasMessageContaining("PROCESSING");
        }

        @Test
        @DisplayName("changeStatus to READY mints a pickup code once")
        void changeStatus_ready_generatesPickupCode() {
            Order order = pendingOrder();

            order.changeStatus(OrderStatus.READY);
            String code = order.pickupCode();

            assertThat(code).isNotNull().matches("\\d{6}");
            // re-entering READY does not regenerate the code
            order.changeStatus(OrderStatus.READY);
            assertThat(order.pickupCode()).isEqualTo(code);
        }
    }

    @Nested
    @DisplayName("saga transitions (idempotent)")
    class Saga {

        @Test
        @DisplayName("markPaid moves PENDING → PROCESSING/PAID once; redelivery is a no-op")
        void markPaid_isIdempotent() {
            Order order = pendingOrder();
            order.drainPendingStatusHistory();

            assertThat(order.markPaid()).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(order.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
            assertThat(order.paidAt()).isNotNull();
            assertThat(order.drainPendingStatusHistory()).containsExactly(OrderStatus.PROCESSING);

            // redelivery
            assertThat(order.markPaid()).isFalse();
            assertThat(order.drainPendingStatusHistory()).isEmpty();
        }

        @Test
        @DisplayName("markPaymentFailed cancels the order and truncates an over-long reason")
        void markPaymentFailed_cancelsAndTruncates() {
            Order order = pendingOrder();
            String longReason = "x".repeat(1500);

            assertThat(order.markPaymentFailed(longReason)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.paymentStatus()).isEqualTo(OrderPaymentStatus.FAILED);
            assertThat(order.failureMessage()).hasSize(1000);

            // redelivery is a no-op
            assertThat(order.markPaymentFailed("again")).isFalse();
        }

        @Test
        @DisplayName("expireReservation cancels a live order but is a no-op for terminal ones")
        void expireReservation_idempotentOnTerminal() {
            Order order = pendingOrder();
            assertThat(order.expireReservation()).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.expireReservation()).isFalse();
        }

        @Test
        @DisplayName("expireReservation never cancels an already-PAID order (stale expiry, ADR 0015)")
        void expireReservation_isNoOpForPaidOrder() {
            Order order = pendingOrder();
            order.markPaid();

            assertThat(order.expireReservation()).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(order.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
        }
    }

    @Nested
    @DisplayName("refundability")
    class Refundability {

        @Test
        @DisplayName("a non-Stripe order is not refundable")
        void assertRefundable_rejectsNonStripe() {
            Order order = Order.place(draft(PaymentType.CASH, null,
                    line("Box", Money.ofMinor(10_000L, UAH), 1)));
            assertThatThrownBy(order::assertRefundable)
                    .isInstanceOf(OrderNotRefundableException.class)
                    .hasMessageContaining("Stripe");
        }

        @Test
        @DisplayName("a Stripe order that is not PAID is not refundable")
        void assertRefundable_rejectsUnpaidStripe() {
            Order order = Order.place(draft(PaymentType.STRIPE, null,
                    line("Box", Money.ofMinor(10_000L, UAH), 1)));
            assertThatThrownBy(order::assertRefundable)
                    .isInstanceOf(OrderNotRefundableException.class)
                    .hasMessageContaining("PAID");
        }

        @Test
        @DisplayName("a paid Stripe order is refundable")
        void assertRefundable_allowsPaidStripe() {
            Order order = Order.place(draft(PaymentType.STRIPE, null,
                    line("Box", Money.ofMinor(10_000L, UAH), 1)));
            order.markPaid();
            order.assertRefundable(); // does not throw
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static ResolvedOrderLine line(String name, Money price, int quantity) {
        return new ResolvedOrderLine(new SurpriseBoxId(UUID.randomUUID()), name, price, quantity);
    }

    private static OrderDraft draft(PaymentType paymentType, Money minimum, ResolvedOrderLine... lines) {
        return new OrderDraft(
                new ProfileId(UUID.randomUUID()),
                new StoreId(UUID.randomUUID()),
                "Test store",
                paymentType,
                DeliveryType.PICKUP,
                null,
                List.of(lines),
                minimum);
    }

    private static Order pendingOrder() {
        // CASH places straight into PROCESSING; force a clean PENDING order for
        // lifecycle tests by using a non-cash payment type.
        return Order.place(draft(PaymentType.STRIPE, null,
                line("Box", Money.ofMinor(10_000L, UAH), 1)));
    }
}
