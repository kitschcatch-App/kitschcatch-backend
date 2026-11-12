// 기존 매장에 검수한 상세 정보만 트랜잭션으로 반영한다.
package com.kitschcatch.backend.tools.storecontent;

import static com.kitschcatch.backend.tools.storecontent.StoreContentBatch.require;

import com.kitschcatch.backend.domain.store.entity.StoreAmenityCode;
import com.kitschcatch.backend.domain.store.entity.StoreAmenityStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;

final class StoreContentImporter {
    record Result(int stores, int descriptionsChanged, int photoListsChanged, int amenityListsChanged, UUID auditId) {}

    Result execute(Connection connection, String schema, StoreContentBatch batch, boolean apply) throws SQLException {
        require(schema.matches("[a-z_][a-z0-9_]{0,62}"), "명시적 소문자 스키마가 필요합니다.");
        require(connection.getAutoCommit() && connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"),
            "새 PostgreSQL 연결이 필요합니다.");
        String prefix = "\"" + schema + "\".";
        int previousIsolation = connection.getTransactionIsolation();
        boolean previousReadOnly = connection.isReadOnly();
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(!apply);
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '10s'");
                statement.execute("SET LOCAL statement_timeout = '60s'");
                if (apply) statement.execute("SET CONSTRAINTS " + prefix + "uk_store_images_sort DEFERRED");
            }
            int descriptions = 0, photoLists = 0, amenityLists = 0;
            for (var edit : batch.stores()) {
                String description = lookupStore(connection, prefix, edit, apply);
                if (edit.replaceDescription() && !Objects.equals(description, edit.description())) {
                    if (apply) updateDescription(connection, prefix, edit);
                    descriptions++;
                }
                if (edit.photos() != null && photosChanged(connection, prefix, edit)) {
                    if (apply) replacePhotos(connection, prefix, edit);
                    photoLists++;
                }
                if (edit.amenities() != null && amenitiesChanged(connection, prefix, edit)) {
                    if (apply) replaceAmenities(connection, prefix, edit);
                    amenityLists++;
                }
            }
            UUID audit = null;
            if (apply) {
                audit = audit(connection, prefix, batch);
                connection.commit();
            } else {
                connection.rollback();
            }
            return new Result(batch.stores().size(), descriptions, photoLists, amenityLists, audit);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                // 연결 상태가 불명확하면 auto-commit 전환으로 커밋될 위험을 피한다.
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        } finally {
            if (!connection.isClosed()) {
                connection.setAutoCommit(true);
                connection.setReadOnly(previousReadOnly);
                connection.setTransactionIsolation(previousIsolation);
            }
        }
    }

    private String lookupStore(Connection connection, String prefix, StoreContentBatch.StoreEdit edit, boolean apply)
        throws SQLException {
        try (var statement = connection.prepareStatement("SELECT name, address, description FROM " + prefix
            + "stores WHERE id = ?" + (apply ? " FOR NO KEY UPDATE" : ""))) {
            statement.setLong(1, edit.id());
            try (var rows = statement.executeQuery()) {
                require(rows.next() && edit.name().equals(rows.getString(1)) && edit.address().equals(rows.getString(2)),
                    "대상 매장이 없거나 매장명·주소가 일치하지 않습니다. storeId=" + edit.id());
                return rows.getString(3);
            }
        }
    }

    private void updateDescription(Connection connection, String prefix, StoreContentBatch.StoreEdit edit) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE " + prefix + "stores SET description = ? WHERE id = ?")) {
            statement.setString(1, edit.description());
            statement.setLong(2, edit.id());
            statement.executeUpdate();
        }
    }

    private boolean photosChanged(Connection connection, String prefix, StoreContentBatch.StoreEdit edit) throws SQLException {
        var current = new ArrayList<StoreContentBatch.PhotoRow>();
        try (var statement = connection.prepareStatement("SELECT object_key, sort_order, caption, credit, source_url FROM "
            + prefix + "store_images WHERE store_id = ? ORDER BY sort_order")) {
            statement.setLong(1, edit.id());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) current.add(new StoreContentBatch.PhotoRow(
                    rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getString(5)));
            }
        }
        return !current.equals(edit.photos().stream().map(StoreContentBatch.Photo::row).toList());
    }

    private void replacePhotos(Connection connection, String prefix, StoreContentBatch.StoreEdit edit) throws SQLException {
        var keys = connection.createArrayOf("text", edit.photos().stream().map(photo -> photo.row().key()).toArray());
        try (var statement = connection.prepareStatement("DELETE FROM " + prefix
            + "store_images WHERE store_id = ? AND NOT (object_key = ANY (?))")) {
            statement.setLong(1, edit.id());
            statement.setArray(2, keys);
            statement.executeUpdate();
        } finally {
            keys.free();
        }
        try (var statement = connection.prepareStatement("INSERT INTO " + prefix
            + "store_images(store_id, object_key, sort_order, caption, credit, source_url) VALUES (?,?,?,?,?,?) "
            + "ON CONFLICT (object_key) DO UPDATE SET sort_order=EXCLUDED.sort_order, caption=EXCLUDED.caption, "
            + "credit=EXCLUDED.credit, source_url=EXCLUDED.source_url")) {
            for (var photo : edit.photos()) {
                var row = photo.row();
                statement.setLong(1, edit.id());
                statement.setString(2, row.key());
                statement.setInt(3, row.order());
                statement.setString(4, row.caption());
                statement.setString(5, row.credit());
                statement.setString(6, row.sourceUrl());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private boolean amenitiesChanged(Connection connection, String prefix, StoreContentBatch.StoreEdit edit) throws SQLException {
        var current = new EnumMap<StoreAmenityCode, StoreContentBatch.Amenity>(StoreAmenityCode.class);
        try (var statement = connection.prepareStatement("SELECT code, status, note FROM " + prefix
            + "store_amenities WHERE store_id = ?")) {
            statement.setLong(1, edit.id());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) current.put(StoreAmenityCode.valueOf(rows.getString(1)),
                    new StoreContentBatch.Amenity(StoreAmenityStatus.valueOf(rows.getString(2)), rows.getString(3)));
            }
        }
        return !current.equals(edit.amenities());
    }

    private void replaceAmenities(Connection connection, String prefix, StoreContentBatch.StoreEdit edit) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM " + prefix + "store_amenities WHERE store_id = ?")) {
            statement.setLong(1, edit.id());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("INSERT INTO " + prefix
            + "store_amenities(store_id, code, status, note) VALUES (?,?,?,?)")) {
            for (var entry : edit.amenities().entrySet()) {
                statement.setLong(1, edit.id());
                statement.setString(2, entry.getKey().name());
                statement.setString(3, entry.getValue().status().name());
                statement.setString(4, entry.getValue().note());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private UUID audit(Connection connection, String prefix, StoreContentBatch batch) throws SQLException {
        UUID id = UUID.randomUUID();
        var ids = connection.createArrayOf("bigint", batch.stores().stream().map(StoreContentBatch.StoreEdit::id).toArray());
        try (var statement = connection.prepareStatement("INSERT INTO " + prefix
            + "store_content_imports(id, input_sha256, store_ids, reviewer, reviewed_at) VALUES (?,?,?,?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, batch.sha256());
            statement.setArray(3, ids);
            statement.setString(4, batch.reviewer());
            statement.setObject(5, batch.reviewedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        } finally {
            ids.free();
        }
        return id;
    }
}
