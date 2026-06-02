package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

	@EntityGraph(attributePaths = {"user", "post"})
	Optional<PurchaseOrder> findByOrderNumberAndUserId(String orderNumber, Long userId);
}
