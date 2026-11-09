// DB에서 반경 내 매장을 거리와 ID로 정렬하고 필요한 페이지만 조회한다.
package com.kitschcatch.backend.domain.store.repository;

import com.kitschcatch.backend.domain.store.dto.NearbyStoreResponse;
import com.kitschcatch.backend.domain.store.service.NearbyStoreQuery;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NearbyStoreRepository {
    private static final String QUERY = """
        SELECT id, name, address, latitude, longitude, distance_meters
        FROM (
            SELECT id, name, address, latitude, longitude,
                2.0 * CAST(:earthRadius AS DOUBLE PRECISION) * ASIN(SQRT(LEAST(1.0, GREATEST(0.0,
                    POWER(SIN(RADIANS(latitude - :latitude) / 2), 2)
                    + COS(RADIANS(:latitude)) * COS(RADIANS(latitude))
                    * POWER(SIN(RADIANS(longitude - :longitude) / 2), 2)
                )))) AS distance_meters
            FROM stores
            WHERE latitude BETWEEN :minLatitude AND :maxLatitude
            %s
        ) candidates
        WHERE distance_meters <= :radiusMeters
        ORDER BY distance_meters ASC, id ASC
        LIMIT :limit OFFSET :offset
        """;
    private final NamedParameterJdbcTemplate jdbc;

    public NearbyStoreRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NearbyStoreResponse> findNearby(NearbyStoreQuery query, Long excludedStoreId) {
        var parameters = new MapSqlParameterSource()
            .addValue("earthRadius", NearbyStoreQuery.EARTH_RADIUS_METERS)
            .addValue("latitude", query.latitude())
            .addValue("longitude", query.longitude())
            .addValue("minLatitude", query.minLatitude())
            .addValue("maxLatitude", query.maxLatitude())
            .addValue("radiusMeters", query.radiusMeters() + NearbyStoreQuery.DISTANCE_EPSILON_METERS)
            .addValue("limit", query.page().size() + 1)
            .addValue("offset", query.page().offset());
        String sql = QUERY.formatted(excludedStoreId == null ? "" : "AND id <> :excludedStoreId");
        if (excludedStoreId != null) {
            parameters.addValue("excludedStoreId", excludedStoreId);
        }
        return jdbc.query(sql, parameters, (rs, rowNum) -> new NearbyStoreResponse(rs.getLong("id"), rs.getString("name"),
            rs.getString("address"), rs.getDouble("latitude"), rs.getDouble("longitude"),
            Math.round(rs.getDouble("distance_meters")), false));
    }
}
