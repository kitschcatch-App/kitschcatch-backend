package com.kitschcatch.backend.domain.auth.oidc;

public interface KakaoOidcTokenVerifier {

	KakaoOidcUser verify(String idToken, String nonce);
}
