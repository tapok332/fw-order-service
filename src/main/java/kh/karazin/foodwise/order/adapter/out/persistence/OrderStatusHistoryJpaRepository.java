package kh.karazin.foodwise.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data repository for the order status-history audit table.
 */
public interface OrderStatusHistoryJpaRepository extends JpaRepository<OrderStatusHistoryEntity, UUID> {
}
