// 만료된 미결제 주문을 주기적으로 조회하고 주문별로 예약을 해제한다.
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "app.orders", name = "expiration-enabled", havingValue = "true", matchIfMissing = true)
public class OrderReservationScheduler {
	private static final Logger log = LoggerFactory.getLogger(OrderReservationScheduler.class);
	private final PurchaseOrderRepository orderRepository;
	private final OrderReservationService reservationService;

	public OrderReservationScheduler(PurchaseOrderRepository orderRepository, OrderReservationService reservationService) {
		this.orderRepository = orderRepository;
		this.reservationService = reservationService;
	}

	@Scheduled(fixedDelayString = "${app.orders.expiration-scan-delay:60s}", initialDelayString = "${app.orders.expiration-scan-delay:60s}")
	public void expireReservations() {
		for (Long id : orderRepository.findExpiredReservationIds(LocalDateTime.now(), PageRequest.of(0, 100))) {
			try {
				reservationService.expireOrder(id);
			} catch (RuntimeException exception) {
				log.warn("주문 예약 만료 처리 실패. orderId={}", id, exception);
			}
		}
	}
}
