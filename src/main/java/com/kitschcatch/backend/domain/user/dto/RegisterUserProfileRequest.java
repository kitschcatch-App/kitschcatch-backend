// 최초 사용자 프로필 등록 요청의 필수값과 이미지 키를 검증한다.
package com.kitschcatch.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "사용자 프로필 등록 요청")
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = RegisterUserProfileRequest.Deserializer.class)
public record RegisterUserProfileRequest(
	@Schema(description = "사용자 닉네임. 앞뒤 공백 제거·NFC 정규화 후 1~50자", example = "키치수집가")
	@NotBlank
	String nickname,

	@Schema(description = "프로필 이미지 object key", example = "profiles/42/image.png")
	@Size(max = 512)
	String profileImageKey,

	@Schema(description = "선택적 아이디. 앞뒤 공백 제거·소문자 변환 후 영문, 숫자, 밑줄 3~30자")
	String username,

	@Schema(description = "선택적 한줄소개. 앞뒤 공백 제거·NFC 정규화 후 160자 이하")
	String bio
) {
	public static class Deserializer extends ValueDeserializer<RegisterUserProfileRequest> {
		@Override
		public RegisterUserProfileRequest deserialize(JsonParser parser, DeserializationContext context) {
			var root = context.readTree(parser);
			ProfileRequestJson.validate(root, context, RegisterUserProfileRequest.class);
			return new RegisterUserProfileRequest(
				ProfileRequestJson.text(root.get("nickname"), context, RegisterUserProfileRequest.class),
				ProfileRequestJson.text(root.get("profileImageKey"), context, RegisterUserProfileRequest.class),
				ProfileRequestJson.text(root.get("username"), context, RegisterUserProfileRequest.class),
				ProfileRequestJson.text(root.get("bio"), context, RegisterUserProfileRequest.class));
		}
	}
}
