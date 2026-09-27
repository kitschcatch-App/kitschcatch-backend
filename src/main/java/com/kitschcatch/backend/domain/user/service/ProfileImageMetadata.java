// 업로드된 프로필 이미지의 실제 크기와 MIME 메타데이터를 전달한다.
package com.kitschcatch.backend.domain.user.service;

public record ProfileImageMetadata(String contentType, long contentLength) {
}
