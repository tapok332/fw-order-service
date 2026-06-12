package kh.karazin.foodwise.order.adapter.out.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProfileServiceClientTest {

    private static final String BASE_URL = "http://profile-service:8082";

    private MockRestServiceServer mockServer;
    private ProfileServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new ProfileServiceClient(builder.build(), CircuitBreakerRegistry.ofDefaults());
    }

    @Test
    void profileExists_returnsFalse_whenUpstreamReturns404() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        mockServer.expect(requestTo(BASE_URL + "/internal/profiles/" + userId))
                .andRespond(withResourceNotFound());

        assertFalse(client.profileExists(userId));
        mockServer.verify();
    }

    @Test
    void profileExists_returnsFalse_onUpstream5xx() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        mockServer.expect(requestTo(BASE_URL + "/internal/profiles/" + userId))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertFalse(client.profileExists(userId));
        mockServer.verify();
    }

    @Test
    void profileExists_returnsTrue_whenProfileFound() {
        UUID userId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        String body = """
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "userId": "%s",
                  "name": "Alice",
                  "email": "alice@example.com"
                }
                """.formatted(userId);

        mockServer.expect(requestTo(BASE_URL + "/internal/profiles/" + userId))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertTrue(client.profileExists(userId));
        mockServer.verify();
    }
}
