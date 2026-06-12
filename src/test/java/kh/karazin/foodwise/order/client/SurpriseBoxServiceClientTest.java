package kh.karazin.foodwise.order.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kh.karazin.foodwise.common.dto.internal.InternalSurpriseBoxDto;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.common.money.MoneyJacksonModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SurpriseBoxServiceClientTest {

    private static final String BASE_URL = "http://surprisebox-service:8084";

    private MockRestServiceServer mockServer;
    private SurpriseBoxServiceClient client;

    private static final Currency UAH = Currency.getInstance("UAH");

    @BeforeEach
    void setUp() {
        // Register the Money-aware Jackson 3 module on the converter so the
        // wire form {"amount":"1.50","currency":"UAH"} deserializes into Money
        // (mirrors what the auto-configured RestClient.Builder carries in prod).
        JsonMapper jsonMapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .messageConverters(converters ->
                        converters.add(0, new JacksonJsonHttpMessageConverter(jsonMapper)));
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new SurpriseBoxServiceClient(builder.build(), CircuitBreakerRegistry.ofDefaults());
    }

    @Test
    void getSurpriseBox_returnsTypedDto_whenSuccessfulResponse() {
        UUID boxId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        UUID storeId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        // ADR 0010: internal endpoints return the DTO directly, no envelope.
        String body = """
                {
                  "id": "%s",
                  "title": "Mystery Pastry Box",
                  "price": { "amount": "1.50", "currency": "UAH" },
                  "stock": 4,
                  "imageUrl": "https://example.com/box.jpg",
                  "store": { "storeId": "%s", "name": "Sweet Spot" },
                  "location": { "latitude": 50.0, "longitude": 30.0 }
                }
                """.formatted(boxId, storeId);

        mockServer.expect(requestTo(BASE_URL + "/internal/surprise-boxes/" + boxId))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        InternalSurpriseBoxDto box = client.getSurpriseBox(boxId);

        assertNotNull(box);
        assertEquals(boxId, box.id());
        assertEquals("Mystery Pastry Box", box.title());
        assertEquals(Money.ofMinor(150L, UAH), box.price());
        assertEquals(4, box.stock());
        assertNotNull(box.store());
        assertEquals(storeId, box.store().storeId());
        assertEquals("Sweet Spot", box.store().name());
        mockServer.verify();
    }

    @Test
    void getSurpriseBox_returnsNull_whenBodyIsLiteralNull() {
        // Defensive: contract guarantees a DTO on 200 OK, but a literal-null
        // body should still fall back to the null-sentinel path.
        UUID boxId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        mockServer.expect(requestTo(BASE_URL + "/internal/surprise-boxes/" + boxId))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertNull(client.getSurpriseBox(boxId));
        mockServer.verify();
    }

    /**
     * Upstream-404 mapping (sibling fix to Pack 1).
     *
     * <p>A genuine "no such surprise box" answer must surface as a typed
     * {@code FoodWiseException(ENTITY_NOT_FOUND)} so the caller can return
     * a clean {@code 404} to the API consumer. Pack 1's
     * {@code getSurpriseBox(...) == null} branch in {@code OrderService.createOrder}
     * still applies to the genuine circuit-breaker fallback case (downstream
     * unhealthy) where the call returns {@code null} via the fallback;
     * upstream 404 is the unrelated case of "downstream is fine, the entity
     * just does not exist" and now produces the accurate 404 instead of an
     * opaque 503.
     */
    @Test
    void getSurpriseBox_throwsEntityNotFound_whenUpstreamReturns404() {
        UUID boxId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        mockServer.expect(requestTo(BASE_URL + "/internal/surprise-boxes/" + boxId))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getSurpriseBox(boxId))
                .isInstanceOf(FoodWiseException.class)
                .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                        e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                .containsExactly(FoodWiseErrorCode.ENTITY_NOT_FOUND.getMessage(),
                        FoodWiseErrorCode.ENTITY_NOT_FOUND.getHttpStatus());
        mockServer.verify();
    }

    @Test
    void getSurpriseBox_returnsNull_onUpstream5xx() {
        UUID boxId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        mockServer.expect(requestTo(BASE_URL + "/internal/surprise-boxes/" + boxId))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        // ADR 0006: 5xx = infra failure, degraded to null; OrderService then
        // rejects the order with its own 503 ("Pricing unavailable, please retry").
        assertNull(client.getSurpriseBox(boxId));
        mockServer.verify();
    }
}
