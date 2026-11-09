// 운송장 버전별 작업·이력을 저장하고 지연되거나 중복된 결과를 걸러낸다.
package com.kitschcatch.backend.domain.order.tracking;

import com.kitschcatch.backend.domain.notification.NotificationEvents.TradeChanged;
import com.kitschcatch.backend.domain.notification.NotificationType;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.service.OrderAccessService;
import com.kitschcatch.backend.domain.order.service.OrderReservationService;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
public class ShipmentTracking {
    public record Registered(PurchaseOrder order) {}
    record Claim(long orderId, long revision, String carrier, String number, UUID callback, UUID lease,
                 OffsetDateTime expiresAt, boolean query, int attempts) {}
    public record View(long shipmentRevision, String carrierCode, String trackingNumber, String status,
                       String dataState, OffsetDateTime lastEventAt, OffsetDateTime lastCheckedAt,
                       boolean stale, List<TrackingProvider.Event> events) {}
    private record Saved(String status, OffsetDateTime eventAt, OffsetDateTime checkedAt, boolean failed) {}

    private final JdbcTemplate jdbc;
    private final OrderAccessService access;
    private final OrderReservationService reservations;
    private final ApplicationEventPublisher events;
    @Value("${app.shipment.tracking.enabled:false}")
    private boolean enabled;
    @Value("${app.shipment.tracking.notify-enabled:false}")
    private boolean notifyEnabled;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void registered(Registered event) {
        if (!enabled) return;
        var order = event.order();
        var shipment = order.getShipment();
        jdbc.update("""
            update shipment_tracking set next_check_at=null where order_id=? and revision<>?
            """, order.getId(), revision(order));
        jdbc.update("""
            insert into shipment_tracking(order_id,revision,carrier_id,tracking_number,next_check_at)
            values (?,?,?,?,now()) on conflict do nothing
            """, order.getId(), revision(order), carrier(shipment.getCarrierCode()), shipment.getTrackingNumber());
    }

