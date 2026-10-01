// 거래 후속 처리 SQL의 기존 이력 보존·재실행·상태 제약과 원자적 실패를 검증한다.
package com.kitschcatch.backend.domain.order;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="ISSUE45_TEST_DB_URL",matches="jdbc:postgresql:.*")
class OrderLifecycleMigrationTest {
    void legacy(Connection c) throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE orders(id BIGINT PRIMARY KEY, order_status VARCHAR(30) NOT NULL CONSTRAINT orders_order_status_check CHECK(order_status IN ('PENDING','PAID','CANCELED','REFUNDED')), amount BIGINT NOT NULL CHECK(amount>0))");
            s.execute("CREATE TABLE payments(id BIGINT PRIMARY KEY,order_id BIGINT,canceled_at TIMESTAMP)");
            s.execute("INSERT INTO orders VALUES(1,'CANCELED',12000),(2,'PAID',13000)");
            s.execute("INSERT INTO payments VALUES(1,1,TIMESTAMP '2026-09-01 12:30:00')");
        }
    }
    @Test void cancellationBackfillAndNewValuesSurviveRerun() throws Exception {
        try(var db=new PostgresOrderLifecycleTestDatabase();var c=db.connect();var s=c.createStatement()) {
            legacy(c);PostgresOrderLifecycleTestDatabase.migrate(c);
            try(var r=s.executeQuery("SELECT canceled_at FROM orders WHERE id=1")) {r.next();assertThat(r.getTimestamp(1).toString()).startsWith("2026-09-01 12:30:00");}
            s.execute("UPDATE orders SET cancel_reason='보존',refund_id='REF-OLD',refund_amount=12000,refund_reason='환불',refund_status='COMPLETED',refund_requested_at=TIMESTAMP '2026-09-01 12:00:00',refund_completed_at=TIMESTAMP '2026-09-01 12:30:00' WHERE id=1");
            s.execute("UPDATE payments SET canceled_at=TIMESTAMP '2026-10-01 00:00:00'");
            PostgresOrderLifecycleTestDatabase.migrate(c);
            try(var r=s.executeQuery("SELECT cancel_reason,refund_id,canceled_at FROM orders WHERE id=1")) {
                r.next();assertThat(r.getString(1)).isEqualTo("보존");assertThat(r.getString(2)).isEqualTo("REF-OLD");
                assertThat(r.getTimestamp(3).toString()).startsWith("2026-09-01 12:30:00");
            }
        }
    }
    @Test void newStatusIsAcceptedWhileUnknownStatusAndDuplicateRefundAreRejected() throws Exception {
        try(var db=new PostgresOrderLifecycleTestDatabase();var c=db.connect();var s=c.createStatement()) {
            legacy(c);PostgresOrderLifecycleTestDatabase.migrate(c);
            s.execute("UPDATE orders SET order_status='PURCHASE_CONFIRMED' WHERE id=2");
            assertThatThrownBy(()->s.execute("UPDATE orders SET order_status='UNKNOWN' WHERE id=2")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.execute("UPDATE orders SET amount=-1 WHERE id=2")).isInstanceOf(SQLException.class);
            s.execute("UPDATE orders SET refund_id='REF-ONE' WHERE id=1");
            assertThatThrownBy(()->s.execute("UPDATE orders SET refund_id='REF-ONE' WHERE id=2")).isInstanceOf(SQLException.class);
        }
    }
    @Test void unsupportedExistingStatusRollsBackNewColumns() throws Exception {
        try(var db=new PostgresOrderLifecycleTestDatabase();var c=db.connect();var s=c.createStatement()) {
            legacy(c);s.execute("ALTER TABLE orders DROP CONSTRAINT orders_order_status_check");
            s.execute("UPDATE orders SET order_status='UNKNOWN'");
            assertThatThrownBy(()->PostgresOrderLifecycleTestDatabase.migrate(c)).isInstanceOf(SQLException.class);
            try(var r=s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='orders' AND column_name='cancel_reason'")) {
                r.next();assertThat(r.getInt(1)).isZero();
            }
        }
    }
    @Test void shipmentIsNotInventedForHistoricalOrders() throws Exception {
        try(var db=new PostgresOrderLifecycleTestDatabase();var c=db.connect();var s=c.createStatement()) {
            legacy(c);PostgresOrderLifecycleTestDatabase.migrate(c);PostgresOrderLifecycleTestDatabase.migrate(c);
            try(var r=s.executeQuery("SELECT count(*) FROM orders WHERE shipment_carrier_code IS NULL AND shipment_tracking_number IS NULL AND confirmed_at IS NULL")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(2);
            }
        }
    }
}
