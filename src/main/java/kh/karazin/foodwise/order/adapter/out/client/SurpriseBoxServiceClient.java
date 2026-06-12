package kh.karazin.foodwise.order.adapter.out.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kh.karazin.foodwise.common.dto.internal.InternalSurpriseBoxDto;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Client for {@code surprise-box-service} via REST.
 *
 * <p>Same shape as {@link StoreServiceClient}: programmatic Resilience4j with
 * outer try/catch separating domain answers (4xx → typed
 * {@link FoodWiseException}) from infra failures (breaker OPEN / 5xx / network
 * → {@code null}). A {@code null} return drives the "Pricing unavailable, please
 * retry" 503 in the placement use case; legitimate 404s surface as a clean 404.
 *
 * <p>See ADR 0006.
 */
@Slf4j
@Component
class SurpriseBoxServiceClient {

    private static final String BREAKER_NAME = "surpriseboxService";

    private final RestClient surpriseBoxRestClient;
    private final CircuitBreaker circuitBreaker;

    SurpriseBoxServiceClient(RestClient surpriseBoxRestClient,
                             CircuitBreakerRegistry circuitBreakerRegistry) {
        this.surpriseBoxRestClient = surpriseBoxRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(BREAKER_NAME);
    }

    InternalSurpriseBoxDto getSurpriseBox(UUID boxId) {
        try {
            return circuitBreaker.executeSupplier(() ->
                    surpriseBoxRestClient.get()
                            .uri("/internal/surprise-boxes/{boxId}", boxId)
                            .retrieve()
                            .body(InternalSurpriseBoxDto.class)
            );
        } catch (HttpClientErrorException.NotFound e) {
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.ENTITY_NOT_FOUND, "Surprise box not found: " + boxId);
        } catch (HttpClientErrorException e) {
            log.warn("surprise-box-service rejected getSurpriseBox({}): status={} body={}",
                    boxId, e.getStatusCode(), e.getResponseBodyAsString());
            throw FoodWiseException.errorWithDescription(
                    FoodWiseErrorCode.SERVICE_UNAVAILABLE,
                    "surprise-box-service rejected request: " + e.getStatusCode());
        } catch (CallNotPermittedException e) {
            log.warn("surprise-box circuit breaker open for {}: {}", boxId, e.getMessage());
            return null;
        } catch (RestClientException e) {
            log.warn("surprise-box upstream failure for {}: {}", boxId, e.getMessage());
            return null;
        }
    }
}
