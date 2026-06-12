package kh.karazin.foodwise.order.adapter.out.persistence;

import kh.karazin.foodwise.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link OrderEntity}.
 */
public interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {

    Page<OrderEntity> findByProfileIdOrderByCreatedAtDesc(UUID profileId, Pageable pageable);

    /**
     * Mock-fulfillment sweep. {@code JOIN FETCH} the items because the caller
     * (the scheduler) maps each result to a full domain aggregate outside a
     * transaction — a lazy items collection would otherwise fail to initialize.
     */
    @Query("SELECT DISTINCT o FROM OrderEntity o LEFT JOIN FETCH o.items WHERE o.status IN :statuses")
    List<OrderEntity> findByStatusInWithItems(@Param("statuses") Collection<OrderStatus> statuses);
}
