package com.kitschcatch.backend.global.config.stomp;

import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import com.kitschcatch.backend.global.security.JwtTokenProvider;

import java.security.Principal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * STOMP CONNECT 요청에 담긴 JWT 토큰을 검증하고,
 * WebSocket 세션의 Principal로 인증 정보를 등록하는 인터셉터
 */
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final Pattern CHAT_ROOM_SUBSCRIBE_PATTERN = Pattern.compile("^/sub/chat-rooms/(\\d+)$");

	private final JwtTokenProvider jwtTokenProvider;
	private final ChatRoomRepository chatRoomRepository;

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

		if (accessor == null) {
			return message;
		}

		if (StompCommand.CONNECT.equals(accessor.getCommand())) {
			// CONNECT 단계에서 JWT를 강제해서 이후 Principal이 비지 않도록 한다.
			String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
			if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
				throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
			}

			String token = authorization.substring(BEARER_PREFIX.length());
			AuthenticatedUser authenticatedUser = jwtTokenProvider.parseAccessToken(token);

			accessor.setUser(new UsernamePasswordAuthenticationToken(
				authenticatedUser,
				null,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))
			));
			return message;
		}

		if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
			// 실시간 메시지 구독도 채팅방 참여자만 허용한다.
			String destination = accessor.getDestination();
			Matcher matcher = CHAT_ROOM_SUBSCRIBE_PATTERN.matcher(destination == null ? "" : destination);

			if (matcher.matches()) {
				Long userId = extractUserId(accessor.getUser());
				Long chatRoomId = Long.valueOf(matcher.group(1));

				if (!chatRoomRepository.existsParticipant(chatRoomId, userId)) {
					throw new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
				}
			}
		}

		return message;
	}

	private Long extractUserId(Principal principal) {
		// STOMP 세션의 Principal에서 인증 사용자 ID를 꺼낸다.
		if (principal instanceof Authentication authentication
			&& authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser) {
			return authenticatedUser.userId();
		}
		throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
	}
}
