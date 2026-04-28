package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
}
