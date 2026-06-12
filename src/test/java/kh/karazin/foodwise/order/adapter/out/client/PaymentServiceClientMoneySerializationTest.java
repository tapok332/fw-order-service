package kh.karazin.foodwise.order.adapter.out.client;

import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.common.money.MoneyJacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Currency;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies that the order→payment request body serializes {@link Money} using the
 * Money-aware Jackson 3 converter from the auto-configured {@code RestClient.Builder}
 * (registered via {@code MoneyJacksonConfig} in fw-common), NOT the raw record form.
 *
 * <p>Regression test for the CRITICAL bug where a plain {@code RestClient.builder()}
 * factory (without {@code MoneyJacksonModule}) serialized a {@code Money} as
 * {@code {"amountMinor":30000,"currency":{...}}} instead of the wire form
 * {@code {"amount":"300.00","currency":"UAH"}}.
 */
@SpringBootTest(
        classes = PaymentServiceClientMoneySerializationTest.MinimalWebConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "internal.service.secret=test-secret",
        "foodwise.order.currency=UAH",
        "foodwise.order.stripe-currency=uah",
        "foodwise.order.demo-auto-advance=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
                + ",org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"
                + ",org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
                + ",org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
                + ",org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
                + ",org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
                + ",org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
                + ",org.springframework.boot.sql.init.autoconfigure.SqlInitializationAutoConfiguration"
})
class PaymentServiceClientMoneySerializationTest {

    private static final String BASE_URL = "http://payment-service:8087";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MoneyJacksonConfig.class)
    static class MinimalWebConfig {
        // intentionally empty — auto-config provides RestClient.Builder with Money converter
    }

    @Autowired
    private RestClient.Builder autoConfiguredBuilder;

    private MockRestServiceServer mockServer;
    private PaymentServiceClient paymentServiceClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builderForPayment = autoConfiguredBuilder.clone()
                .baseUrl(BASE_URL)
                .defaultHeader("X-Internal-Token", "test-secret");

        mockServer = MockRestServiceServer.bindTo(builderForPayment).build();

        paymentServiceClient = new PaymentServiceClient(
                builderForPayment.build(),
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults());
    }

    @Test
    @DisplayName("Money in createStripeIntent body serializes as wire form {amount,currency}, not raw record {amountMinor,...}")
    void createStripeIntent_serializesMoneyAsWireForm() {
        UUID orderId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID profileId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID paymentId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        Money amount = Money.ofMinor(30_000L, Currency.getInstance("UAH"));

        String responseJson = """
                {
                  "success": true,
                  "data": {
                    "paymentId": "%s",
                    "paymentIntentId": "pi_test_12345",
                    "clientSecret": "pi_test_12345_secret_xyz",
                    "status": "PENDING",
                    "amount": { "amount": "300.00", "currency": "UAH" }
                  }
                }
                """.formatted(paymentId);

        mockServer.expect(requestTo(BASE_URL + "/internal/payments/stripe-intent"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount.amount").value("300.00"))
                .andExpect(jsonPath("$.amount.currency").value("UAH"))
                .andExpect(jsonPath("$.amount.amountMinor").doesNotExist())
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        var result = paymentServiceClient.createStripeIntent(orderId, profileId, amount, "Order " + orderId);

        mockServer.verify();
        assert result != null : "createStripeIntent must return a non-null InternalPaymentIntentDto";
        assert "pi_test_12345".equals(result.paymentIntentId()) : "paymentIntentId must match stub";
        assert "pi_test_12345_secret_xyz".equals(result.clientSecret()) : "clientSecret must match stub";
    }
}
