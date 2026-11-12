// 결제 웹훅을 중복 없이 저장하고 처리 후보를 조회한다.
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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {

	@Modifying
	@Query("""
		insert into PaymentWebhookEvent
		    (transmissionId, eventType, eventHash, pgOrderId, paymentKey, pgStatus, pgCreatedAt,
		     processingStatus, receiveCount, nextProcessAt, processedAt, failureReason, receivedAt)
		values (:#{#event.transmissionId}, :#{#event.eventType}, :#{#event.eventHash},
		        :#{#event.pgOrderId}, :#{#event.paymentKey}, :#{#event.pgStatus}, :#{#event.pgCreatedAt},
		        :#{#event.processingStatus}, 0, :#{#event.nextProcessAt}, :#{#event.processedAt},
		        :#{#event.failureReason}, :#{#event.nextProcessAt})
		on conflict do nothing
		""")
	int insertIfAbsent(@Param("event") PaymentWebhookEvent event);

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
