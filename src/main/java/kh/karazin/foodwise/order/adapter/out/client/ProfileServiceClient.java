package kh.karazin.foodwise.order.adapter.out.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kh.karazin.foodwise.common.dto.internal.InternalProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Client for {@code profile-service} via REST.
 *
 * <p>Note: profile-service's internal endpoint returns a bare
 * {@link InternalProfileDto}, not an {@code ApiResponse} envelope.
 *
 * <p>Programmatic Resilience4j (see {@link StoreServiceClient} for rationale —
 * ADR 0006). Semantics for {@code profileExists}: a {@code 404} from upstream
 * is a legitimate "no such profile" answer ({@code false}); breaker-open or
 * upstream 5xx degrade pessimistically to {@code false} too, so a downstream
 * outage causes the caller to reject the order rather than silently accept it
 * for a possibly-non-existent user.
 */
@Slf4j
@Component
class ProfileServiceClient {

    private static final String BREAKER_NAME = "profileService";

    private final RestClient profileRestClient;
    private final CircuitBreaker circuitBreaker;

    ProfileServiceClient(RestClient profileRestClient,
                         CircuitBreakerRegistry circuitBreakerRegistry) {
        this.profileRestClient = profileRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(BREAKER_NAME);
    }

    boolean profileExists(UUID userId) {
        try {
            InternalProfileDto profile = circuitBreaker.executeSupplier(() ->
                    profileRestClient.get()
                            .uri("/internal/profiles/{userId}", userId)
                            .retrieve()
                            .body(InternalProfileDto.class)
            );
            return profile != null;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (HttpClientErrorException e) {
            log.warn("profile-service rejected profileExists({}): {}", userId, e.getStatusCode());
            return false;
        } catch (CallNotPermittedException e) {
            log.warn("profile-service circuit breaker open for {}: {}", userId, e.getMessage());
            return false;
        } catch (RestClientException e) {
            log.warn("Profile service unavailable, rejecting order for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
