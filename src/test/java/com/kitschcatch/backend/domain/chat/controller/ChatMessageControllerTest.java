package com.kitschcatch.backend.domain.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.chat.dto.ChatImageUploadUrl;
import com.kitschcatch.backend.domain.chat.dto.ChatMessageResponse;
import com.kitschcatch.backend.domain.chat.entity.MessageType;
import com.kitschcatch.backend.domain.chat.service.ChatMessageService;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.exception.GlobalExceptionHandler;
import com.kitschcatch.backend.global.response.ResponseStatusSetterAdvice;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ChatMessageControllerTest {

	private static final String CHAT_IMAGE_UPLOAD_URL =
		"https://upload.example.com/chats/100/1/chat-image.png?signature=abc";
	private static final String CHAT_IMAGE_OBJECT_KEY = "chats/100/1/chat-image.png";
	private static final String CHAT_IMAGE_URL = "https://cdn.example.com/chats/100/1/chat-image.png";

	private ChatMessageService chatMessageService;
	private SimpMessagingTemplate messagingTemplate;
	private MockMvc mockMvc;
	private UsernamePasswordAuthenticationToken authentication;

	@BeforeEach
	void setUp() {
		chatMessageService = mock(ChatMessageService.class);
		messagingTemplate = mock(SimpMessagingTemplate.class);
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new ChatMessageController(chatMessageService, messagingTemplate))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new ResponseStatusSetterAdvice(), new GlobalExceptionHandler())
			.setValidator(validator)
			.build();

		authentication = new UsernamePasswordAuthenticationToken(new AuthenticatedUser(1L), null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("이전 메시지 조회 API는 저장된 메시지 목록을 반환한다")
	void getMessagesReturnsStoredMessages() throws Exception {
		when(chatMessageService.getMessages(1L, 100L)).thenReturn(List.of(
			new ChatMessageResponse(
				11L,
				100L,
				1L,
				"buyer",
				MessageType.TEXT,
				"안녕하세요",
				null,
				false,
				LocalDateTime.of(2026, 5, 24, 20, 0)
			)
		));

		mockMvc.perform(get("/api/chat-rooms/100/messages").principal(authentication))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].messageId").value(11))
			.andExpect(jsonPath("$.data[0].senderNickname").value("buyer"))
			.andExpect(jsonPath("$.data[0].content").value("안녕하세요"));
	}

	@Test
	@DisplayName("채팅 이미지 업로드 URL 발급 API는 생성 응답을 반환한다")
	void createImageUploadUrlReturnsCreatedResponse() throws Exception {
		when(chatMessageService.createChatImageUploadUrl(1L, 100L, "chat.png", "image/png"))
			.thenReturn(new ChatImageUploadUrl(
				CHAT_IMAGE_UPLOAD_URL,
				CHAT_IMAGE_OBJECT_KEY,
				CHAT_IMAGE_URL,
				300L
			));

		mockMvc.perform(post("/api/chat-rooms/100/messages/images/upload-url")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originalFileName": "chat.png",
					  "contentType": "image/png"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.uploadUrl").value(CHAT_IMAGE_UPLOAD_URL))
			.andExpect(jsonPath("$.data.objectKey").value(CHAT_IMAGE_OBJECT_KEY))
			.andExpect(jsonPath("$.data.imageUrl").value(CHAT_IMAGE_URL))
			.andExpect(jsonPath("$.data.expiresInSeconds").value(300));
	}

	@Test
	@DisplayName("이미지 메시지 저장 후 채팅방 구독자에게 WebSocket 메시지가 발행된다")
	void sendImageMessageReturnsCreatedMessageAndBroadcasts() throws Exception {
		ChatMessageResponse response = new ChatMessageResponse(
			21L,
			100L,
			1L,
			"buyer",
			MessageType.IMAGE,
			null,
			CHAT_IMAGE_URL,
			false,
			LocalDateTime.of(2026, 5, 24, 20, 10)
		);
		when(chatMessageService.sendImageMessage(eq(1L), eq(100L), eq(CHAT_IMAGE_OBJECT_KEY)))
			.thenReturn(response);

		mockMvc.perform(post("/api/chat-rooms/100/messages/images/messages")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "objectKey": "chats/100/1/chat-image.png"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.messageId").value(21))
			.andExpect(jsonPath("$.data.messageType").value("IMAGE"))
			.andExpect(jsonPath("$.data.imageUrl").value(CHAT_IMAGE_URL));

		ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
		verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), eq(response));
		assertThat(destinationCaptor.getValue()).isEqualTo("/sub/chat-rooms/100");
	}

	@Test
	@DisplayName("이미지 메시지 저장 API는 빈 objectKey를 검증 오류로 반환한다")
	void sendImageMessageWithBlankObjectKeyReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/chat-rooms/100/messages/images/messages")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "objectKey": " "
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("COMMON_001"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("objectKey"));
	}

	@Test
	@DisplayName("메시지 조회 API는 참여자가 아니면 접근 거부 에러를 반환한다")
	void getMessagesWithoutPermissionReturnsForbidden() throws Exception {
		when(chatMessageService.getMessages(1L, 100L))
			.thenThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

		mockMvc.perform(get("/api/chat-rooms/100/messages").principal(authentication))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("CHAT_003"))
			.andExpect(jsonPath("$.error.message").value("채팅방에 접근할 권한이 없습니다."));
	}
}
