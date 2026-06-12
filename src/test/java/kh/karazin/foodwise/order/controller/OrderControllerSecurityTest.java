package kh.karazin.foodwise.order.controller;

import kh.karazin.foodwise.common.exception.FoodWiseErrorCode;
import kh.karazin.foodwise.common.exception.FoodWiseException;
import kh.karazin.foodwise.common.exception.GlobalExceptionHandler;
import kh.karazin.foodwise.order.config.SecurityConfig;
import kh.karazin.foodwise.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security smoke test for OrderController.
 *
 * Uses an explicit @ContextConfiguration to bypass the main OrderApplication
 * (which triggers JPA / Outbox wiring) and configure only what the WebMvc slice
 * needs to verify @PreAuthorize behavior.
 *
 * Requests carry X-Internal-Token because in production all traffic flows
 * through the gateway, which always injects this header.
 */
@WebMvcTest(controllers = OrderController.class)
@ContextConfiguration(classes = OrderControllerSecurityTest.WebSliceConfig.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "internal.service.secret=test-internal-secret")
class OrderControllerSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class
    })
    @ComponentScan(
            basePackageClasses = OrderController.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = OrderController.class))
    static class WebSliceConfig {
    }

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_TOKEN_VALUE = "test-internal-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void updateOrderStatus_returns403_whenCallerLacksAdminRole() throws Exception {
        String body = """
                {"status": "DELIVERED"}
                """;

        mockMvc.perform(put("/orders/{orderId}/status", UUID.randomUUID())
                        .with(user("client").roles("CLIENT"))
                        .with(csrf())
                        .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN_VALUE)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelOrder_isRejected_whenAnonymous() throws Exception {
        mockMvc.perform(put("/orders/{orderId}/cancel", UUID.randomUUID())
                        .with(csrf())
                        .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN_VALUE))
                .andExpect(status().is4xxClientError());
    }

    /**
     * Pack 1, task 1.1 — IDOR closure on {@code GET /orders/{orderId}}.
     *
     * <p>Reproduces the cross-user retrieval scenario: an authenticated
     * client requests an order belonging to a different profile. Before the
     * fix, {@code OrderController.getOrderById} delegated to
     * {@code OrderService.getOrderById(UUID)} which did no ownership check,
     * returning the full {@code OrderDto} (delivery address, pickup code,
     * totalPrice) for any guessable UUID. After the fix, the controller
     * passes the caller's {@code X-User-Id} into
     * {@code OrderService.getOrderByIdForUser(UUID, UUID)}, which throws
     * {@code FORBIDDEN} on mismatch — surfacing as HTTP 403.
     */
    @Test
    void getOrderById_returns403_whenCallerIsNotOrderOwner() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID attackerProfileId = UUID.randomUUID();

        when(orderService.getOrderByIdForUser(eq(orderId), any(UUID.class)))
                .thenThrow(FoodWiseException.errorWithDescription(
                        FoodWiseErrorCode.FORBIDDEN, "You are not allowed to view this order"));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user("client").roles("CLIENT"))
                        .with(csrf())
                        .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN_VALUE)
                        .header("X-User-Id", attackerProfileId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

}
