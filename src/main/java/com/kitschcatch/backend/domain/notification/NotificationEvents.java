// 기존 채팅·결제 이벤트를 동기 처리하여 알림 저장도 함께 커밋하거나 롤백한다.
package com.kitschcatch.backend.domain.notification;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.order.entity.Payment;
import java.util.stream.Stream;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEvents {
  private final NotificationService service;

  public NotificationEvents(NotificationService service) {
    this.service = service;
  }

  public record ChatCreated(ChatMessage message) {}

  public record PaymentChanged(Payment payment, NotificationType type) {}

  @EventListener
  public void chat(ChatCreated event) {
    var message = event.message();
    var room = message.getChatRoom();
    long recipient =
        room.getBuyer().getId().equals(message.getSender().getId())
            ? room.getSeller().getId()
            : room.getBuyer().getId();
    service.record(
        recipient,
        "chat:" + message.getId(),
        NotificationType.CHAT_MESSAGE,
        room.getId().toString());
  }

  @EventListener
  public void payment(PaymentChanged event) {
    var order = event.payment().getOrder();
    // 거래의 판매자 스냅샷을 쓰며 두 계정의 잠금 순서를 고정한다.
    Stream.of(order.getUser().getId(), order.getSellerId())
        .distinct()
        .sorted()
        .forEach(
            userId ->
                service.record(
                    userId,
                    "payment:" + event.payment().getPaymentId() + ":" + event.type(),
                    event.type(),
                    order.getOrderNumber()));
  }
}
