package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<PurchaseOrder> findFirstByPostIdAndOrderStatusOrderByCreatedAtDesc(Long postId, OrderStatus orderStatus);

	@Query("""
		select o.post.id
		from PurchaseOrder o
		where o.id = :id and o.user.id = :userId
		""")
	Optional<Long> findPostIdByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select o
		from PurchaseOrder o
		join fetch o.user
		join fetch o.post
		where o.id = :id and o.user.id = :userId
		""")
	Optional<PurchaseOrder> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
