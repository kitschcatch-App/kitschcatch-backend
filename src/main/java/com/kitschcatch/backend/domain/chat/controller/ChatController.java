package com.kitschcatch.backend.domain.chat.controller;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.chat.service.ChatService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat-rooms")
@RequiredArgsConstructor
@Tag(name = "채팅방", description = "판매 게시글 기준 채팅방 생성 API")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final ChatService chatService;

    // 채팅방 생성
    @PostMapping
    @Operation(summary = "채팅방 생성", description = "판매 게시글에 대해 구매자와 판매자 사이의 채팅방을 생성합니다.")
    public ApiResponse<ChatRoomResponse> createChatRoom(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateChatRoomRequest request
    ) {
        return ApiResponse.created(chatService.createChatRoom(user.userId(), request));
    }


}
