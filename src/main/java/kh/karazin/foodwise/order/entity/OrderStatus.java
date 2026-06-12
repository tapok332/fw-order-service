package kh.karazin.foodwise.order.entity;

/**
 * Possible statuses for an order lifecycle.
 */
public enum OrderStatus {
    PENDING,
    PROCESSING,
    READY,
    COMPLETED,
    CANCELLED;

    /**
     * Terminal statuses have no further automatic transitions.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    /**
     * Next status in the happy-path lifecycle, or {@code this} for terminal
     * statuses. CANCELLED is never reached via this chain — it is set explicitly
     * on user cancellation or saga compensation.
     */
    public OrderStatus next() {
        return switch (this) {
            case PENDING -> PROCESSING;
            case PROCESSING -> READY;
            case READY -> COMPLETED;
            case COMPLETED, CANCELLED -> this;
        };
    }
}
