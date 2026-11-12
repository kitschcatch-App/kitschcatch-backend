// 구매자의 반품 사진 업로드 URL 발급과 실제 파일 검증 API를 제공한다.
package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.RefundImageDtos.*;
import com.kitschcatch.backend.domain.order.service.RefundImageService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders/{orderId}/refund-images")
@SecurityRequirement(name = "bearerAuth")
public class RefundImageController {
    private final RefundImageService images;

    @PostMapping("/upload-urls")
    @Operation(summary = "반품 사진 업로드 URL 발급", description = """
        환불 신청이 가능한 주문의 구매자 전용입니다. JPEG·PNG·WebP, 파일당 최대 5MiB, 요청당 최대 5장입니다.
        반환된 URL과 headers로 5분 내 PUT한 뒤 완료 API를 호출하세요. 미사용 사진은 발급 후 24시간이 지나면 정리합니다.
        """)
    public ApiResponse<Uploads> upload(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable String orderId, @Valid @RequestBody UploadRequest request) {
        return ApiResponse.success(images.upload(user.userId(), orderId, request));
    }

    @PostMapping("/{imageId}/complete")
    @Operation(summary = "반품 사진 업로드 검증", description = """
        주문의 구매자가 실제 파일을 검증하고 EXIF가 제거된 사진을 준비합니다. READY인 imageId를 반품 요청에 제출하세요.
        동일 사진의 완료 요청을 재시도할 수 있습니다.
        """)
    public ApiResponse<Completed> complete(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable String orderId, @PathVariable @Positive long imageId) {
        return ApiResponse.success(images.complete(user.userId(), orderId, imageId));
    }
}
