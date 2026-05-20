package com.kitschcatch.backend.domain.auth.oidc;

public record KakaoOidcUser(
	String subject,
	String email,
	String nickname
) {
}
