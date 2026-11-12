// 차단 트랜잭션에서 두 사용자의 채팅 푸시를 잠그고 발송 대상에서 제외한다.
package com.kitschcatch.backend.domain.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatPushCancellation {
    private final PushDeliveryRepository deliveries;

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelBetween(long first, long second) {
        deliveries.lockPendingChatBetween(first, second, NotificationType.CHAT_MESSAGE)
            .forEach(PushDelivery::skipBlocked);
    }
}
