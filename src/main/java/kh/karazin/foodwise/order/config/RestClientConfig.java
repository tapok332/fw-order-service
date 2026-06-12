package kh.karazin.foodwise.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient configuration for inter-service communication.
 *
 * <p>Each bean clones the Spring Boot auto-configured {@code RestClient.Builder}
 * injected by the framework. That builder carries the Money-aware Jackson 3
 * converter (registered via {@code MoneyJacksonConfig} in fw-common and picked
 * up by Spring Boot auto-configuration), so all four downstream clients
 * serialize {@link kh.karazin.foodwise.common.money.Money} as
 * {@code {"amount":"300.00","currency":"UAH"}} on the wire rather than the raw
 * record form. Using the static {@code RestClient.builder()} factory would
 * produce a plain builder with default converters that do NOT include the Money
 * module — never use it here.
 */
@Configuration
public class RestClientConfig {

    private final RestClient.Builder autoConfiguredBuilder;

    @Value("${services.store-service.url:http://store-service:8083}")
    private String storeServiceUrl;

    @Value("${services.profile-service.url:http://profile-service:8082}")
    private String profileServiceUrl;

    @Value("${services.surprisebox-service.url:http://surprisebox-service:8084}")
    private String surpriseboxServiceUrl;

    @Value("${services.payment-service.url:http://payment-service:8087}")
    private String paymentServiceUrl;

    @Value("${internal.service.secret}")
    private String internalServiceSecret;

    public RestClientConfig(RestClient.Builder autoConfiguredBuilder) {
        this.autoConfiguredBuilder = autoConfiguredBuilder;
    }

    @Bean
    public RestClient storeRestClient() {
        return autoConfiguredBuilder.clone()
                .baseUrl(storeServiceUrl)
                .defaultHeader("X-Internal-Token", internalServiceSecret)
                .build();
    }

    @Bean
    public RestClient profileRestClient() {
        return autoConfiguredBuilder.clone()
                .baseUrl(profileServiceUrl)
                .defaultHeader("X-Internal-Token", internalServiceSecret)
                .build();
    }

    @Bean
    public RestClient surpriseBoxRestClient() {
        return autoConfiguredBuilder.clone()
                .baseUrl(surpriseboxServiceUrl)
                .defaultHeader("X-Internal-Token", internalServiceSecret)
                .build();
    }

    @Bean
    public RestClient paymentRestClient() {
        return autoConfiguredBuilder.clone()
                .baseUrl(paymentServiceUrl)
                .defaultHeader("X-Internal-Token", internalServiceSecret)
                .build();
    }
}
