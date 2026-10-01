// 운영자의 수취인 연결과 판매자 본인의 연결 정보 조회 API를 제공한다.
package com.kitschcatch.backend.domain.order.controller;
import com.kitschcatch.backend.domain.order.settlement.*;
import com.kitschcatch.backend.domain.order.entity.SettlementRecipient;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @SecurityRequirement(name="bearerAuth")
@RequestMapping("/api/settlement/recipients/{sellerId}")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="COMMON_001/002: 회원 ID 또는 셀러 ID 입력 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="AUTH_004: 인증 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="SETTLEMENT_004: 연결 운영자 또는 조회 당사자 아님.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="SETTLEMENT_007: 판매자 또는 연결 정보 없음.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="SETTLEMENT_008: 토스 소유 관계·승인 상태 불일치 또는 기존 연결 변경.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="502",description="SETTLEMENT_006: 토스 조회 실패.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="SETTLEMENT_005: 지급대행 설정 누락.")
public class SettlementRecipientController {
    private final SettlementRecipientService service;
    public record Request(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,35}") String providerSellerId) {}
    public record Response(long sellerId,String providerSellerId,String refSellerId,LocalDateTime verifiedAt) {
        static Response from(SettlementRecipient r) { return new Response(r.getSellerId(),r.getProviderSellerId(),SettlementGateway.sellerReference(r.getSellerId()),r.getVerifiedAt()); }
    }
    @PutMapping @Operation(summary="토스 지급 수취인 연결",description="운영자가 토스에 등록한 APPROVED 셀러를 연결합니다. refSellerId는 KC + 회원 ID의 대문자 36진수(최소 5자리)입니다. 기존 Seller ID 교체는 허용하지 않습니다.")
    public ApiResponse<Response> bind(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Positive long sellerId,@Valid @RequestBody Request request) {
        return ApiResponse.success(Response.from(service.bind(user.userId(),sellerId,request.providerSellerId())));
    }
    @GetMapping @Operation(summary="지급 수취인 연결 조회",description="본인 또는 운영자만 조회하며 계좌 원문은 저장하거나 반환하지 않습니다.")
    public ApiResponse<Response> get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Positive long sellerId) {
        return ApiResponse.success(Response.from(service.get(user.userId(),sellerId)));
    }
}
