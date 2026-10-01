// 실제 029 SQL의 구 데이터 보완·재실행·부분 유일 제약과 트랜잭션 롤백을 검증한다.
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ISSUE54_TEST_DB_URL", matches = ".+")
class PaymentRecovery029PostgresTest {
    @Test
    void appliesRealSqlAndReexecutionPreservesRowsAndKeys() throws Exception {
        try (var db = new Issue54PostgresDatabase(); var connection = db.connect()) {
            baseline(connection);
            migrate(connection);
            assertThat(number(connection, "SELECT count(*) FROM payment_attempts")).isEqualTo(5);
            assertThat(number(connection, """
                SELECT count(*) FROM payment_attempts WHERE operation = 'CANCEL'
                AND sequence_number = 2 AND approval_attempt_id IS NOT NULL
                AND pg_idempotency_key = 'cancel-key-4'
                """)).isEqualTo(1);
            assertThat(number(connection, """
                SELECT count(*) FROM payments WHERE current_attempt_id = 'LEGACY-' || id
                """)).isEqualTo(4);
            execute(connection, "UPDATE payment_attempts SET check_count=7");
            migrate(connection);
            assertThat(number(connection, "SELECT count(*) FROM payment_attempts WHERE check_count=7")).isEqualTo(5);
            execute(connection, "UPDATE payments SET payment_status='FAILED' WHERE id=1");
            assertThatThrownBy(() -> execute(connection, """
                INSERT INTO payment_attempts(attempt_id,payment_id,sequence_number,operation,attempt_status,
                pg_order_id,amount,pg_idempotency_key,requested_at,next_check_at,created_at,updated_at)
                VALUES ('DUP',1,2,'CONFIRM','PREPARED','PG-duplicate',12000,'duplicate',now(),now(),now(),now())
                """)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void duplicateKeysStopBeforeDdlAndPreserveLegacyData() throws Exception {
        try (var db = new Issue54PostgresDatabase(); var connection = db.connect()) {
            baseline(connection);
            execute(connection, "UPDATE payments SET payment_key='key-3' WHERE id=2");
            assertThatThrownBy(() -> migrate(connection)).isInstanceOf(SQLException.class);
            assertUnchanged(connection);
            assertThat(number(connection, "SELECT count(*) FROM payments WHERE payment_key='key-3'")).isEqualTo(2);
        }
    }

    @Test
    void failureDuringBackfillRollsBackDdlAndUpdatesTogether() throws Exception {
        try (var db = new Issue54PostgresDatabase(); var connection = db.connect()) {
            baseline(connection);
            // 구 컬럼은 100자지만 새 PG 주문 번호 컬럼은 64자라 backfill 단계에서 실패한다.
            execute(connection, "UPDATE orders SET order_number=repeat('X',70) WHERE id=3");
            assertThatThrownBy(() -> migrate(connection)).isInstanceOf(SQLException.class);
            assertUnchanged(connection);
            assertThat(number(connection, "SELECT count(*) FROM orders WHERE length(order_number)=70")).isEqualTo(1);
        }
    }

    private void assertUnchanged(Connection connection) throws SQLException {
        assertThat(number(connection, "SELECT count(*) FROM payments")).isEqualTo(4);
        assertThat(number(connection, """
            SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema()
            AND table_name='payments' AND column_name='processing_operation'
            """)).isZero();
        assertThat(number(connection, """
            SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema()
            AND table_name IN ('payment_attempts','payment_webhook_events','payment_recovery_029_applied')
            """)).isZero();
    }

    private void baseline(Connection connection) throws SQLException {
        execute(connection, """
            CREATE TABLE posts(id bigint PRIMARY KEY);
            CREATE TABLE orders(id bigint PRIMARY KEY, order_number varchar(100), order_status varchar(30), amount bigint);
            CREATE TABLE payments(id bigint PRIMARY KEY,payment_id varchar(50),order_id bigint REFERENCES orders(id),
                payment_key varchar(255),amount bigint,payment_status varchar(30),created_at timestamp,approved_at timestamp,
                CONSTRAINT old_payment_status CHECK(payment_status IN ('READY','PROCESSING','SUCCESS','CANCELED')));
            INSERT INTO orders VALUES (1,'ORD-1','PENDING',12000),(2,'ORD-2','PENDING',12000),
                (3,'ORD-3','PAID',12000),(4,'ORD-4','PAID',12000);
            INSERT INTO payments VALUES
                (1,'PAY-1',1,NULL,12000,'READY',now(),NULL),
                (2,'PAY-2',2,'key-2',12000,'PROCESSING',now(),NULL),
                (3,'PAY-3',3,'key-3',12000,'SUCCESS',now(),now()),
                (4,'PAY-4',4,'key-4',12000,'PROCESSING',now(),now());
            """);
    }

    private void migrate(Connection connection) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/manual/029_payment_recovery.sql")) {
            try {
                execute(connection, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (SQLException exception) {
                execute(connection, "ROLLBACK");
                throw exception;
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
