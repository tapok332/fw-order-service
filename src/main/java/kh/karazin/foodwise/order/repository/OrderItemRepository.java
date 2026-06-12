package kh.karazin.foodwise.order.repository;

import kh.karazin.foodwise.order.entity.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository for order item entities.
 */
public interface OrderItemRepository extends JpaRepository<OrderItemEntity, UUID> {
}
