package kh.karazin.foodwise.order.domain;

/**
 * Raised when an order cannot be cancelled in its current status.
 * Translated to the service-level error contract (HTTP 400) by the
 * application layer.
 */
public class OrderNotCancellableException extends RuntimeException {

    public OrderNotCancellableException(String message) {
        super(message);
    }
}
