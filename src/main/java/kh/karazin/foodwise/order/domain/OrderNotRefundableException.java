package kh.karazin.foodwise.order.domain;

/**
 * Raised when an order is not in a refundable state (wrong payment type or
 * payment status). Translated to the service-level error contract (HTTP 400)
 * by the application layer.
 */
public class OrderNotRefundableException extends RuntimeException {

    public OrderNotRefundableException(String message) {
        super(message);
    }
}
