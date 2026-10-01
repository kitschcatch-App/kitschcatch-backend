// 하나의 조회 시점에서 함께 읽은 주문과 선택적인 결제 정보를 전달한다.
package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;

public record OrderHistoryRow(PurchaseOrder order, Payment payment) {}
