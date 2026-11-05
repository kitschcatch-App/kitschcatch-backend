// 기존 구독에도 채팅 메시지와 읽음 이벤트를 전송하기 직전에 차단을 적용한다.
package com.kitschcatch.backend.global.config.stomp;

import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatOutboundInterceptor implements ExecutorChannelInterceptor {
    private final WebSocketAuthChannelInterceptor sessions;
    private final ChatRoomRepository rooms;

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        var headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) return message;
        Long room = ChatSocketDestinations.subscriptionRoom(SimpMessageHeaderAccessor.getDestination(headers));
        if (room == null) return null;
        Long user = sessions.userForSession(SimpMessageHeaderAccessor.getSessionId(headers));
        return user != null && rooms.canDeliverTo(room, user) ? message : null;
    }
}
