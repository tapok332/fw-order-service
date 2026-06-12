package kh.karazin.foodwise.order.domain;

/**
 * Raised when a caller tries to view or act on an order they do not own.
 * Translated to the service-level error contract (HTTP 403) by the
 * application layer.
 */
public class OrderAccessDeniedException extends RuntimeException {

    public OrderAccessDeniedException(String message) {
        super(message);
    }
}
