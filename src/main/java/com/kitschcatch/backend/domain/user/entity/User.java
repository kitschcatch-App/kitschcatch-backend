package com.kitschcatch.backend.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
	name = "users",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_users_auth_provider_provider_user_id",
		columnNames = {"auth_provider", "provider_user_id"}
	)
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(nullable = false, unique = true, length = 255)
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
}
