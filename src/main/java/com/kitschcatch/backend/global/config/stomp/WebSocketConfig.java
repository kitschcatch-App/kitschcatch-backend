package com.kitschcatch.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	@Override
	public void configureMessageBroker(MessageBrokerRegistry config) {
		config.enableSimpleBroker("/sub");
		config.setApplicationDestinationPrefixes("/pub");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry config) {
		config.addEndpoint("/ws-connect")
			.setAllowedOriginPatterns("*");
//			.withSockJS();
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		// 클라이언트가 서버로 보내는 STOMP 메시지에 대해 인증 인터셉터를 적용한다.
		// CONNECT 프레임에서 JWT를 읽어 Principal로 등록한다.
		registration.interceptors(webSocketAuthChannelInterceptor);
	}

}
