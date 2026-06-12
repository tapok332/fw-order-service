package kh.karazin.foodwise.order.repository;

import kh.karazin.foodwise.order.entity.OrderStatusHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for order status history entries.
 */
public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistoryEntity, UUID> {

    List<OrderStatusHistoryEntity> findByOrderIdOrderByChangedAtAsc(UUID orderId);
}
