package com.kitschcatch.backend.domain.chat.controller;

import com.kitschcatch.backend.domain.chat.dto.*;
import com.kitschcatch.backend.domain.chat.service.ChatMessageService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "채팅 메시지", description = "채팅 메시지 조회와 HTTP 기반 메시지 전송 API")
@SecurityRequirement(name = "bearerAuth")
public class ChatMessageController {

	private final ChatMessageService chatMessageService;
	private final SimpMessagingTemplate messagingTemplate;

	// 이전 대화 목록 불러오기
	@GetMapping
	@Operation(summary = "채팅 메시지 목록 조회", description = "인증 사용자가 참여 중인 채팅방의 이전 메시지를 조회합니다.")
	public ApiResponse<List<ChatMessageResponse>> getMessages(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "채팅방 ID", example = "1")
		@PathVariable Long chatRoomId
	) {
		return ApiResponse.success(chatMessageService.getMessages(user.userId(), chatRoomId));
	}

	/*// 텍스트 타입 메세지 전송
	@PostMapping("/text")
	@Operation(summary = "텍스트 메시지 전송", description = "HTTP 요청으로 텍스트 메시지를 저장하고 같은 채팅방 구독자에게 전달합니다.")
	public ApiResponse<ChatMessageResponse> sendTextMessage(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "채팅방 ID", example = "1")
		@PathVariable Long chatRoomId,
		@Valid @RequestBody SendTextMessageRequest request
	) {
		// HTTP로 저장한 텍스트 메시지도 같은 채팅방 구독자에게 즉시 전달한다.
		ChatMessageResponse response = chatMessageService.sendTextMessage(user.userId(), chatRoomId, request.content());
		messagingTemplate.convertAndSend("/sub/chat-rooms/" + chatRoomId, response);
		return ApiResponse.created(response);
	}*/

	@PostMapping("/images/upload-url")
	@Operation(
			summary = "채팅 이미지 업로드 URL 발급",
			description = "프론트에서 S3에 직접 이미지를 업로드할 수 있는 Presigned URL을 발급합니다."
	)
	public ApiResponse<ChatImageUploadUrl> createImageUploadUrl(
			@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long chatRoomId,
			@Valid @RequestBody ChatImageUploadUrlRequest request
	) {
		ChatImageUploadUrl response = chatMessageService.createChatImageUploadUrl(
				user.userId(),
				chatRoomId,
				request.originalFileName(),
				request.contentType()
		);

		return ApiResponse.created(response);
	}

	@PostMapping("/images/messages")
	@Operation(
			summary = "이미지 메시지 저장",
			description = "S3 업로드가 완료된 이미지를 채팅 메시지로 저장하고 구독자에게 전달합니다."
	)
	public ApiResponse<ChatMessageResponse> sendImageMessage(
			@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long chatRoomId,
			@Valid @RequestBody SendChatImageMessageRequest request
	) {
		ChatMessageResponse response = chatMessageService.sendImageMessage(
				user.userId(),
				chatRoomId,
				request.objectKey()
		);

		messagingTemplate.convertAndSend("/sub/chat-rooms/" + chatRoomId, response);

		return ApiResponse.created(response);
	}


}
