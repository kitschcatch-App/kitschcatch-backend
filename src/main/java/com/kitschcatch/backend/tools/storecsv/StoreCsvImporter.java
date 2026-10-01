// PostgreSQL의 매장·영업시간·검수 기록과 identity 보정을 원자적으로 적용한다.
package com.kitschcatch.backend.tools.storecsv;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;

public final class StoreCsvImporter {
    public record Result(boolean applied, int newStores, int existingStores, int hours, UUID auditId) { }

    public Result execute(Connection connection, String schema, StoreCsvBatch batch, boolean apply) throws SQLException {
        StoreCsvBatch.require(schema != null && schema.matches("[a-z_][a-z0-9_]{0,62}"), "명시적 소문자 스키마가 필요합니다.");
        StoreCsvBatch.require(connection.getAutoCommit(), "새 auto-commit 연결이 필요합니다.");
        StoreCsvBatch.require(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"), "PostgreSQL만 지원합니다.");
        String prefix = "\"" + schema + "\".";
        connection.setReadOnly(!apply);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '10s'");
                statement.execute("SET LOCAL statement_timeout = '60s'");
                // 관계와 누락 행을 보존하면서 일반 DML 및 다른 적재와의 경합을 차단한다.
                if (apply) statement.execute("LOCK TABLE " + prefix + "stores, " + prefix
                    + "store_business_hours IN SHARE ROW EXCLUSIVE MODE");
            }
            var existing = new HashSet<Long>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id FROM " + prefix + "stores")) {
                while (rows.next()) existing.add(rows.getLong(1));
            }
            var parents = new HashSet<>(existing);
            batch.stores().forEach(store -> parents.add(store.id()));
            for (var hours : batch.hours()) {
                StoreCsvBatch.require(parents.contains(hours.storeId()), "영업시간의 부모 매장이 DB와 매장 CSV에 없습니다.");
            }
            int updates = (int) batch.stores().stream().filter(store -> existing.contains(store.id())).count();
            if (!apply) {
                connection.rollback();
                return new Result(false, batch.stores().size() - updates, updates, batch.hours().size(), null);
            }
            upsert(connection, prefix, batch);
            UUID auditId = UUID.randomUUID();
            audit(connection, prefix, batch, auditId);
            if (!batch.stores().isEmpty()) repairSequence(connection, schema, prefix);
            connection.commit();
            return new Result(true, batch.stores().size() - updates, updates, batch.hours().size(), auditId);
        } catch (SQLException | RuntimeException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            throw exception;
        } finally {
            connection.setAutoCommit(true);
            connection.setReadOnly(false);
        }
    }

    private void upsert(Connection connection, String prefix, StoreCsvBatch batch) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO " + prefix + "stores "
            + "(id,name,region,address,latitude,longitude,phone) VALUES (?,?,?,?,?,?,?) "
            + "ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,region=EXCLUDED.region,address=EXCLUDED.address,"
            + "latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude,phone=EXCLUDED.phone")) {
            for (var store : batch.stores()) {
                statement.setLong(1, store.id());
                statement.setString(2, store.name());
                statement.setString(3, store.region());
                statement.setString(4, store.address());
                statement.setDouble(5, store.latitude());
                statement.setDouble(6, store.longitude());
                statement.setString(7, store.phone());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (var statement = connection.prepareStatement("INSERT INTO " + prefix + "store_business_hours "
            + "(store_id,day_of_week,open_time,close_time,closed) VALUES (?,?,?,?,?) "
            + "ON CONFLICT(store_id,day_of_week) DO UPDATE SET open_time=EXCLUDED.open_time,"
            + "close_time=EXCLUDED.close_time,closed=EXCLUDED.closed")) {
            for (var hours : batch.hours()) {
                statement.setLong(1, hours.storeId());
                statement.setString(2, hours.day().name());
                statement.setObject(3, hours.open(), java.sql.Types.TIME);
                statement.setObject(4, hours.close(), java.sql.Types.TIME);
                statement.setBoolean(5, hours.closed());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void audit(Connection connection, String prefix, StoreCsvBatch batch, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO " + prefix + "store_csv_imports "
            + "(id,source,reviewer,reviewed_at,stores_sha256,hours_sha256,store_ids,hour_keys) VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, batch.review().source());
            statement.setString(3, batch.review().reviewer());
            statement.setObject(4, OffsetDateTime.ofInstant(batch.review().reviewedAt(), ZoneOffset.UTC));
            statement.setString(5, batch.storesHash());
            statement.setString(6, batch.hoursHash());
            statement.setArray(7, connection.createArrayOf("bigint", batch.stores().stream().map(StoreCsvBatch.Store::id).toArray()));
            statement.setArray(8, connection.createArrayOf("text", batch.hours().stream().map(h -> h.storeId() + ":" + h.day()).toArray()));
            statement.executeUpdate();
        }
    }

    private void repairSequence(Connection connection, String schema, String prefix) throws SQLException {
        String sequence;
        try (var statement = connection.prepareStatement("SELECT quote_ident(n.nspname)||'.'||quote_ident(c.relname) "
            + "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
            + "JOIN pg_sequences s ON s.schemaname=n.nspname AND s.sequencename=c.relname "
            + "WHERE c.oid=pg_get_serial_sequence(?, 'id')::regclass "
            + "AND s.increment_by=1 AND s.cache_size=1 AND NOT s.cycle")) {
            statement.setString(1, "\"" + schema + "\".stores");
            try (var rows = statement.executeQuery()) {
                StoreCsvBatch.require(rows.next(), "stores identity sequence는 increment=1, cache=1, no cycle이어야 합니다.");
                sequence = rows.getString(1);
            }
        }
        try (var statement = connection.createStatement()) {
            // 값 조회 전에 sequence의 트랜잭션 잠금을 잡는다. setval은 롤백되지 않으므로 사용하지 않는다.
            statement.execute("ALTER SEQUENCE " + sequence + " CACHE 1");
            long next;
            try (var rows = statement.executeQuery("SELECT last_value,is_called FROM " + sequence)) {
                rows.next();
                next = rows.getBoolean(2) ? Math.addExact(rows.getLong(1), 1) : rows.getLong(1);
            }
            try (var rows = statement.executeQuery("SELECT COALESCE(MAX(id),0) FROM " + prefix + "stores")) {
                rows.next();
                next = Math.max(next, Math.addExact(rows.getLong(1), 1));
            }
            statement.execute("ALTER SEQUENCE " + sequence + " RESTART WITH " + next);
        }
    }
}
