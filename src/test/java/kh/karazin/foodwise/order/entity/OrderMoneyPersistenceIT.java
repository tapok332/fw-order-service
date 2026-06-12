package kh.karazin.foodwise.order.entity;

import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence integration test: verifies that {@link Money} fields in
 * {@link OrderEntity} and {@link OrderItemEntity} round-trip through Flyway
 * schema + Hibernate correctly, and that the computed total equals the sum
 * of item prices × quantities.
 *
 * <p>Uses {@code @SpringBootTest} + Testcontainers (no {@code @DataJpaTest} —
 * removed in Spring Boot 4). Flyway migrations run automatically so the schema
 * is real PostgreSQL, not H2.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "internal.service.secret=test-internal-secret",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "foodwise.order.currency=UAH",
        "foodwise.order.stripe-currency=uah",
        "foodwise.order.demo-auto-advance=false"
})
class OrderMoneyPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final Currency UAH = Currency.getInstance("UAH");

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @Transactional
    @DisplayName("Money embedded fields persist and load correctly for order and items")
    void moneyFieldsRoundTripThroughDatabase() {
        // Arrange — two items: 2×25000 kopecks + 1×30000 kopecks = 80000 kopecks total
        Money itemPrice1 = Money.ofMinor(25000L, UAH);
        Money itemPrice2 = Money.ofMinor(30000L, UAH);
        Money expectedTotal = itemPrice1.times(2).plus(itemPrice2);  // 80000

        OrderEntity order = OrderEntity.builder()
                .profileId(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .storeName("Test Store")
                .status(OrderStatus.PENDING)
                .paymentType(PaymentType.CASH)
                .deliveryType(DeliveryType.PICKUP)
                .totalPrice(expectedTotal)
                .build();

        OrderItemEntity item1 = OrderItemEntity.builder()
                .order(order)
                .surpriseBoxId(UUID.randomUUID())
                .name("Box A")
                .price(itemPrice1)
                .quantity(2)
                .build();
        OrderItemEntity item2 = OrderItemEntity.builder()
                .order(order)
                .surpriseBoxId(UUID.randomUUID())
                .name("Box B")
                .price(itemPrice2)
                .quantity(1)
                .build();
        order.setItems(List.of(item1, item2));

        // Act
        OrderEntity saved = orderRepository.save(order);
        orderRepository.flush();

        OrderEntity loaded = orderRepository.findById(saved.getId())
                .orElseThrow(() -> new AssertionError("Order not found after save"));

        // Assert — totalPrice Money round-trips correctly
        assertThat(loaded.getTotalPrice())
                .as("totalPrice must round-trip as Money")
                .isEqualTo(expectedTotal);
        assertThat(loaded.getTotalPrice().amountMinor()).isEqualTo(80000L);
        assertThat(loaded.getTotalPrice().currency()).isEqualTo(UAH);

        // Assert — item prices round-trip correctly
        assertThat(loaded.getItems()).hasSize(2);
        var loadedPrices = loaded.getItems().stream()
                .map(OrderItemEntity::getPrice)
                .toList();
        assertThat(loadedPrices).containsExactlyInAnyOrder(itemPrice1, itemPrice2);

        // Assert — computed sum of item prices equals totalPrice
        Money computedTotal = loaded.getItems().stream()
                .map(i -> i.getPrice().times(i.getQuantity()))
                .reduce(Money.ofMinor(0L, UAH), Money::plus);
        assertThat(computedTotal)
                .as("sum of item.price × quantity must equal order.totalPrice")
                .isEqualTo(expectedTotal);
    }

    @Test
    @Transactional
    @DisplayName("OrderDto produced by toDto() carries Money fields (not int)")
    void toDtoExposesMoneyFields() {
        Money price = Money.ofMinor(15000L, UAH);
        OrderEntity order = OrderEntity.builder()
                .profileId(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .storeName("Dto Store")
                .status(OrderStatus.PENDING)
                .paymentType(PaymentType.CASH)
                .deliveryType(DeliveryType.PICKUP)
                .totalPrice(price)
                .build();

        OrderItemEntity item = OrderItemEntity.builder()
                .order(order)
                .surpriseBoxId(UUID.randomUUID())
                .name("Box X")
                .price(price)
                .quantity(1)
                .build();
        order.setItems(List.of(item));

        OrderEntity saved = orderRepository.save(order);
        orderRepository.flush();
        OrderEntity loaded = orderRepository.findById(saved.getId()).orElseThrow();

        var dto = loaded.toDto();
        assertThat(dto.totalPrice()).isEqualTo(price);
        assertThat(dto.items()).singleElement()
                .satisfies(i -> assertThat(i.price()).isEqualTo(price));
    }
}
