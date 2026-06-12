package kh.karazin.foodwise.order.adapter.out.client;

import kh.karazin.foodwise.common.dto.internal.InternalStoreDto;
import kh.karazin.foodwise.order.application.port.out.StoreGateway;
import kh.karazin.foodwise.order.domain.StoreId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing the {@link StoreGateway} outbound port on top of the
 * circuit-breaker-protected {@link StoreServiceClient}. Maps the internal DTO
 * to the small {@link StoreSnapshot} the placement use case needs; a {@code null}
 * from the client (infra failure) propagates as {@code null}.
 */
@Component
@RequiredArgsConstructor
class StoreGatewayAdapter implements StoreGateway {

    private final StoreServiceClient storeServiceClient;

    @Override
    public StoreSnapshot getStore(StoreId storeId) {
        InternalStoreDto store = storeServiceClient.getStore(storeId.value());
        if (store == null) {
            return null;
        }
        return new StoreSnapshot(store.name(), store.minOrderAmount());
    }
}
