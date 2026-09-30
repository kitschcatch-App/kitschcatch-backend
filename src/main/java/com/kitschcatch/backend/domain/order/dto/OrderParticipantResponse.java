// 거래 당사자의 식별자와 주문 당시 닉네임만 노출한다.
package com.kitschcatch.backend.domain.order.dto;

public record OrderParticipantResponse(Long id, String nickname) {}
