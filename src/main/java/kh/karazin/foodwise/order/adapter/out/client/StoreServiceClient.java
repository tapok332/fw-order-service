package kh.karazin.foodwise.order.adapter.out.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kh.karazin.foodwise.common.dto.internal.InternalStoreDto;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Client for {@code store-service} via REST.
 *
 * <p>Uses Resilience4j's programmatic API ({@code executeSupplier}) instead of
 * the {@code @CircuitBreaker} annotation so the exception-to-domain mapping
 * lives in one linear block of code, with no fallback-method semantic
 * inversion. Branches:
 * <ul>
 *     <li>{@code 404} from upstream → typed {@link FoodWiseException}
 *         {@code ENTITY_NOT_FOUND} so the caller surfaces a clean {@code 404}
 *         to the API consumer.</li>
 *     <li>Other {@code 4xx} → typed {@link FoodWiseException}
 *         {@code SERVICE_UNAVAILABLE} (contract drift, not an outage).</li>
 *     <li>{@link CallNotPermittedException} (breaker OPEN) or any other
 *         {@link RestClientException} (5xx / network) → degraded {@code null}
 *         so the caller rejects the order with its own 503, per Pack 1 ADR 0005.</li>
 * </ul>
 *
 * <p>{@code 4xx} responses are never counted as breaker failures because
 * {@code HttpClientErrorException} is caught here, outside the supplier, before
 * Resilience4j sees the exception. See ADR 0006.
 */
@Slf4j
@Component
class StoreServiceClient {

    private static final String BREAKER_NAME = "storeService";

    private final RestClient storeRestClient;
    private final CircuitBreaker circuitBreaker;

    StoreServiceClient(RestClient storeRestClient,
                       CircuitBreakerRegistry circuitBreakerRegistry) {
        this.storeRestClient = storeRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(BREAKER_NAME);
    }

    InternalStoreDto getStore(UUID storeId) {
        try {
            return circuitBreaker.executeSupplier(() ->
                    storeRestClient.get()
                            .uri("/internal/stores/{storeId}", storeId)
                            .retrieve()
                            .body(InternalStoreDto.class)
            );
        } catch (HttpClientErrorException.NotFound e) {
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.ENTITY_NOT_FOUND, "Store not found: " + storeId);
        } catch (HttpClientErrorException e) {
            log.warn("store-service rejected getStore({}): status={} body={}",
                    storeId, e.getStatusCode(), e.getResponseBodyAsString());
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                    "store-service rejected request: " + e.getStatusCode());
        } catch (CallNotPermittedException e) {
            log.warn("store-service circuit breaker open for {}: {}", storeId, e.getMessage());
            return null;
        } catch (RestClientException e) {
            log.warn("store-service upstream failure for {}: {}", storeId, e.getMessage());
            return null;
        }
    }

    /**
     * Convenience helper preserving the previous {@code getStoreName} contract:
     * returns {@code null} when the store cannot be fetched for infra reasons,
     * propagates {@link FoodWiseException} for domain answers (404 / malformed).
     */
    String getStoreName(UUID storeId) {
        InternalStoreDto store = getStore(storeId);
        return store != null ? store.name() : null;
    }
}
