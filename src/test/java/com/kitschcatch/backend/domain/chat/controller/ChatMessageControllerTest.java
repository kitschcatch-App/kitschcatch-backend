package com.kitschcatch.backend.domain.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.multipart.MultipartFile;

class ChatMessageControllerTest {

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
	@DisplayName("텍스트 메시지 전송 API는 저장 후 구독자에게도 메시지를 전달한다")
	void sendTextMessageReturnsCreatedMessageAndBroadcasts() throws Exception {
		ChatMessageResponse response = new ChatMessageResponse(
			21L,
			100L,
			1L,
			"buyer",
			MessageType.TEXT,
			"문의드립니다.",
			null,
			false,
			LocalDateTime.of(2026, 5, 24, 20, 10)
		);
		when(chatMessageService.sendTextMessage(eq(1L), eq(100L), eq("문의드립니다.")))
			.thenReturn(response);

		mockMvc.perform(post("/api/chat-rooms/100/messages/text")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "content": "문의드립니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.messageId").value(21))
			.andExpect(jsonPath("$.data.content").value("문의드립니다."));

		verify(messagingTemplate).convertAndSend("/sub/chat-rooms/100", response);
	}

	@Test
	@DisplayName("텍스트 메시지 전송 API는 빈 내용을 검증 오류로 반환한다")
	void sendTextMessageWithBlankContentReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/chat-rooms/100/messages/text")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "content": " "
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("COMMON_001"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("content"));
	}

	@Test
	@DisplayName("이미지 메시지 전송 API는 저장 후 구독자에게도 이미지를 전달한다")
	void sendImageMessageReturnsCreatedMessageAndBroadcasts() throws Exception {
		MockMultipartFile file = new MockMultipartFile("image", "chat.png", "image/png", "image".getBytes());
		ChatMessageResponse response = new ChatMessageResponse(
			31L,
			100L,
			1L,
			"buyer",
			MessageType.IMAGE,
			null,
			"https://temp.kitschcatch.local/chat-images/sample-chat.png",
			false,
			LocalDateTime.of(2026, 5, 24, 20, 20)
		);
		when(chatMessageService.sendImageMessage(eq(1L), eq(100L), any(MultipartFile.class)))
			.thenReturn(response);

		mockMvc.perform(multipart("/api/chat-rooms/100/messages/images")
				.file(file)
				.principal(authentication))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.messageId").value(31))
			.andExpect(jsonPath("$.data.imageUrl").value("https://temp.kitschcatch.local/chat-images/sample-chat.png"));

		verify(messagingTemplate).convertAndSend("/sub/chat-rooms/100", response);
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
