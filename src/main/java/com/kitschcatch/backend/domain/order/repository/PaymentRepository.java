// 결제 식별자 기반 조회를 담당하는 JPA Repository
package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	@EntityGraph(attributePaths = {"order", "order.user"})
	Optional<Payment> findByPaymentIdAndOrderUserId(String paymentId, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.paymentId = :paymentId and p.order.user.id = :userId")
	Optional<Payment> findByPaymentIdAndOrderUserIdWithLock(
		@Param("paymentId") String paymentId,
		@Param("userId") Long userId
	);

	@Query("select p.order.id from Payment p where p.paymentId = :paymentId and p.order.user.id = :userId")
	Optional<Long> findOrderIdByPaymentIdAndOrderUserId(@Param("paymentId") String paymentId, @Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.order.id = :orderId")
	Optional<Payment> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
