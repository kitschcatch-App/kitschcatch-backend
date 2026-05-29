package com.kitschcatch.backend.domain.chat.controller;

import com.kitschcatch.backend.domain.chat.dto.ChatMessageResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatReadResponse;
import com.kitschcatch.backend.domain.chat.dto.SendTextMessageRequest;
import com.kitschcatch.backend.domain.chat.service.ChatMessageService;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

	private final ChatMessageService chatMessageService;
	private final SimpMessagingTemplate messagingTemplate;

	// 텍스트 메세지 전송
	@MessageMapping("/chat-rooms/{chatRoomId}/messages/text")
	public void sendTextMessage(
		@DestinationVariable Long chatRoomId,
		@Valid SendTextMessageRequest request,
		Principal principal
	) {
		// 메세지 전송
		Long userId = extractUserId(principal);
		ChatMessageResponse response = chatMessageService.sendTextMessage(userId, chatRoomId, request.content());

		// 구독중인 클라이언트들에게 메세지 전달
		messagingTemplate.convertAndSend("/sub/chat-rooms/" + chatRoomId, response);
	}

	// 읽은 메세지 처리
	@MessageMapping("/chat-rooms/{chatRoomId}/read")
	public void markMessagesAsRead(@DestinationVariable Long chatRoomId, Principal principal) {

		Long userId = extractUserId(principal);

		ChatReadResponse response = chatMessageService.markMessagesAsRead(userId, chatRoomId);

		messagingTemplate.convertAndSend("/sub/chat-rooms/" + chatRoomId + "/read", response);
	}


	private Long extractUserId(Principal principal) {
		if (principal instanceof org.springframework.security.core.Authentication authentication) {
			Object authenticatedPrincipal = authentication.getPrincipal();
			if (authenticatedPrincipal instanceof AuthenticatedUser authenticatedUser) {
				return authenticatedUser.userId();
			}
		}
		throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
	}
}
