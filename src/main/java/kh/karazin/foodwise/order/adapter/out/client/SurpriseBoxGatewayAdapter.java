package kh.karazin.foodwise.order.adapter.out.client;

import kh.karazin.foodwise.common.dto.internal.InternalSurpriseBoxDto;
import kh.karazin.foodwise.order.application.port.out.SurpriseBoxGateway;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing the {@link SurpriseBoxGateway} outbound port on top of
 * the circuit-breaker-protected {@link SurpriseBoxServiceClient}.
 *
 * <p>An incomplete payload (missing price/title on an otherwise-200 response)
 * is collapsed to {@code null} here — the same sentinel the client returns for
 * an infrastructure failure — so the placement use case rejects the order
 * uniformly with "Pricing unavailable, please retry", preserving the exact
 * pre-refactor wire response for both cases. A genuine upstream 404 still
 * propagates as the client's typed {@code ENTITY_NOT_FOUND}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SurpriseBoxGatewayAdapter implements SurpriseBoxGateway {

    private final SurpriseBoxServiceClient surpriseBoxServiceClient;

    @Override
    public ResolvedBox resolve(SurpriseBoxId boxId) {
        InternalSurpriseBoxDto box = surpriseBoxServiceClient.getSurpriseBox(boxId.value());
        if (box == null) {
            return null;
        }
        if (box.price() == null || box.title() == null) {
            log.warn("Surprise-box {} payload incomplete (price/title missing)", boxId.value());
            return null;
        }
        return new ResolvedBox(box.title(), box.price());
    }
}
