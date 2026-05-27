package com.kitschcatch.backend.domain.chat.controller;

import com.kitschcatch.backend.domain.chat.dto.ChatMessageResponse;
import com.kitschcatch.backend.domain.chat.dto.SendTextMessageRequest;
import com.kitschcatch.backend.domain.chat.service.ChatMessageService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chat-rooms/{chatRoomId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {

	private final ChatMessageService chatMessageService;
	private final SimpMessagingTemplate messagingTemplate;

	// 이전 대화 목록 불러오기
	@GetMapping
	public ApiResponse<List<ChatMessageResponse>> getMessages(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long chatRoomId
	) {
		return ApiResponse.success(chatMessageService.getMessages(user.userId(), chatRoomId));
	}

	// 텍스트 타입 메세지 전송
	@PostMapping("/text")
	public ApiResponse<ChatMessageResponse> sendTextMessage(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long chatRoomId,
		@Valid @RequestBody SendTextMessageRequest request
	) {
		// HTTP로 저장한 텍스트 메시지도 같은 채팅방 구독자에게 즉시 전달한다.
		ChatMessageResponse response = chatMessageService.sendTextMessage(user.userId(), chatRoomId, request.content());
		messagingTemplate.convertAndSend("/sub/chat-rooms/" + chatRoomId, response);
		return ApiResponse.created(response);
	}

	// 이미지 타입 메세지 전송
	@PostMapping("/images")
	public ApiResponse<ChatMessageResponse> sendImageMessage(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long chatRoomId,
		@RequestPart("image") MultipartFile imageFile
	) {
		// HTTP로 저장한 이미지 메시지도 같은 채팅방 구독자에게 즉시 전달한다.
		ChatMessageResponse response = chatMessageService.sendImageMessage(user.userId(), chatRoomId, imageFile);
		messagingTemplate.convertAndSend("/sub/chat-rooms/" + chatRoomId, response);
		return ApiResponse.created(response);
	}

}
