// 배송 정보에서 지원하는 택배사와 송장 번호를 검증한다.
package com.kitschcatch.backend.domain.order.dto;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
public record ShipmentRequest(
    @NotNull @Pattern(regexp="CJ_LOGISTICS|HANJIN") @Schema(description="지원 택배사: CJ_LOGISTICS, HANJIN") String carrierCode,
    @NotNull @Pattern(regexp="[0-9]{8,40}") @Schema(description="하이픈 없는 8~40자리 송장 번호") String trackingNumber
) {}
