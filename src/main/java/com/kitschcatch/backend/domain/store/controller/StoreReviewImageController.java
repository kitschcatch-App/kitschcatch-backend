// 매장 리뷰 사진의 업로드 URL, 완료 처리와 공개 사진 모아보기를 제공한다.
package com.kitschcatch.backend.domain.store.controller;

import com.kitschcatch.backend.domain.store.dto.StoreReviewImageDtos.*;
import com.kitschcatch.backend.domain.store.service.StorePageQuery;
import com.kitschcatch.backend.domain.store.service.StoreReviewImageService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "매장 리뷰 사진")
@SecurityRequirement(name = "bearerAuth")
public class StoreReviewImageController {
    private final StoreReviewImageService images;

    @PostMapping("/stores/{storeId}/review-images/upload-urls")
    @Operation(summary = "리뷰 사진 업로드 URL 발급", description = "JPEG/PNG/WebP, 파일당 5MiB, 요청당 10장, 하루 100장. 반환된 headers로 PUT 후 완료 API를 호출합니다.")
    public ApiResponse<Uploads> upload(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long storeId, @Valid @RequestBody UploadRequest request) {
        return ApiResponse.success(images.upload(user.userId(), storeId, request));
    }

    @PostMapping("/store-review-images/{imageId}/complete")
    @Operation(summary = "리뷰 사진 업로드 완료", description = "실제 이미지 검증과 메타데이터 제거 후 연결 가능한 imageId를 반환합니다.")
    public ApiResponse<Completed> complete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long imageId) {
        return ApiResponse.success(images.complete(user.userId(), imageId));
    }

    @GetMapping("/stores/{storeId}/review-images")
    @Operation(summary = "매장 리뷰 사진 모아보기", description = "공개 리뷰의 사진을 리뷰 최신순, 사진 지정 순서로 반환합니다. URL은 만료되면 재조회합니다.")
    public ApiResponse<Gallery> gallery(@PathVariable long storeId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(images.gallery(storeId, new StorePageQuery(page, size)));
    }
}
