// 프로필 이미지 URL 발급 요청의 필수값과 JSON 타입을 검증한다.
package com.kitschcatch.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "프로필 이미지 한 장의 업로드 URL 발급 요청")
@JsonDeserialize(using = CreateProfileImageUploadUrlRequest.Deserializer.class)
public record CreateProfileImageUploadUrlRequest(
	@NotBlank @Size(max = 255)
	@Schema(description = "원본 파일명. MIME과 확장자가 일치해야 합니다.", example = "profile.jpg")
	String fileName,
	@NotBlank @Size(max = 100)
	@Schema(description = "JPEG·PNG·WebP MIME. 앞뒤 공백과 대소문자를 정규화합니다.", example = "image/jpeg")
	String contentType,
	@NotNull @Positive
	@Schema(description = "실제 전송 바이트 수. 기본 최대 5,000,000바이트이며 서버 설정으로 변경할 수 있습니다.", example = "102400")
	Long fileSize
) {
	public static class Deserializer extends ValueDeserializer<CreateProfileImageUploadUrlRequest> {
		private static final Set<String> FIELDS = Set.of("fileName", "contentType", "fileSize");

		@Override
		public CreateProfileImageUploadUrlRequest deserialize(JsonParser parser, DeserializationContext context) {
			JsonNode root = context.readTree(parser);
			if (!root.isObject() || root.properties().stream().anyMatch(field -> !FIELDS.contains(field.getKey()))) {
				return context.reportInputMismatch(CreateProfileImageUploadUrlRequest.class, "지원하지 않는 요청 필드입니다.");
			}
			String fileName = text(root.get("fileName"), context);
			String contentType = text(root.get("contentType"), context);
			JsonNode size = root.get("fileSize");
			if (size != null && !size.isNull() && (!size.isIntegralNumber() || !size.canConvertToLong())) {
				return context.reportInputMismatch(CreateProfileImageUploadUrlRequest.class, "fileSize는 정수여야 합니다.");
			}
			return new CreateProfileImageUploadUrlRequest(fileName, contentType,
				size == null || size.isNull() ? null : size.longValue());
		}

		private String text(JsonNode node, DeserializationContext context) {
			if (node == null || node.isNull()) {
				return null;
			}
			if (!node.isString()) {
				return context.reportInputMismatch(CreateProfileImageUploadUrlRequest.class, "문자열 필드의 타입이 올바르지 않습니다.");
			}
			return node.stringValue();
		}
	}
}
