package kh.karazin.foodwise.order.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Order-service runtime configuration.
 *
 * <p>{@code currency} and {@code stripeCurrency} are retained as startup-validation
 * gates until Phase 7 (currency now travels inside {@link kh.karazin.foodwise.common.money.Money};
 * see ADR 0012). Their validation constraints ensure deployments with a mis-cased or
 * missing currency property fail fast at boot rather than at the first Stripe call.
 * Remove them only when the outbox payloads and Stripe integration are fully migrated
 * to derive currency from the {@code Money} value (Phase 7.1 / 7.2).
 *
 * <p>{@code demoAutoAdvance} is the kill switch for the mock fulfillment
 * scheduler that walks orders through the status lifecycle once per minute.
 * It exists so a real deployment can disable the fake progression without a
 * code change.
 */
@Validated
@ConfigurationProperties(prefix = "foodwise.order")
public record OrderProperties(
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO-4217 uppercase code")
        String currency,
        @NotBlank @Pattern(regexp = "^[a-z]{3}$", message = "stripeCurrency must be a 3-letter lowercase code")
        String stripeCurrency,
        boolean demoAutoAdvance
) {
}
