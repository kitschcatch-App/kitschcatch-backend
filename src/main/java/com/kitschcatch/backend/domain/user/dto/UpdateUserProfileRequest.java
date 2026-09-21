// 프로필 수정에서 필드 생략과 명시적 null을 구분한다.
package com.kitschcatch.backend.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "사용자 프로필 수정 요청")
@JsonIgnoreProperties(ignoreUnknown = false)
public class UpdateUserProfileRequest {

	@Size(max = 50)
	private String nickname;
	@Size(max = 512)
	private String profileImageKey;
	private boolean nicknameProvided;
	private boolean profileImageKeyProvided;

	@JsonProperty("nickname")
	@Schema(description = "변경할 닉네임")
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
		return nicknameProvided || profileImageKeyProvided;
	}

	@JsonAnySetter
	public void rejectUnknownField(String name, Object value) {
		throw new IllegalArgumentException("지원하지 않는 프로필 필드입니다: " + name);
	}
}
