package kh.karazin.foodwise.order.repository;

import kh.karazin.foodwise.order.entity.OrderEntity;
import kh.karazin.foodwise.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for order entities.
 */
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    Page<OrderEntity> findByProfileIdOrderByCreatedAtDesc(UUID profileId, Pageable pageable);

    List<OrderEntity> findByProfileId(UUID profileId);

    List<OrderEntity> findByStatusIn(Collection<OrderStatus> statuses);
}
