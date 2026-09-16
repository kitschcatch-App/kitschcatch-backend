// 결제 시도 이력의 조회와 잠금을 담당하는 JPA Repository
package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

	Optional<PaymentAttempt> findByAttemptId(String attemptId);

	@Query("select a.payment.order.id from PaymentAttempt a where a.attemptId = :attemptId")
	Optional<Long> findOrderIdByAttemptId(@Param("attemptId") String attemptId);

	@Query("select a.payment.paymentId from PaymentAttempt a where a.attemptId = :attemptId")
	Optional<String> findPaymentIdByAttemptId(@Param("attemptId") String attemptId);

	Optional<PaymentAttempt> findByPaymentIdAndSourceAttemptAttemptId(
		Long paymentId,
		String sourceAttemptId
	);

	Optional<PaymentAttempt> findTopByPaymentIdOrderBySequenceNumberDesc(Long paymentId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from PaymentAttempt a where a.attemptId = :attemptId")
	Optional<PaymentAttempt> findByAttemptIdForUpdate(@Param("attemptId") String attemptId);

	@Query("""
		select a from PaymentAttempt a
		where a.attemptStatus in :statuses
		and a.payment.recoveryState <> com.kitschcatch.backend.domain.order.entity.PaymentRecoveryState.REVIEW_REQUIRED
		and a.nextCheckAt <= :now
		and (a.leaseUntil is null or a.leaseUntil <= :now)
		order by a.nextCheckAt, a.id
		""")
	List<PaymentAttempt> findRecoveryCandidates(
		@Param("statuses") Collection<PaymentAttemptStatus> statuses,
		@Param("now") LocalDateTime now,
		Pageable pageable
	);
}
