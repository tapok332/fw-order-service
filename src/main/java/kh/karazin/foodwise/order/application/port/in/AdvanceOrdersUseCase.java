package kh.karazin.foodwise.order.application.port.in;

/**
 * Inbound port for the mock-fulfillment scheduler: walk every in-kitchen order
 * one status step forward.
 */
public interface AdvanceOrdersUseCase {

    /**
     * Advances every order currently PROCESSING or READY one step
     * (PROCESSING → READY → COMPLETED). Per-order isolation: a single failing
     * order is logged and skipped, never blocking the rest of the batch.
     */
    void advanceInKitchenOrders();
}
