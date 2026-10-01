// 프로필 수정에서 필드 생략과 명시적 null을 구분한다.
package com.kitschcatch.backend.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "사용자 프로필 수정 요청")
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = UpdateUserProfileRequest.Deserializer.class)
public class UpdateUserProfileRequest {

	private String nickname;
	@Size(max = 512)
	private String profileImageKey;
	private boolean nicknameProvided;
	private boolean profileImageKeyProvided;
	private String username;
	private String bio;
	private boolean usernameProvided;
	private boolean bioProvided;

	@JsonProperty("username")
	@Schema(description = "아이디. 생략하면 보존, null이면 해제한다. 공백 제거·소문자 변환 후 영문, 숫자, 밑줄 3~30자")
	public void setUsername(String username) {
		this.usernameProvided = true;
		this.username = username;
	}

	@JsonProperty("bio")
	@Schema(description = "한줄소개. 생략하면 보존하며 null 또는 공백이면 삭제한다. 정규화 후 160자 이하")
	public void setBio(String bio) {
		this.bioProvided = true;
		this.bio = bio;
	}

	public String username() { return username; }
	public String bio() { return bio; }
	public boolean usernameProvided() { return usernameProvided; }
	public boolean bioProvided() { return bioProvided; }

	@JsonProperty("nickname")
	@Schema(description = "변경할 닉네임. 앞뒤 공백 제거·NFC 정규화 후 1~50자")
	public void setNickname(String nickname) {
		this.nicknameProvided = true;
		this.nickname = nickname;
	}

	@JsonProperty("profileImageKey")
	@Schema(description = "변경할 프로필 이미지 key. null이면 이미지 연결을 해제한다.")
	public void setProfileImageKey(String profileImageKey) {
		this.profileImageKeyProvided = true;
		this.profileImageKey = profileImageKey;
	}

	public String nickname() {
		return nickname;
	}

	public String profileImageKey() {
		return profileImageKey;
	}

	public boolean nicknameProvided() {
		return nicknameProvided;
	}

	public boolean profileImageKeyProvided() {
		return profileImageKeyProvided;
	}

	public boolean hasChanges() {
		return nicknameProvided || profileImageKeyProvided || usernameProvided || bioProvided;
	}

	@JsonAnySetter
	public void rejectUnknownField(String name, Object value) {
		throw new IllegalArgumentException("지원하지 않는 프로필 필드입니다: " + name);
	}

	public static class Deserializer extends ValueDeserializer<UpdateUserProfileRequest> {
		@Override
		public UpdateUserProfileRequest deserialize(JsonParser parser, DeserializationContext context) {
			var root = context.readTree(parser);
			ProfileRequestJson.validate(root, context, UpdateUserProfileRequest.class);
			var request = new UpdateUserProfileRequest();
			if (root.has("nickname")) {
				request.setNickname(ProfileRequestJson.text(root.get("nickname"), context, UpdateUserProfileRequest.class));
			}
			if (root.has("profileImageKey")) {
				request.setProfileImageKey(ProfileRequestJson.text(root.get("profileImageKey"), context, UpdateUserProfileRequest.class));
			}
			if (root.has("username")) {
				request.setUsername(ProfileRequestJson.text(root.get("username"), context, UpdateUserProfileRequest.class));
			}
			if (root.has("bio")) {
				request.setBio(ProfileRequestJson.text(root.get("bio"), context, UpdateUserProfileRequest.class));
			}
			return request;
		}
	}
}
