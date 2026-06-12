package kh.karazin.foodwise.order.adapter.out.persistence;

import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.adapter.in.rest.OrderDto;
import kh.karazin.foodwise.order.adapter.in.rest.OrderRestMapper;
import kh.karazin.foodwise.order.application.port.out.OrderRepository;
import kh.karazin.foodwise.order.domain.DeliveryType;
import kh.karazin.foodwise.order.domain.Order;
import kh.karazin.foodwise.order.domain.OrderDraft;
import kh.karazin.foodwise.order.domain.OrderItem;
import kh.karazin.foodwise.order.domain.PaymentType;
import kh.karazin.foodwise.order.domain.ProfileId;
import kh.karazin.foodwise.order.domain.ResolvedOrderLine;
import kh.karazin.foodwise.order.domain.StoreId;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;
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
 * Persistence integration test: places a domain {@link Order} through the
 * {@link OrderRepository} port and reads it back, asserting that the embedded
 * {@link Money} columns round-trip through the Flyway schema + Hibernate mapping
 * and that the persisted total matches the domain-computed value.
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
    @DisplayName("Money fields round-trip and the persisted total equals the domain total")
    void moneyFieldsRoundTripThroughDatabase() {
        // 2 × 25000 + 1 × 30000 = 80000 minor (UAH)
        Money itemPrice1 = Money.ofMinor(25000L, UAH);
        Money itemPrice2 = Money.ofMinor(30000L, UAH);
        Money expectedTotal = itemPrice1.times(2).plus(itemPrice2);

        Order placed = Order.place(new OrderDraft(
                new ProfileId(UUID.randomUUID()),
                new StoreId(UUID.randomUUID()),
                "Test Store",
                PaymentType.CASH,
                DeliveryType.PICKUP,
                null,
                List.of(
                        new ResolvedOrderLine(new SurpriseBoxId(UUID.randomUUID()), "Box A", itemPrice1, 2),
                        new ResolvedOrderLine(new SurpriseBoxId(UUID.randomUUID()), "Box B", itemPrice2, 1)),
                null));

        Order saved = orderRepository.save(placed);
        Order loaded = orderRepository.findById(saved.id()).orElseThrow();

        // totalPrice round-trips as Money
        assertThat(loaded.totalPrice()).isEqualTo(expectedTotal);
        assertThat(loaded.totalPrice().amountMinor()).isEqualTo(80000L);
        assertThat(loaded.totalPrice().currency()).isEqualTo(UAH);

        // item prices round-trip and lines carry persistence ids
        assertThat(loaded.items()).hasSize(2);
        assertThat(loaded.items()).allSatisfy(item -> assertThat(item.id()).isNotNull());
        assertThat(loaded.items().stream().map(OrderItem::price).toList())
                .containsExactlyInAnyOrder(itemPrice1, itemPrice2);

        // sum of item.price × quantity equals the persisted total
        Money computed = loaded.items().stream()
                .map(i -> i.price().times(i.quantity()))
                .reduce(Money.ofMinor(0L, UAH), Money::plus);
        assertThat(computed).isEqualTo(expectedTotal);

        // the REST view carries Money (not int) end-to-end
        OrderDto dto = OrderRestMapper.toDto(loaded);
        assertThat(dto.totalPrice()).isEqualTo(expectedTotal);
        assertThat(dto.items()).allSatisfy(i -> assertThat(i.price()).isNotNull());
    }
}
