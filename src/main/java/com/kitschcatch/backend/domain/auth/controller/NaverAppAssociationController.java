// 네이버 콜백을 서명된 iOS 앱으로 연결하는 공개 도메인 연결 문서를 제공한다.
package com.kitschcatch.backend.domain.auth.controller;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NaverAppAssociationController {

	private static final Pattern APP_ID = Pattern.compile("[A-Z0-9]{10}\\.[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*");
	private final String iosAppId;

	NaverAppAssociationController(@Value("${app.naver.ios-app-id:}") String iosAppId) {
		this.iosAppId = iosAppId.trim();
	}

	@GetMapping(value = "/.well-known/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
		if (!APP_ID.matcher(iosAppId).matches()) {
			return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
		}
		var detail = Map.of("appIDs", List.of(iosAppId), "components",
			List.of(Map.of("/", "/api/auth/naver/callback")));
		return ResponseEntity.ok(Map.of("applinks", Map.of("details", List.of(detail))));
	}
}
