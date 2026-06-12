package kh.karazin.foodwise.order.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kh.karazin.foodwise.common.dto.internal.InternalStoreDto;
import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.common.money.MoneyJacksonModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests verifying typed deserialization of {@code /internal/stores/{id}}
 * responses into {@link InternalStoreDto}.
 */
class StoreServiceClientTest {

    private static final String BASE_URL = "http://store-service:8083";

    private MockRestServiceServer mockServer;
    private StoreServiceClient client;

    private static final Currency UAH = Currency.getInstance("UAH");

    @BeforeEach
    void setUp() {
        // Register the Money-aware Jackson 3 module on the converter so the
        // wire form {"amount":"25.00","currency":"UAH"} deserializes into Money
        // (mirrors what the auto-configured RestClient.Builder carries in prod).
        JsonMapper jsonMapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .messageConverters(converters ->
                        converters.add(0, new JacksonJsonHttpMessageConverter(jsonMapper)));
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new StoreServiceClient(builder.build(), CircuitBreakerRegistry.ofDefaults());
    }

    @Test
    void getStore_returnsTypedDto_whenSuccessfulResponse() {
        UUID storeId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        // ADR 0010: internal endpoints return the DTO directly, no ApiResponse envelope.
        String body = """
                {
                  "id": "%s",
                  "name": "Eco Bakery",
                  "imageUrl": "https://example.com/eb.jpg",
                  "location": { "latitude": 50.45, "longitude": 30.52 },
                  "deliveryFee": { "amount": "25.00", "currency": "UAH" },
                  "minOrderAmount": { "amount": "100.00", "currency": "UAH" }
                }
                """.formatted(storeId);

        mockServer.expect(requestTo(BASE_URL + "/internal/stores/" + storeId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        InternalStoreDto store = client.getStore(storeId);

        assertNotNull(store);
        assertEquals(storeId, store.id());
        assertEquals("Eco Bakery", store.name());
        assertNotNull(store.location());
        assertEquals(50.45, store.location().latitude());
        assertEquals(30.52, store.location().longitude());
        assertEquals(Money.ofMinor(2500L, UAH), store.deliveryFee());
        assertEquals(Money.ofMinor(10000L, UAH), store.minOrderAmount());
        mockServer.verify();
    }

    @Test
    void getStoreName_returnsName_whenStoreFound() {
        UUID storeId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        // ADR 0010: direct DTO, no envelope.
        String body = """
                { "id": "%s", "name": "Veggie Hub" }
                """.formatted(storeId);

        mockServer.expect(requestTo(BASE_URL + "/internal/stores/" + storeId))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertEquals("Veggie Hub", client.getStoreName(storeId));
        mockServer.verify();
    }

    @Test
    void getStoreName_returnsNull_whenBodyIsLiteralNull() {
        // Defensive: contract guarantees a non-null DTO on 200 OK, but if the
        // producer ever sent `null` as the body, the client should fall back
        // to the null-sentinel path (caller treats as infra glitch).
        UUID storeId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        mockServer.expect(requestTo(BASE_URL + "/internal/stores/" + storeId))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertNull(client.getStoreName(storeId));
        mockServer.verify();
    }

    /**
     * Upstream-404 mapping (sibling fix to Pack 1).
     *
     * <p>Before: a {@code 404} from {@code GET /internal/stores/{id}} surfaced
     * as a raw {@code HttpClientErrorException.NotFound} which
     * {@code GlobalExceptionHandler}'s catch-all mapped to {@code 500
     * unknownError}. After: a {@code FoodWiseException(ENTITY_NOT_FOUND)} is
     * thrown so the caller propagates an accurate {@code 404 entityNotFound}
     * to the API consumer. The 4xx is also intentionally NOT counted as a
     * circuit-breaker failure — it is a legitimate domain answer, not an
     * outage signal.
     */
    @Test
    void getStore_throwsEntityNotFound_whenUpstreamReturns404() {
        UUID storeId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        mockServer.expect(requestTo(BASE_URL + "/internal/stores/" + storeId))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getStore(storeId))
                .isInstanceOf(FoodWiseException.class)
                .extracting(e -> ((FoodWiseException) e).getErrorDetails().message(),
                        e -> ((FoodWiseException) e).getErrorDetails().httpStatus())
                .containsExactly(FoodWiseErrorCode.ENTITY_NOT_FOUND.getMessage(),
                        FoodWiseErrorCode.ENTITY_NOT_FOUND.getHttpStatus());
        mockServer.verify();
    }

    @Test
    void getStoreName_throwsEntityNotFound_whenUpstreamReturns404() {
        UUID storeId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        mockServer.expect(requestTo(BASE_URL + "/internal/stores/" + storeId))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getStoreName(storeId))
                .isInstanceOf(FoodWiseException.class)
                .extracting(e -> ((FoodWiseException) e).getErrorDetails().message())
                .isEqualTo(FoodWiseErrorCode.ENTITY_NOT_FOUND.getMessage());
        mockServer.verify();
    }

    @Test
    void getStore_throwsServiceUnavailable_onOther4xx() {
        UUID storeId = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

        mockServer.expect(requestTo(BASE_URL + "/internal/stores/" + storeId))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.getStore(storeId))
                .isInstanceOf(FoodWiseException.class)
                .extracting(e -> ((FoodWiseException) e).getErrorDetails().message())
                .isEqualTo(FoodWiseErrorCode.SERVICE_UNAVAILABLE.getMessage());
        mockServer.verify();
    }

    /**
     * Programmatic Resilience4j (ADR 0006): upstream {@code 5xx} is an infra
     * failure — the client returns {@code null} so the caller can decide to
     * reject with its own 503. Tests both that the exception flows correctly
     * through {@code executeSupplier} and that the outer catch downgrades it
     * to the {@code null} sentinel.
     */
    @Test
    void getStore_returnsNull_onUpstream5xx() {
        UUID storeId = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

        mockServer.expect(requestTo(BASE_URL + "/internal/stores/" + storeId))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertNull(client.getStore(storeId));
        mockServer.verify();
    }
}
