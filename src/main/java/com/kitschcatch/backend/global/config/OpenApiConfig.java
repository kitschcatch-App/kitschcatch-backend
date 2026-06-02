package com.kitschcatch.backend.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("KitschCatch Backend API")
				.version("v1")
				.description("""
					KitschCatch 백엔드 REST API 문서입니다.

					인증 API를 제외한 REST API는 Authorization 헤더에 Bearer access token이 필요합니다.

					WebSocket/STOMP 참고 정보:
					- 연결 엔드포인트: /ws
					- SockJS 사용
					- publish prefix: /pub
					- subscribe prefix: /sub
					- 텍스트 메시지 발행: /pub/chat-rooms/{chatRoomId}/messages/text
					- 채팅방 구독: /sub/chat-rooms/{chatRoomId}
					- CONNECT 시 Authorization: Bearer <token> 헤더 필요
					"""))
			.components(new Components()
				.addSecuritySchemes("bearerAuth", new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")));
	}
}
