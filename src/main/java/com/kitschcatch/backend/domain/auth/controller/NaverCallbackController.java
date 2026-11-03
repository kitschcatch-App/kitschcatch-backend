// 앱 링크로 전달되지 못한 네이버 인증 콜백에 비밀값을 표시하지 않는 안내를 제공한다.
package com.kitschcatch.backend.domain.auth.controller;

import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NaverCallbackController {
	@GetMapping(value = "/api/auth/naver/callback", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> callback() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.header("Referrer-Policy", "no-referrer")
			.header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
			.header("X-Content-Type-Options", "nosniff")
			.contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
			.body("""
				<!doctype html>
				<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
				<title>키치캐치 로그인</title></head><body><main>
				<h1>키치캐치 앱으로 돌아가 주세요.</h1>
				<p>로그인 결과를 앱으로 전달하지 못했습니다. 앱으로 돌아가 로그인을 다시 시작해 주세요.</p>
				</main></body></html>
				""");
	}
}
