package com.kitschcatch.backend.domain.user.entity;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
	name = "users",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_users_nickname_key", columnNames = "nickname_key"),
		@UniqueConstraint(name = "uk_users_username", columnNames = "username"),
		@UniqueConstraint(
		name = "uk_users_auth_provider_provider_user_id",
		columnNames = {"auth_provider", "provider_user_id"}
		)
	}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(name = "nickname_key", length = 50)
	private String nicknameKey;

	@Column(length = 30)
	private String username;

	@Column(length = 160)
	private String bio;

	@Column(name = "profile_image_key", length = 512)
	private String profileImageKey;

	@Column(name = "profile_registered_at")
	private Instant profileRegisteredAt;

	@Column(length = 255)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(name = "auth_provider", nullable = false, length = 30)
	private AuthProvider authProvider;

	@Column(name = "provider_user_id", nullable = false, length = 100)
	private String providerUserId;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private User(String nickname, String email, AuthProvider authProvider, String providerUserId) {
		this.nickname = nickname;
		this.email = email;
		this.authProvider = authProvider;
		this.providerUserId = providerUserId;
	}

	@Column(name = "withdrawn_at")
	private Instant withdrawnAt;

	public boolean isActive() { return withdrawnAt == null; }

	public void requireActive() {
		if (!isActive()) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
	}

	public void withdraw(Instant now) {
		if (!isActive()) return;
		withdrawnAt = now;
		nickname = "탈퇴한 사용자";
		nicknameKey = null;
		email = "withdrawn-" + UUID.randomUUID() + "@account.invalid";
		profileImageKey = null;
		profileRegisteredAt = null;
	}

	public boolean isProfileRegistered() {
		return profileRegisteredAt != null;
	}

	public void registerProfile(String nickname, String nicknameKey, String profileImageKey, Instant registeredAt) {
		this.nickname = nickname;
		this.nicknameKey = nicknameKey;
		this.profileImageKey = profileImageKey;
		this.profileRegisteredAt = registeredAt;
	}

	public void updateProfile(String nickname, String nicknameKey, String profileImageKey, boolean imageProvided) {
		this.nickname = nickname;
		this.nicknameKey = nicknameKey;
		if (imageProvided) {
			this.profileImageKey = profileImageKey;
		}
	}

	public void updatePublicProfile(String username, boolean usernameProvided, String bio, boolean bioProvided) {
		if (usernameProvided) {
			this.username = username;
		}
		if (bioProvided) {
			this.bio = bio;
		}
	}
}
