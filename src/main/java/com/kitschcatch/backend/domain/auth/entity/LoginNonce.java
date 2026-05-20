package com.kitschcatch.backend.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "login_nonces")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginNonce {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 64)
	private String nonceHash;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private LocalDateTime consumedAt;

	@Builder
	private LoginNonce(String nonceHash, LocalDateTime expiresAt) {
		this.nonceHash = nonceHash;
		this.expiresAt = expiresAt;
	}

	public boolean isConsumed() {
		return consumedAt != null;
	}

	public boolean isActive(LocalDateTime now) {
		return !isConsumed() && expiresAt.isAfter(now);
	}

	public void consume() {
		if (!isConsumed()) {
			this.consumedAt = LocalDateTime.now();
		}
	}
}
