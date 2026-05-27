package com.kitschcatch.backend.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 텍스트 메시지 전송 시 본문 내용을 받는 요청 DTO
public record SendTextMessageRequest(

	@NotBlank(message = "메시지 내용은 비어 있을 수 없습니다.")
	@Size(max = 1000, message = "메시지 내용은 1000자 이하여야 합니다.")
	String content
) {
}
