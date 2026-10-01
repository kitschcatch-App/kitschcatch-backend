// 반품 검수와 수취인 SQL의 이력 보존·참조 제약·실패 롤백을 검증한다.
package com.kitschcatch.backend.domain.order;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="ISSUE45_TEST_DB_URL",matches="jdbc:postgresql:.*")
class OrderPayoutReturnMigrationTest {
    void apply(Connection c,String name) throws Exception {
        try(var source=getClass().getResourceAsStream("/db/manual/"+name);var s=c.createStatement()) {
            try {s.execute(new String(source.readAllBytes(),StandardCharsets.UTF_8));}
            catch(SQLException failure) {s.execute("ROLLBACK");throw failure;}
        }
    }
    @Test void extensionsPreserveReviewAndRecipientSnapshotsAndEnforceOwnershipConstraints() throws Exception {
        try(var db=new PostgresOrderLifecycleTestDatabase();var c=db.connect();var s=c.createStatement()) {
            new OrderLifecycleMigrationTest().legacy(c);PostgresOrderLifecycleTestDatabase.migrate(c);
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY); INSERT INTO users VALUES(10),(11)");
            s.execute("ALTER TABLE orders ADD CONSTRAINT orders_refund_status_check CHECK(refund_status IN ('REQUESTED','PROCESSING','COMPLETED'))");
            apply(c,"045_refund_review.sql");apply(c,"045_settlement_recipient.sql");
            s.execute("UPDATE orders SET refund_status='REJECTED',refund_review_reason='검수 증빙',refund_reviewed_by=10,refund_reviewed_at=TIMESTAMP '2026-10-01 12:00:00',settlement_destination='seller_1' WHERE id=1");
            s.execute("INSERT INTO settlement_recipients VALUES(10,'seller_1',11,CURRENT_TIMESTAMP)");
            apply(c,"045_refund_review.sql");apply(c,"045_settlement_recipient.sql");
            try(var r=s.executeQuery("SELECT refund_status,refund_review_reason,settlement_destination FROM orders WHERE id=1")) {
                r.next();assertThat(r.getString(1)).isEqualTo("REJECTED");assertThat(r.getString(2)).isEqualTo("검수 증빙");assertThat(r.getString(3)).isEqualTo("seller_1");
            }
            assertThatThrownBy(()->s.execute("INSERT INTO settlement_recipients VALUES(11,'seller_1',10,CURRENT_TIMESTAMP)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.execute("INSERT INTO settlement_recipients VALUES(99,'seller_2',10,CURRENT_TIMESTAMP)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.execute("UPDATE orders SET refund_status='UNKNOWN' WHERE id=1")).isInstanceOf(SQLException.class);
        }
    }
    @Test void unsupportedLegacyRefundStateRollsBackReviewColumns() throws Exception {
        try(var db=new PostgresOrderLifecycleTestDatabase();var c=db.connect();var s=c.createStatement()) {
            new OrderLifecycleMigrationTest().legacy(c);PostgresOrderLifecycleTestDatabase.migrate(c);
            s.execute("UPDATE orders SET refund_status='UNKNOWN' WHERE id=1");
            assertThatThrownBy(()->apply(c,"045_refund_review.sql")).isInstanceOf(SQLException.class);
            try(var r=s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='orders' AND column_name='refund_reviewed_by'")) {
                r.next();assertThat(r.getInt(1)).isZero();
            }
        }
    }
}
