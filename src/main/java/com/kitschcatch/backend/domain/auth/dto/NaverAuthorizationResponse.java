// 네이버 모바일 브라우저 인증 요청에 필요한 state와 URL을 반환한다.
package com.kitschcatch.backend.domain.auth.dto;

public record NaverAuthorizationResponse(String state, String authorizationUrl, long expiresIn) {}
