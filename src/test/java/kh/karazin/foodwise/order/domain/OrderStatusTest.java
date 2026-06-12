package kh.karazin.foodwise.order.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void happyPathChainWalksToCompleted() {
        assertThat(OrderStatus.PENDING.next()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(OrderStatus.PROCESSING.next()).isEqualTo(OrderStatus.READY);
        assertThat(OrderStatus.READY.next()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void terminalStatusesAreReportedTerminalAndDoNotAdvance() {
        assertThat(OrderStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(OrderStatus.COMPLETED.next()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(OrderStatus.CANCELLED.next()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void nonTerminalStatusesAreReportedNonTerminal() {
        assertThat(OrderStatus.PENDING.isTerminal()).isFalse();
        assertThat(OrderStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(OrderStatus.READY.isTerminal()).isFalse();
    }
}
