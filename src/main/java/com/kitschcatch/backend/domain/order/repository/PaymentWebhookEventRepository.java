// 결제 웹훅 수신 기록의 조회와 중복 확인을 담당하는 JPA Repository
package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.PaymentWebhookEvent;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookProcessingStatus;
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

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {

	Optional<PaymentWebhookEvent> findByTransmissionId(String transmissionId);

	Optional<PaymentWebhookEvent> findByEventTypeAndEventHash(String eventType, String eventHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from PaymentWebhookEvent e where e.id = :id")
	Optional<PaymentWebhookEvent> findByIdForUpdate(@Param("id") Long id);

	@Query("""
		select e from PaymentWebhookEvent e
		where e.processingStatus in :statuses
		and e.nextProcessAt <= :now
		and (e.leaseUntil is null or e.leaseUntil <= :now)
		order by e.nextProcessAt, e.id
		""")
	List<PaymentWebhookEvent> findProcessingCandidates(
		@Param("statuses") Collection<PaymentWebhookProcessingStatus> statuses,
		@Param("now") LocalDateTime now,
		Pageable pageable
	);
}
