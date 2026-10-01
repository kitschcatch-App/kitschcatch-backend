package com.kitschcatch.backend.domain.chat.controller;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomDetailResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomListResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.chat.service.ChatService;
import com.kitschcatch.backend.domain.chat.service.ChatRoomListQuery;
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
            description = "본인이 참여하고 본인에게 삭제되지 않은 채팅방만 조회합니다. role은 BUYER 또는 SELLER이며 생략하면 전체입니다. postId는 참여 조건 안에서 적용됩니다. 마지막 메시지 시각·ID 역순, 빈 목록도 200입니다."
    )
    public ApiResponse<List<ChatRoomListResponse>> getMyChatRooms(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "참여 역할", schema = @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"BUYER", "SELLER"})) @RequestParam(required = false) String role,
            @Parameter(description = "상품 ID", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "integer", format = "int64", minimum = "1")) @RequestParam(required = false) String postId
    ) {
        return ApiResponse.ok(chatService.getMyChatRooms(user.userId(), ChatRoomListQuery.from(role, postId)));
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
