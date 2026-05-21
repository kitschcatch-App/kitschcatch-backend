package com.kitschcatch.backend.domain.payment.repository;

import com.kitschcatch.backend.domain.payment.entity.Payment;
import com.kitschcatch.backend.domain.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
		Long orderId,
		Collection<PaymentStatus> statuses
	);

	@Query("""
		select o.post.id
		from Payment p
		join p.order o
		where p.id = :id and o.user.id = :userId
		""")
	Optional<Long> findPostIdByIdAndOrderUserId(@Param("id") Long id, @Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select p
		from Payment p
		join fetch p.order o
		join fetch o.user
		join fetch o.post
		where p.id = :id and o.user.id = :userId
		""")
	Optional<Payment> findByIdAndOrderUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
