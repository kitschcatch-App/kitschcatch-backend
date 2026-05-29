package com.kitschcatch.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ChatReadResponse(
        Long chatRoomId,
        Long readerId,
        List<Long> readMessageIds,
        int readCount,
        LocalDateTime readAt
) {
}