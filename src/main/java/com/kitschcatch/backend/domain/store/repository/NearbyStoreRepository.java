// DB에서 반경 내 매장을 거리와 ID로 정렬하고 필요한 페이지만 조회한다.
package com.kitschcatch.backend.domain.store.repository;

import com.kitschcatch.backend.domain.store.dto.NearbyStoreResponse;
import com.kitschcatch.backend.domain.store.service.NearbyStoreQuery;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NearbyStoreRepository {
    private static final String QUERY = """
        SELECT id, name, address, latitude, longitude, distance_meters
        FROM (
            SELECT id, name, address, latitude, longitude,
                2 * :earthRadius * ASIN(SQRT(LEAST(1.0, GREATEST(0.0,
                    POWER(SIN(RADIANS(latitude - :latitude) / 2), 2)
                    + COS(RADIANS(:latitude)) * COS(RADIANS(latitude))
                    * POWER(SIN(RADIANS(longitude - :longitude) / 2), 2)
                )))) AS distance_meters
            FROM stores
            WHERE latitude BETWEEN :minLatitude AND :maxLatitude
        ) candidates
        WHERE distance_meters <= :radiusMeters
        ORDER BY distance_meters ASC, id ASC
        LIMIT :limit OFFSET :offset
        """;
    private final NamedParameterJdbcTemplate jdbc;

    public NearbyStoreRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NearbyStoreResponse> findNearby(NearbyStoreQuery query) {
        return jdbc.query(QUERY, Map.of(
            "earthRadius", NearbyStoreQuery.EARTH_RADIUS_METERS,
            "latitude", query.latitude(), "longitude", query.longitude(),
            "minLatitude", query.minLatitude(), "maxLatitude", query.maxLatitude(),
            "radiusMeters", query.radiusMeters() + NearbyStoreQuery.DISTANCE_EPSILON_METERS,
            "limit", query.page().size() + 1, "offset", query.page().offset()
        ), (rs, rowNum) -> new NearbyStoreResponse(rs.getLong("id"), rs.getString("name"),
            rs.getString("address"), rs.getDouble("latitude"), rs.getDouble("longitude"),
            Math.round(rs.getDouble("distance_meters"))));
    }
}
