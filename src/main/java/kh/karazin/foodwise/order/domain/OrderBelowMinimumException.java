package kh.karazin.foodwise.order.domain;

/**
 * Raised when an order total is below the store's configured minimum, or the
 * total and minimum are expressed in different currencies (not comparable).
 * Translated to the service-level error contract (HTTP 422) by the
 * application layer.
 */
public class OrderBelowMinimumException extends RuntimeException {

    public OrderBelowMinimumException(String message) {
        super(message);
    }
}
