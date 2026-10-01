// 후기 SQL의 재실행·이력 보존·유일성·당사자 관계와 실패 롤백을 검증한다.
package com.kitschcatch.backend.domain.review;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ISSUE56_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class TransactionReviewMigrationTest {
    void legacy(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            s.execute("INSERT INTO users VALUES(1),(2),(3)");
            s.execute("CREATE TABLE orders(id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,seller_id BIGINT NOT NULL,order_status VARCHAR(30))");
            s.execute("INSERT INTO orders VALUES(1,1,2,'PURCHASE_CONFIRMED'),(2,1,3,'PAID')");
        }
    }
    String insert(int order, int author, int recipient, int buyer, int seller, int rating, String content) {
        return "INSERT INTO transaction_reviews(order_id,author_id,recipient_id,buyer_id,seller_id,rating,content) VALUES("
            + order + "," + author + "," + recipient + "," + buyer + "," + seller + "," + rating + ",'" + content + "')";
    }
    @Test void repeatedMigrationPreservesRowsAndEnforcesDuplicateRatingAndContentConstraints() throws Exception {
        try (var db = new PostgresReviewTestDatabase(); var c = db.connect(); var s = c.createStatement()) {
            legacy(c); PostgresReviewTestDatabase.migrate(c);
            s.execute(insert(1, 1, 2, 1, 2, 5, "평가"));
            PostgresReviewTestDatabase.migrate(c);
            try (var r = s.executeQuery("SELECT rating,content FROM transaction_reviews")) {
                r.next(); assertThat(r.getInt(1)).isEqualTo(5); assertThat(r.getString(2)).isEqualTo("평가");
            }
            assertThatThrownBy(() -> s.execute(insert(1, 1, 2, 1, 2, 4, "중복")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");
            for (int rating : new int[]{0, 6})
                assertThatThrownBy(() -> s.execute(insert(1, 2, 1, 1, 2, rating, "평가")))
                    .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
            assertThatThrownBy(() -> s.execute(insert(1, 2, 1, 1, 2, 5, " ")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
            s.execute(insert(1, 2, 1, 1, 2, 1, "상대 평가"));
            try (var r = s.executeQuery("SELECT count(*) FROM transaction_reviews")) { r.next(); assertThat(r.getInt(1)).isEqualTo(2); }
            try (var r = s.executeQuery("SELECT to_regclass('ix_reviews_recipient_created_id')")) { r.next(); assertThat(r.getString(1)).isNotNull(); }
        }
    }
    @Test void fabricatedParticipantsMissingRelationsAndDeletionAreRejected() throws Exception {
        try (var db = new PostgresReviewTestDatabase(); var c = db.connect(); var s = c.createStatement()) {
            legacy(c); PostgresReviewTestDatabase.migrate(c);
            assertThatThrownBy(() -> s.execute(insert(1, 1, 3, 1, 3, 5, "조작")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23503");
            for (String sql : new String[]{insert(1, 3, 2, 1, 2, 5, "타인"), insert(1, 1, 1, 1, 2, 5, "본인")})
                assertThatThrownBy(() -> s.execute(sql)).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
            assertThatThrownBy(() -> s.execute(insert(999, 1, 2, 1, 2, 5, "없는 주문")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23503");
            s.execute(insert(1, 1, 2, 1, 2, 5, "평가"));
            for (String sql : new String[]{"DELETE FROM orders WHERE id=1", "DELETE FROM users WHERE id=2", "UPDATE orders SET seller_id=3 WHERE id=1"})
                assertThatThrownBy(() -> s.execute(sql)).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23503");
        }
    }
    @Test void missingPrerequisiteRollsBackMigrationChanges() throws Exception {
        try (var db = new PostgresReviewTestDatabase(); var c = db.connect(); var s = c.createStatement()) {
            legacy(c); s.execute("DROP TABLE users");
            assertThatThrownBy(() -> PostgresReviewTestDatabase.migrate(c)).isInstanceOf(SQLException.class);
            try (var r = s.executeQuery("SELECT to_regclass('uk_orders_review_participants'),to_regclass('transaction_reviews')")) {
                r.next(); assertThat(r.getString(1)).isNull(); assertThat(r.getString(2)).isNull();
            }
        }
    }
}
