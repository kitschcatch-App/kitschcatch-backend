package com.kitschcatch.backend.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "채팅 메시지 읽음 처리 응답")
public record ChatReadResponse(

        @Schema(description = "채팅방 ID", example = "1")
        Long chatRoomId,

        @Schema(description = "읽음 처리한 사용자 ID", example = "2")
        Long readerId,

        @Schema(description = "읽음 처리된 메시지 ID 목록", example = "[1, 2, 3]")
        List<Long> readMessageIds,

        @Schema(description = "읽음 처리된 메시지 개수", example = "3")
        int readCount,

        @Schema(description = "읽음 처리 시각", example = "2026-06-04T14:30:00")
        LocalDateTime readAt
) {
}