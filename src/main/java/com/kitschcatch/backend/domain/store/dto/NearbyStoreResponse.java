// 주변 매장의 마커 정보와 반올림한 미터 단위 거리를 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record NearbyStoreResponse(Long id, String name, String address,
                                  double latitude, double longitude,
                                  @Schema(description = "구면 직선거리의 미터 반올림 값. 경로 이동 거리가 아닙니다.")
                                  long distanceMeters) {}
