// 주문과 결제를 한 번에 조회하고 구매자·스냅샷 판매자의 최신순 페이지를 반환한다.
package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface OrderHistoryRepository extends Repository<PurchaseOrder, Long> {
    String ROW_QUERY = """
        select new com.kitschcatch.backend.domain.order.repository.OrderHistoryRow(o, p)
        from PurchaseOrder o left join Payment p on p.order = o
        """;

    @Query(ROW_QUERY + "where o.orderNumber = :orderNumber")
    Optional<OrderHistoryRow> findDetail(@Param("orderNumber") String orderNumber);

    @Query(value = ROW_QUERY + """
        where o.user.id = :userId and (:status is null or o.orderStatus = :status)
        order by o.createdAt desc, o.id desc
        """, countQuery = """
        select count(o) from PurchaseOrder o
        where o.user.id = :userId and (:status is null or o.orderStatus = :status)
        """)
    Page<OrderHistoryRow> findPurchases(@Param("userId") Long userId, @Param("status") OrderStatus status, Pageable pageable);

    @Query(value = ROW_QUERY + """
        where o.sellerId = :userId and (:status is null or o.orderStatus = :status)
        order by o.createdAt desc, o.id desc
        """, countQuery = """
        select count(o) from PurchaseOrder o
        where o.sellerId = :userId and (:status is null or o.orderStatus = :status)
        """)
    Page<OrderHistoryRow> findSales(@Param("userId") Long userId, @Param("status") OrderStatus status, Pageable pageable);
}
