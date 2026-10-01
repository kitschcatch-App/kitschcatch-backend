// 거래 종료와 외부 결과 확정 여부를 구매자·판매자 양쪽에서 검사한다.
package com.kitschcatch.backend.domain.user.repository;

import com.kitschcatch.backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface UserWithdrawalRepository extends Repository<User, Long> {
    @Query(value = """
        select id from posts where user_id = :userId
        union select post_id from orders where user_id = :userId or seller_id = :userId
        order by id
        """, nativeQuery = true)
    java.util.List<Long> findAffectedPostIds(@Param("userId") Long userId);

    @Query("select o.id from PurchaseOrder o where o.user.id = :userId or o.sellerId = :userId order by o.id")
    java.util.List<Long> findOrderIds(@Param("userId") Long userId);

    @Query(value = """
        select count(*) from orders o
        where (o.user_id = :userId or o.seller_id = :userId) and (
            o.order_status not in ('CANCELED', 'REFUNDED', 'PURCHASE_CONFIRMED')
            or (o.order_status = 'PURCHASE_CONFIRMED'
                and (o.settlement_status is null or o.settlement_status <> 'COMPLETED'))
            or (o.refund_id is not null
                and (o.refund_status is null or o.refund_status not in ('COMPLETED', 'REJECTED', 'WITHDRAWN')))
            or (o.settlement_id is not null
                and (o.settlement_status is null or o.settlement_status <> 'COMPLETED'))
            or not exists (select 1 from payments p where p.order_id = o.id)
            or exists (select 1 from payments p where p.order_id = o.id and (
                p.payment_status = 'PROCESSING' or p.recovery_state is null or p.recovery_state <> 'NONE'
                or (o.order_status in ('CANCELED', 'REFUNDED') and p.payment_status not in ('CANCELED', 'FAILED'))
                or (o.order_status = 'PURCHASE_CONFIRMED' and p.payment_status <> 'SUCCESS')
                or exists (select 1 from payment_attempts a where a.payment_id = p.id and (
                    a.attempt_status in ('PROCESSING', 'UNKNOWN')
                    or (a.operation = 'CANCEL' and a.attempt_status in ('PREPARED', 'FAILED')
                        and (p.current_attempt_id is null or p.current_attempt_id = a.attempt_id))))))
        )
        """, nativeQuery = true)
    long countBlockingTrades(@Param("userId") Long userId);
}
