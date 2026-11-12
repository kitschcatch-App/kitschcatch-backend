// 환불 운영자가 심사 대상을 찾고 기존 환불 상세를 조회한다.
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.RefundResponse;
import com.kitschcatch.backend.domain.order.entity.OrderRefund.Status;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.post.service.PostImageStorage;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRefundService {
    private final NamedParameterJdbcTemplate jdbc;
    private final RefundProperties properties;
    private final UserRepository users;
    private final RefundService refunds;
    private final PostImageStorage images;

    public record Item(String orderId, String refundId, Status status, long amount, String reason,
                       LocalDateTime requestedAt, String title, String thumbnailUrl, long buyerId, String buyerNickname,
                       long sellerId, String sellerNickname, boolean shipped, Boolean returnReceived,
                       int imageCount, List<String> availableActions) {}
    public record Result(List<Item> items, int page, int size, long totalElements) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result list(long actor, List<Status> statuses, LocalDateTime from, LocalDateTime to,
                       String orderId, String refundId, Boolean shipped, int page, int size) {
        requireOperator(actor);
        if (page < 0 || size < 1 || size > 100 || from != null && to != null && !from.isBefore(to))
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        if (statuses != null && statuses.stream().anyMatch(java.util.Objects::isNull))
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        var params = new MapSqlParameterSource("statuses",
            (statuses == null || statuses.isEmpty() ? List.of(Status.REQUESTED, Status.PROCESSING) : statuses)
                .stream().map(Enum::name).distinct().toList());
        var where = new StringBuilder(" where o.refund_id is not null and o.refund_status in (:statuses)");
        filter(where, params, "o.refund_requested_at >= :from", "from", from);
        filter(where, params, "o.refund_requested_at < :to", "to", to);
        filter(where, params, "o.order_number = :orderId", "orderId", orderId);
        filter(where, params, "o.refund_id = :refundId", "refundId", refundId);
        if (shipped != null) where.append(shipped ? " and o.shipment_registered_at is not null"
            : " and o.shipment_registered_at is null");
        long total = jdbc.queryForObject("select count(*) from orders o" + where, params, Long.class);
        params.addValue("size", size).addValue("offset", (long) page * size);
        var rows = jdbc.query("""
            select o.order_number,o.refund_id,o.refund_status,o.refund_amount,o.refund_reason,o.refund_requested_at,
              o.post_title,o.post_thumbnail_key,o.user_id,o.buyer_nickname,o.seller_id,o.seller_nickname,
              o.shipment_registered_at,o.refund_return_received,
              (o.order_status='PAID' and p.payment_status='SUCCESS' and p.recovery_state<>'REVIEW_REQUIRED') as can_review,
              (select count(*) from refund_images i where i.order_id=o.id
              and i.refund_id=o.refund_id and i.state='ATTACHED') as image_count
            from orders o left join payments p on p.order_id=o.id
            """ + where + " order by o.refund_requested_at, o.id limit :size offset :offset", params, (rs, row) -> {
            var status = Status.valueOf(rs.getString("refund_status"));
            boolean sent = rs.getObject("shipment_registered_at") != null;
            boolean canReview = status == Status.REQUESTED && sent && rs.getBoolean("can_review");
            String thumbnail = rs.getString("post_thumbnail_key");
            return new Item(rs.getString("order_number"), rs.getString("refund_id"), status,
                rs.getLong("refund_amount"), rs.getString("refund_reason"),
                rs.getObject("refund_requested_at", LocalDateTime.class), rs.getString("post_title"),
                thumbnail == null ? null : images.imageUrl(thumbnail),
                rs.getLong("user_id"), rs.getString("buyer_nickname"), rs.getLong("seller_id"),
                rs.getString("seller_nickname"), sent, rs.getObject("refund_return_received", Boolean.class),
                rs.getInt("image_count"), canReview ? List.of("APPROVE", "REJECT") : List.of());
        });
        return new Result(rows, page, size, total);
    }

    @Transactional(readOnly = true)
    public RefundResponse detail(long actor, String refundId) {
        requireOperator(actor);
        String orderId = jdbc.query("select order_number from orders where refund_id=:id",
            new MapSqlParameterSource("id", refundId), (rs, i) -> rs.getString(1))
            .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));
        return refunds.get(actor, orderId);
    }

    private void requireOperator(long actor) {
        if (!properties.operatorUserIds().contains(actor) || !users.existsByIdAndWithdrawnAtIsNull(actor))
            throw new BusinessException(ErrorCode.REFUND_FORBIDDEN);
    }

    private static void filter(StringBuilder where, MapSqlParameterSource params, String expression,
                               String name, Object value) {
        if (value == null) return;
        where.append(" and ").append(expression);
        params.addValue(name, value);
    }
}
