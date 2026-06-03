package com.kitschcatch.backend.domain.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomDetailResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomListResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
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

		// 기본 테스트 사용자는 buyer로 두고, 필요하면 개별 테스트에서 다른 사용자로 바꾼다.
		authentication = authenticate(1L);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("채팅방 생성 API는 생성된 채팅방 정보를 반환한다")
	void createChatRoomReturnsCreatedRoom() throws Exception {
		// 서비스가 반환한 채팅방 생성 결과가 공통 응답 형식으로 내려오는지 확인한다.
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
			.andExpect(jsonPath("$.data.postTitle").value("키링 판매"))
			.andExpect(jsonPath("$.data.buyerId").value(1))
			.andExpect(jsonPath("$.data.sellerId").value(2));
	}

	@Test
	@DisplayName("채팅방 목록 조회 API는 현재 사용자가 참여한 채팅방 목록을 반환한다")
	void getMyChatRoomsReturnsParticipatingRooms() throws Exception {
		// 목록 응답에는 상대방 정보와 마지막 메시지 정보가 포함되어야 한다.
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
			.andExpect(jsonPath("$.data[0].opponentId").value(4))
			.andExpect(jsonPath("$.data[0].opponentNickname").value("another-seller"))
			.andExpect(jsonPath("$.data[0].lastMessageContent").value("판매 중인가요?"))
			.andExpect(jsonPath("$.data[1].chatRoomId").value(102))
			.andExpect(jsonPath("$.data[1].opponentId").value(3))
			.andExpect(jsonPath("$.data[1].opponentNickname").value("another-buyer"))
			.andExpect(jsonPath("$.data[1].lastMessageContent").value("네 가능합니다."));
	}

	@Test
	@DisplayName("채팅방 생성 API는 postId가 없으면 검증 오류를 반환한다")
	void createChatRoomWithoutPostIdReturnsBadRequest() throws Exception {
		// 필수 요청값이 없으면 공통 검증 에러 형식이 내려와야 한다.
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
		// 비즈니스 예외도 공통 에러 응답 구조와 HTTP 상태를 따라야 한다.
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

	@Test
	@DisplayName("구매자는 채팅방 상세 정보를 조회할 수 있다")
	void 구매자는_채팅방_상세정보를_조회할_수_있다() throws Exception {
		// buyer가 상세 조회를 호출하면 상단 영역에 필요한 판매글 정보가 내려와야 한다.
		when(chatService.getChatRoom(1L, 100L))
			.thenReturn(new ChatRoomDetailResponse(
				100L,
				10L,
				"키링 판매",
				"https://cdn.test/posts/1/test-image.jpg"
			));

		mockMvc.perform(get("/api/chat-rooms/100").principal(authentication))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.chatRoomId").value(100))
			.andExpect(jsonPath("$.data.postId").value(10))
			.andExpect(jsonPath("$.data.postTitle").value("키링 판매"))
			.andExpect(jsonPath("$.data.postThumbnailImageUrl")
				.value("https://cdn.test/posts/1/test-image.jpg"));
	}

	@Test
	@DisplayName("판매자는 채팅방 상세 정보를 조회할 수 있다")
	void 판매자는_채팅방_상세정보를_조회할_수_있다() throws Exception {
		// seller 인증으로 요청해도 동일한 상세 응답을 반환해야 한다.
		UsernamePasswordAuthenticationToken sellerAuthentication = authenticate(2L);
		when(chatService.getChatRoom(2L, 100L))
			.thenReturn(new ChatRoomDetailResponse(
				100L,
				10L,
				"키링 판매",
				"https://cdn.test/posts/1/test-image.jpg"
			));

		mockMvc.perform(get("/api/chat-rooms/100").principal(sellerAuthentication))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.chatRoomId").value(100))
			.andExpect(jsonPath("$.data.postId").value(10))
			.andExpect(jsonPath("$.data.postTitle").value("키링 판매"))
			.andExpect(jsonPath("$.data.postThumbnailImageUrl")
				.value("https://cdn.test/posts/1/test-image.jpg"));
	}

	@Test
	@DisplayName("채팅방 참여자가 아니면 상세 조회에 실패한다")
	void 채팅방_참여자가_아니면_상세조회에_실패한다() throws Exception {
		// 참여하지 않은 사용자는 403 공통 에러 응답을 받아야 한다.
		UsernamePasswordAuthenticationToken strangerAuthentication = authenticate(3L);
		when(chatService.getChatRoom(3L, 100L))
			.thenThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

		mockMvc.perform(get("/api/chat-rooms/100").principal(strangerAuthentication))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("CHAT_003"))
			.andExpect(jsonPath("$.error.message").value("채팅방에 접근할 권한이 없습니다."));
	}

	@Test
	@DisplayName("존재하지 않는 채팅방이면 상세 조회에 실패한다")
	void 존재하지_않는_채팅방이면_상세조회에_실패한다() throws Exception {
		// 없는 채팅방 ID는 404 not found 에러로 매핑되어야 한다.
		when(chatService.getChatRoom(1L, 999L))
			.thenThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

		mockMvc.perform(get("/api/chat-rooms/999").principal(authentication))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("CHAT_001"))
			.andExpect(jsonPath("$.error.message").value("채팅방을 찾을 수 없습니다."));
	}

	private UsernamePasswordAuthenticationToken authenticate(Long userId) {
		// 테스트마다 요청 사용자 ID를 쉽게 바꿀 수 있도록 인증 객체를 생성한다.
		UsernamePasswordAuthenticationToken token =
			new UsernamePasswordAuthenticationToken(new AuthenticatedUser(userId), null, List.of());
		SecurityContextHolder.getContext().setAuthentication(token);
		return token;
	}
}
