// 결제 식별자 기반 조회를 담당하는 JPA Repository
package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	@EntityGraph(attributePaths = {"order", "order.user"})
	Optional<Payment> findByPaymentIdAndOrderUserId(String paymentId, Long userId);
}
