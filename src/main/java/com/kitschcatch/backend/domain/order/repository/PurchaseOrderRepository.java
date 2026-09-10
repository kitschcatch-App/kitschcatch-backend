package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

	@EntityGraph(attributePaths = {"user", "post"})
	Optional<PurchaseOrder> findByOrderNumberAndUserId(String orderNumber, Long userId);

	@Query("select o.id from PurchaseOrder o where o.orderNumber = :orderNumber and o.user.id = :userId")
	Optional<Long> findIdByOrderNumberAndUserId(@Param("orderNumber") String orderNumber, @Param("userId") Long userId);

	@Query("select o.post.id from PurchaseOrder o where o.id = :id")
	Optional<Long> findPostIdById(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from PurchaseOrder o where o.id = :id")
	Optional<PurchaseOrder> findByIdForUpdate(@Param("id") Long id);

	boolean existsByPostIdAndOrderStatusIn(Long postId, Collection<OrderStatus> statuses);

	@Query("""
		select o.id from PurchaseOrder o
		where o.orderStatus = com.kitschcatch.backend.domain.order.entity.OrderStatus.PENDING
		and o.reservationExpiresAt <= :now
		and exists (select p.id from Payment p where p.order = o
			and p.paymentStatus = com.kitschcatch.backend.domain.order.entity.PaymentStatus.READY)
		order by o.reservationExpiresAt, o.id
		""")
	List<Long> findExpiredReservationIds(@Param("now") LocalDateTime now, Pageable pageable);
}