    @Transactional
    public void callback(UUID token, String carrier, String number) {
        if (!enabled) throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND);
        jdbc.update("""
            update shipment_tracking t set pending=true,
                next_check_at=least(t.next_check_at, greatest(now(),coalesce(t.checked_at,now())+interval '30 seconds'))
            from orders o
            where o.id=t.order_id and coalesce(o.shipment_revision,1)=t.revision
              and t.callback_token=? and t.carrier_id=? and t.tracking_number=?
              and t.next_check_at is not null and not t.pending and t.status<>'DELIVERED'
            """, token, carrier, number);
    }

    @Transactional
    public Claim claim() {
        // 활성화 전 등록된 운송장도 한 번만 추적 대상으로 등록한다.
        jdbc.update("""
            insert into shipment_tracking(order_id,revision,carrier_id,tracking_number,next_check_at)
            select o.id,coalesce(o.shipment_revision,1),
                case o.shipment_carrier_code when 'CJ_LOGISTICS' then 'kr.cjlogistics' else 'kr.hanjin' end,
                o.shipment_tracking_number,now() from orders o
            where o.order_status in ('PAID','PURCHASE_CONFIRMED')
              and o.shipment_registered_at>=now()-interval '30 days'
              and o.shipment_carrier_code in ('CJ_LOGISTICS','HANJIN') and o.shipment_tracking_number is not null
              and not exists (select 1 from shipment_tracking t
                  where t.order_id=o.id and t.revision=coalesce(o.shipment_revision,1))
            order by o.id limit 20 on conflict do nothing
            """);
        UUID lease = UUID.randomUUID();
        return jdbc.query("""
            with due as (
                select t.order_id,t.revision,
                    (t.pending or t.lease_token is not null) as query_required
                from shipment_tracking t
                join orders o on o.id=t.order_id and coalesce(o.shipment_revision,1)=t.revision
                where t.next_check_at<=now() and (t.lease_until is null or t.lease_until<=now())
                  and t.created_at>now()-interval '30 days' and o.order_status in ('PAID','PURCHASE_CONFIRMED')
                order by t.next_check_at,t.order_id limit 1 for update of t skip locked
            )
            update shipment_tracking t set lease_token=?,lease_until=now()+interval '1 minute',pending=false
            from due where t.order_id=due.order_id and t.revision=due.revision
            returning t.*, due.query_required
            """, (rs, row) -> new Claim(rs.getLong("order_id"), rs.getLong("revision"), rs.getString("carrier_id"),
            rs.getString("tracking_number"), rs.getObject("callback_token", UUID.class), lease,
            rs.getObject("expires_at", OffsetDateTime.class), rs.getBoolean("query_required")
                || rs.getObject("checked_at") == null || rs.getObject("expires_at") == null
                || rs.getBoolean("failed"), rs.getInt("attempts")), lease).stream().findFirst().orElse(null);
    }

    @Transactional
    public void finish(Claim claim, OffsetDateTime expiration, TrackingProvider.Snapshot result, boolean failed) {
        var order = reservations.lockOrder(claim.orderId());
        if (order.getShipment() == null || revision(order) != claim.revision()
            || !order.getShipment().getTrackingNumber().equals(claim.number())
            || !carrier(order.getShipment().getCarrierCode()).equals(claim.carrier())
            || !List.of("PAID", "PURCHASE_CONFIRMED").contains(order.getOrderStatus().name())) return;

        var state = jdbc.query("""
            select status,event_at,checked_at,failed from shipment_tracking
            where order_id=? and revision=? and lease_token=? and lease_until>clock_timestamp() for update
            """, ShipmentTracking::saved, claim.orderId(), claim.revision(), claim.lease())
            .stream().findFirst().orElse(null);
        if (state == null) return;
        var latest = result == null ? null : result.latest();
        String status = state.status();
        OffsetDateTime eventAt = state.eventAt();
        if (result != null) {
            for (var event : result.events()) {
                jdbc.update("""
                    insert into shipment_tracking_events(order_id,revision,occurred_at,status)
                    values (?,?,?,?) on conflict do nothing
                    """, claim.orderId(), claim.revision(), event.occurredAt(), event.status().name());
            }
            if (latest != null && latest.status() != TrackingProvider.Status.UNKNOWN && !status.equals("DELIVERED")
                && (eventAt == null || !latest.occurredAt().isBefore(eventAt))) {
                String previous = status;
                status = latest.status().name();
                eventAt = latest.occurredAt();
                if (notifyEnabled && !previous.equals(status)) {
                    if (status.equals("OUT_FOR_DELIVERY"))
                        events.publishEvent(new TradeChanged(order, NotificationType.SHIPMENT_OUT_FOR_DELIVERY));
                    if (status.equals("DELIVERED"))
                        events.publishEvent(new TradeChanged(order, NotificationType.SHIPMENT_DELIVERED));
                }
            }
        }
        int attempts = failed ? claim.attempts() + 1 : 0;
        long delay = Math.min(3600, 60L << Math.min(attempts, 6));
        jdbc.update("""
            update shipment_tracking set status=?,event_at=?,checked_at=case when ? then now() else checked_at end,
              expires_at=?,failed=?,attempts=?,pending=(pending or ?),lease_token=null,lease_until=null,
              next_check_at=case when ?='DELIVERED' then null
                when ? then now()+make_interval(secs=>cast(? as double precision))
                when pending then now()+interval '30 seconds' else cast(? as timestamptz)-interval '24 hours' end
            where order_id=? and revision=?
            """, status, eventAt, result != null, expiration, failed, attempts, failed, status,
            failed, delay, expiration, claim.orderId(), claim.revision());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View get(long actor, String orderId) {
        var order = access.read(actor, orderId);
        var shipment = order.getShipment();
        if (shipment == null) throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND);
        long revision = revision(order);
        var state = jdbc.query("""
            select status,event_at,checked_at,failed from shipment_tracking where order_id=? and revision=?
            """, ShipmentTracking::saved, order.getId(), revision).stream().findFirst().orElse(null);
        if (state == null) return new View(revision, shipment.getCarrierCode(), shipment.getTrackingNumber(),
            "UNKNOWN", enabled ? "PENDING" : "UNAVAILABLE", null, null, false, List.of());
        var history = jdbc.query("""
            select status,occurred_at from shipment_tracking_events where order_id=? and revision=?
            order by occurred_at desc,status limit 50
            """, (rs, row) -> new TrackingProvider.Event(TrackingProvider.Status.valueOf(rs.getString(1)),
            rs.getObject(2, OffsetDateTime.class)), order.getId(), revision);
        boolean stale = state.eventAt() != null && (state.failed() || !enabled);
        String data = state.eventAt() != null ? "AVAILABLE" : state.failed() || !enabled ? "UNAVAILABLE" : "PENDING";
        return new View(revision, shipment.getCarrierCode(), shipment.getTrackingNumber(), state.status(), data,
            state.eventAt(), state.checkedAt(), stale, history);
    }

    private static Saved saved(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Saved(rs.getString(1), rs.getObject(2, OffsetDateTime.class),
            rs.getObject(3, OffsetDateTime.class), rs.getBoolean(4));
    }

    private static long revision(PurchaseOrder order) {
        var value = order.getShipment().getRevision();
        return value == null ? 1L : value;
    }

    private static String carrier(String code) {
        return switch (code) {
            case "CJ_LOGISTICS" -> "kr.cjlogistics";
            case "HANJIN" -> "kr.hanjin";
            default -> throw new IllegalArgumentException("지원하지 않는 택배사입니다.");
        };
    }
}
