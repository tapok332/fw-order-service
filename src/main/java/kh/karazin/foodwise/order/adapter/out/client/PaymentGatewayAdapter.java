package kh.karazin.foodwise.order.adapter.out.client;

import kh.karazin.foodwise.common.dto.internal.InternalPaymentIntentDto;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.application.port.out.PaymentGateway;
import kh.karazin.foodwise.order.domain.OrderId;
import kh.karazin.foodwise.order.domain.ProfileId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing the {@link PaymentGateway} outbound port on top of the
 * circuit-breaker-protected {@link PaymentServiceClient}. Translates between
 * domain ids and the client's UUID/DTO surface; the client owns all
 * error classification (ADR 0006).
 */
@Component
@RequiredArgsConstructor
class PaymentGatewayAdapter implements PaymentGateway {

    private final PaymentServiceClient paymentServiceClient;

    @Override
    public PaymentIntent createStripeIntent(OrderId orderId, ProfileId profileId, Money amount, String description) {
        InternalPaymentIntentDto intent = paymentServiceClient.createStripeIntent(
                orderId.value(), profileId.value(), amount, description);
        return new PaymentIntent(intent.paymentIntentId(), intent.clientSecret());
    }

    @Override
    public void refundByOrder(OrderId orderId, Integer amount, String reason) {
        paymentServiceClient.refundByOrder(orderId.value(), amount, reason);
    }
}
