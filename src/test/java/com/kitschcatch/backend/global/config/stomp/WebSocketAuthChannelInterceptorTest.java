package com.kitschcatch.backend.global.config.stomp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class WebSocketAuthChannelInterceptorTest {

	private JwtTokenProvider jwtTokenProvider;
	private ChatRoomRepository chatRoomRepository;
	private WebSocketAuthChannelInterceptor interceptor;
	private MessageChannel channel;

	@BeforeEach
	void setUp() {
		jwtTokenProvider = mock(JwtTokenProvider.class);
		chatRoomRepository = mock(ChatRoomRepository.class);
		interceptor = new WebSocketAuthChannelInterceptor(jwtTokenProvider, chatRoomRepository);
		channel = mock(MessageChannel.class);
	}

	@Test
	@DisplayName("CONNECT 프레임의 Bearer 토큰은 STOMP Principal로 등록된다")
	void connectWithBearerTokenSetsPrincipal() {
		when(jwtTokenProvider.parseAccessToken("valid-token")).thenReturn(new AuthenticatedUser(1L));

		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
		accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
		accessor.setLeaveMutable(true);

		Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
		Message<?> interceptedMessage = interceptor.preSend(message, channel);
		StompHeaderAccessor interceptedAccessor = MessageHeaderAccessor.getAccessor(
			interceptedMessage,
			StompHeaderAccessor.class
		);

		assertThat(interceptedAccessor).isNotNull();
		assertThat(interceptedAccessor.getUser()).isInstanceOf(Authentication.class);
		Authentication authentication = (Authentication) interceptedAccessor.getUser();
		assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedUser(1L));
	}

	@Test
	@DisplayName("CONNECT 프레임에 Authorization 헤더가 없으면 인증 예외가 발생한다")
	void connectWithoutAuthorizationThrowsException() {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
		accessor.setLeaveMutable(true);

		Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

		assertThatThrownBy(() -> interceptor.preSend(message, channel))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.INVALID_AUTH_TOKEN);
	}

	@Test
	@DisplayName("채팅방 참여자는 실시간 구독을 시작할 수 있다")
	void subscribeByParticipantPasses() {
		when(chatRoomRepository.existsParticipant(100L, 1L)).thenReturn(true);

		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		accessor.setDestination("/sub/chat-rooms/100");
		accessor.setUser(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(1L), null, List.of()));
		accessor.setLeaveMutable(true);

		Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
		Message<?> interceptedMessage = interceptor.preSend(message, channel);

		assertThat(interceptedMessage).isSameAs(message);
		verify(chatRoomRepository).existsParticipant(100L, 1L);
	}

	@Test
	@DisplayName("채팅방 참여자가 아니면 실시간 구독을 시작할 수 없다")
	void subscribeByOutsiderThrowsException() {
		when(chatRoomRepository.existsParticipant(100L, 3L)).thenReturn(false);

		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		accessor.setDestination("/sub/chat-rooms/100");
		accessor.setUser(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(3L), null, List.of()));
		accessor.setLeaveMutable(true);

		Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

		assertThatThrownBy(() -> interceptor.preSend(message, channel))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
	}
}
