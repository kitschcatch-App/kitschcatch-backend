package com.kitschcatch.backend.domain.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomListResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.chat.service.ChatService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ChatControllerTest {

	private ChatService chatService;
	private MockMvc mockMvc;
	private UsernamePasswordAuthenticationToken authentication;

	@BeforeEach
	void setUp() {
		chatService = mock(ChatService.class);
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService))
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
	@DisplayName("채팅방 생성 API는 생성된 채팅방 정보를 반환한다")
	void createChatRoomReturnsCreatedRoom() throws Exception {
		when(chatService.createChatRoom(eq(1L), any(CreateChatRoomRequest.class)))
				.thenReturn(new ChatRoomResponse(
						100L,
						10L,
						"키링 판매",
						1L,
						2L,
						null,
						null,
						LocalDateTime.of(2026, 5, 22, 20, 30)
				));


		mockMvc.perform(post("/api/chat-rooms")
						.principal(authentication)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
					{
					  "postId": 10
					}
					"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.chatRoomId").value(100))
				.andExpect(jsonPath("$.data.postId").value(10))
				.andExpect(jsonPath("$.data.buyerNickname").value("buyer"))
				.andExpect(jsonPath("$.data.sellerNickname").value("seller"));
	}

	@Test
	@DisplayName("채팅방 목록 조회 API는 현재 사용자가 참여한 채팅방 목록을 반환한다")
	void getMyChatRoomsReturnsParticipatingRooms() throws Exception {
		when(chatService.getMyChatRooms(1L)).thenReturn(List.of(
				new ChatRoomListResponse(
						101L,
						4L,
						"another-seller",
						"판매 중인가요?",
						LocalDateTime.of(2026, 5, 27, 10, 0)
				),
				new ChatRoomListResponse(
						102L,
						3L,
						"another-buyer",
						"네 가능합니다.",
						LocalDateTime.of(2026, 5, 27, 9, 0)
				)
		));

		mockMvc.perform(get("/api/chat-rooms").principal(authentication))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].chatRoomId").value(101))
				.andExpect(jsonPath("$.data[0].opponentNickname").value("another-seller"))
				.andExpect(jsonPath("$.data[0].postThumbnailImageUrl").value("https://cdn.test/posts/11/thumbnail.png"))
				.andExpect(jsonPath("$.data[1].chatRoomId").value(102))
				.andExpect(jsonPath("$.data[1].opponentNickname").value("another-buyer"))
				.andExpect(jsonPath("$.data[1].postThumbnailImageUrl").value("https://cdn.test/posts/12/thumbnail.png"));
	}

	@Test
	@DisplayName("채팅방 생성 API는 postId가 없으면 검증 오류를 반환한다")
	void createChatRoomWithoutPostIdReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/chat-rooms")
						.principal(authentication)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("COMMON_001"))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("postId"));
	}

	@Test
	@DisplayName("채팅방 생성 API는 자기 게시글 문의 예외를 공통 에러 형식으로 반환한다")
	void createChatRoomSelfChatReturnsCommonError() throws Exception {
		when(chatService.createChatRoom(eq(1L), any(CreateChatRoomRequest.class)))
				.thenThrow(new BusinessException(ErrorCode.CHAT_ROOM_SELF_NOT_ALLOWED));

		mockMvc.perform(post("/api/chat-rooms")
						.principal(authentication)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
					{
					  "postId": 10
					}
					"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("CHAT_002"))
				.andExpect(jsonPath("$.error.message").value("자신의 판매 게시글에는 문의할 수 없습니다."));
	}
}
