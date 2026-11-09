// 정산 조회 작업을 저장하고 실행권을 가진 작업자의 결과만 반영한다.
package com.kitschcatch.backend.domain.order.settlement;

import com.kitschcatch.backend.domain.order.entity.OrderSettlement.Status;
import com.kitschcatch.backend.domain.order.service.OrderReservationService;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
public class SettlementSyncJobs {
    record Claim(long orderId, String settlementId, UUID token, SettlementGateway.Command command) {}
    public record Item(String orderId, String settlementId, String settlementStatus, String syncState,
                       int attempts, OffsetDateTime lastCheckedAt, OffsetDateTime nextCheckAt, String lastError) {}

    private static final String ITEMS = """
        select o.order_number,o.settlement_id,o.settlement_status,j.state,j.attempts,
          j.last_checked_at,j.next_check_at,j.last_error
        from orders o left join settlement_sync_jobs j on j.settlement_id=o.settlement_id
        """;
    private final JdbcTemplate jdbc;
    private final OrderReservationService reservations;
    private final SettlementTransactionService transactions;
    private final SettlementProperties properties;
    private final UserRepository users;
    @Value("${app.settlement.sync-enabled:false}")
    private boolean enabled;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void submitted(SettlementTransactionService.Submitted event) {
        if (!enabled) return;
        // 지급 호출이 시작되기 전에 조회 작업을 같은 커밋에 남긴다.
        jdbc.update("""
            insert into settlement_sync_jobs(settlement_id,next_check_at) values (?,now()+interval '2 minutes')
            on conflict(settlement_id) do update set state='QUEUED', next_check_at=excluded.next_check_at,
              attempts=0, lease_token=null, lease_until=null, last_error=null
            """, event.settlementId());
    }

    @Transactional
    public Claim claim() {
        jdbc.update("""
            insert into settlement_sync_jobs(settlement_id,next_check_at)
            select o.settlement_id,now() from orders o
            where o.settlement_status in ('PROCESSING','UNKNOWN') and o.settlement_amount is not null
              and not exists(select 1 from settlement_sync_jobs j where j.settlement_id=o.settlement_id)
            order by o.id limit 50 on conflict do nothing
            """);
        UUID token = UUID.randomUUID();
        return jdbc.query("""
            with due as (
                select j.settlement_id from settlement_sync_jobs j
                where j.state='QUEUED' and j.next_check_at<=now()
                  and (j.lease_until is null or j.lease_until<=now())
                order by j.next_check_at,j.settlement_id limit 1 for update skip locked
            ), claimed as (
                update settlement_sync_jobs j set lease_token=?,lease_until=now()+interval '2 minutes'
                from due where j.settlement_id=due.settlement_id returning j.settlement_id
            )
            select o.* from orders o join claimed c using (settlement_id)
            """, (rs, row) -> {
                String id = rs.getString("settlement_id");
                var status = Status.valueOf(rs.getString("settlement_status"));
                var command = status == Status.WAITING || status == Status.COMPLETED ? null
                    : new SettlementGateway.Command(id, rs.getLong("seller_id"), rs.getLong("settlement_amount"),
                        rs.getLong("settlement_fee"), "KRW", rs.getString("settlement_destination"),
                        rs.getString("settlement_provider_reference"));
                return new Claim(rs.getLong("id"), id, token, command);
            }, token).stream().findFirst().orElse(null);
    }

    @Transactional
    public void finish(Claim claim, SettlementGateway.Result result, String error) {
        var order = reservations.lockOrder(claim.orderId());
        var settlement = order.getSettlement();
        var attempts = jdbc.queryForList("""
            select attempts from settlement_sync_jobs where settlement_id=? and lease_token=? and lease_until>clock_timestamp()
            for update
            """, Integer.class, claim.settlementId(), claim.token());
        if (attempts.isEmpty()) return;
        boolean current = settlement != null && claim.settlementId().equals(settlement.getSettlementId());
        if (current && claim.command() != null && settlement.getStatus() != Status.WAITING && error == null) {
            transactions.applyResult(order, result);
        }
        int attempt = attempts.getFirst() + 1;
        boolean done = !current || settlement.getStatus() == Status.WAITING
            || settlement.getStatus() == Status.COMPLETED || settlement.getStatus() == Status.FAILED && error == null;
        String state = done ? "DONE" : attempt >= 16 ? "REVIEW_REQUIRED" : "QUEUED";
        String lastError = error != null ? error
            : !done && settlement.getStatus() == Status.UNKNOWN ? "UNVERIFIED_RESULT" : null;
        long delay = Math.min(3600, 60L << Math.min(attempt - 1, 6));
        delay += java.util.concurrent.ThreadLocalRandom.current().nextLong(15);
        jdbc.update("""
            update settlement_sync_jobs set state=?, attempts=?, last_error=?, last_checked_at=now(),
                next_check_at=now()+make_interval(secs => cast(? as double precision)),lease_token=null,lease_until=null
            where settlement_id=? and lease_token=?
            """, state, attempt, lastError, delay, claim.settlementId(), claim.token());
    }

    @Transactional(readOnly = true)
    public List<Item> list(long actor, int page, int size) {
        requireOperator(actor);
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.BAD_REQUEST);
        return jdbc.query(ITEMS + """
            where o.settlement_status in ('PROCESSING','UNKNOWN','FAILED')
            order by o.settlement_requested_at, o.id limit ? offset ?
            """, SettlementSyncJobs::item, size, (long) page * size);
    }

    @Transactional(readOnly = true)
    public Item detail(long actor, String id) {
        requireOperator(actor);
        return find(id);
    }

    @Transactional
    public Item refresh(long actor, String id) {
        requireOperator(actor);
        if (!enabled) throw new BusinessException(ErrorCode.SETTLEMENT_NOT_CONFIGURED);
        long orderId = jdbc.queryForList("select id from orders where settlement_id=?", Long.class, id)
            .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.SETTLEMENT_NOT_FOUND));
        var status = reservations.lockOrder(orderId).getSettlement().getStatus();
        if (status == Status.WAITING || status == Status.COMPLETED)
            throw new BusinessException(ErrorCode.SETTLEMENT_INVALID_STATE);
        jdbc.update("""
            insert into settlement_sync_jobs(settlement_id,next_check_at) values (?,now())
            on conflict(settlement_id) do update set state='QUEUED', next_check_at=now(),
              attempts=0, last_error=null, lease_token=null, lease_until=null
            where settlement_sync_jobs.state<>'QUEUED'
                or (coalesce(settlement_sync_jobs.lease_until,now())<=now()
                    and settlement_sync_jobs.next_check_at>now())
            """, id);
        return find(id);
    }

    private Item find(String id) {
        return jdbc.query(ITEMS + " where o.settlement_id=?", SettlementSyncJobs::item, id)
            .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.SETTLEMENT_NOT_FOUND));
    }

    private static Item item(ResultSet rs, int row) throws SQLException {
        return new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
            rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class), rs.getString(8));
    }

    private void requireOperator(long actor) {
        if (!properties.operatorUserIds().contains(actor) || !users.existsByIdAndWithdrawnAtIsNull(actor))
            throw new BusinessException(ErrorCode.SETTLEMENT_FORBIDDEN);
    }
}
