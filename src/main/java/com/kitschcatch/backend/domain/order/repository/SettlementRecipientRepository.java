// 검증된 판매자 지급 수취 식별자를 조회하고 저장한다.
package com.kitschcatch.backend.domain.order.repository;
import com.kitschcatch.backend.domain.order.entity.SettlementRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SettlementRecipientRepository extends JpaRepository<SettlementRecipient,Long> {}
