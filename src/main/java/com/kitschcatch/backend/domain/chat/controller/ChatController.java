package com.kitschcatch.backend.domain.chat.controller;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomListResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.chat.service.ChatService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat-rooms")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    // 채팅방 목록 조회 API
    // 현재 로그인 사용자가 참여 중인  모든 채팅방 목록을 조회한다.
    @GetMapping
    public ApiResponse<List<ChatRoomListResponse>> getMyChatRooms(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ApiResponse.ok(chatService.getMyChatRooms(user.userId()));
    }

    // 채팅방 생성 API
    @PostMapping
    public ApiResponse<ChatRoomResponse> createChatRoom(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateChatRoomRequest request
    ) {
        return ApiResponse.created(chatService.createChatRoom(user.userId(), request));
    }





}