package kh.karazin.foodwise.order.application.port.in;

import kh.karazin.foodwise.order.domain.StoreId;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;

import java.util.List;

/**
 * Inbound command for placing an order. {@code paymentType} and
 * {@code deliveryType} are still raw wire strings here — the use case parses
 * them into domain enums, preserving the exact validation behaviour of the
 * original controller/service (unsupported payment type → INVALID_REQUEST).
 *
 * <p>Note: no price or name fields — those are resolved server-side from
 * surprise-box-service (ADR 0005).
 */
public record PlaceOrderCommand(
        StoreId storeId,
        List<Line> items,
        String paymentType,
        String deliveryType,
        String deliveryAddress
) {

    public record Line(SurpriseBoxId boxId, int quantity) {
    }
}
