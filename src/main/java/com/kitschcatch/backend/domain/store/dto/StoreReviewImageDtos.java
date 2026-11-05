// 리뷰 사진의 업로드 요청과 완료 상태 및 사진 모아보기 응답을 정의한다.
package com.kitschcatch.backend.domain.store.dto;

import com.kitschcatch.backend.domain.store.entity.StoreReviewImage.State;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class StoreReviewImageDtos {
    private StoreReviewImageDtos() {}

    public record File(@NotBlank @Size(max = 200) String fileName,
                       @NotBlank String contentType,
                       @Min(1) @Max(5242880) long fileSize) {}
    public record UploadRequest(@NotEmpty @Size(max = 10) List<@NotNull @Valid File> images) {}
    public record Upload(long imageId, String uploadUrl, Instant expiresAt, Map<String, String> headers) {}
    public record Uploads(List<Upload> images) {}
    public record Completed(long imageId, State state, Integer width, Integer height) {}
    public record GalleryImage(long id, long reviewId, long authorId, String authorNickname,
                               String imageUrl, int sortOrder, Instant createdAt) {}
    public record Gallery(List<GalleryImage> content, int page, int size, long totalElements, int totalPages) {}
}
