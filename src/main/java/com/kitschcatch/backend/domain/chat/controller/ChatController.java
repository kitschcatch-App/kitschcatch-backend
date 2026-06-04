package com.kitschcatch.backend.domain.chat.controller;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomDetailResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomListResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.chat.service.ChatService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat-rooms")
@RequiredArgsConstructor
@Tag(name = "채팅방", description = "채팅방 생성, 목록 조회, 상세 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final ChatService chatService;

    @GetMapping
    @Operation(
            summary = "채팅방 목록 조회",
            description = "로그인한 사용자가 참여 중인 모든 채팅방 목록을 조회합니다."
    )
    public ApiResponse<List<ChatRoomListResponse>> getMyChatRooms(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ApiResponse.ok(chatService.getMyChatRooms(user.userId()));
    }

    @PostMapping
    @Operation(
            summary = "채팅방 생성",
            description = "판매 게시글에 대해 구매자와 판매자 사이의 채팅방을 생성합니다."
    )
    public ApiResponse<ChatRoomResponse> createChatRoom(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateChatRoomRequest request
    ) {
        return ApiResponse.created(chatService.createChatRoom(user.userId(), request));
    }

    @GetMapping("/{chatRoomId}")
    @Operation(
            summary = "채팅방 상세 조회",
            description = "채팅방 ID를 기준으로 채팅방과 판매 게시글 관련 상세 정보를 조회합니다."
    )
    public ApiResponse<ChatRoomDetailResponse> getChatRoom(
            @AuthenticationPrincipal AuthenticatedUser user,

            @Parameter(description = "채팅방 ID", example = "1")
            @PathVariable Long chatRoomId
    ) {
        return ApiResponse.ok(chatService.getChatRoom(user.userId(), chatRoomId));
    }
}