// 채팅·결제·거래 상태 이벤트를 동기 처리하여 알림 저장도 함께 커밋하거나 롤백한다.
package com.kitschcatch.backend.domain.notification;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationEvents {
  private final NotificationService service;

  private final boolean tradeEnabled;

  public NotificationEvents(NotificationService service,
      @Value("${app.notifications.trade-enabled:false}") boolean tradeEnabled) {
    this.service = service;
    this.tradeEnabled = tradeEnabled;
  }

  public record ChatCreated(ChatMessage message) {}

  public record PaymentChanged(Payment payment, NotificationType type) {}

  public record PaymentCanceled(Payment payment, OrderStatus previousOrderStatus) {}

  public record TradeChanged(PurchaseOrder order, NotificationType type) {}

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
    record(event.payment().getOrder(), event.type(),
        "payment:" + event.payment().getPaymentId() + ":" + event.type(), Audience.BOTH);
  }

  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void cancellation(PaymentCanceled event) {
    var order = event.payment().getOrder();
    if (order.getOrderStatus() == OrderStatus.REFUNDED) {
      if (event.previousOrderStatus() == OrderStatus.REFUNDED) return;
      if (tradeEnabled) {
        trade(new TradeChanged(order, NotificationType.REFUND_COMPLETED));
        return;
      }
    }
    payment(new PaymentChanged(event.payment(), NotificationType.PAYMENT_CANCELED));
  }

  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void trade(TradeChanged event) {
    if (!tradeEnabled) return;
    var order = event.order();
    var type = event.type();
    var orderKey = "order:" + order.getOrderNumber();
    switch (type) {
      case SHIPMENT_REGISTERED ->
          record(order, type, orderKey + ":shipment:registered", Audience.BUYER);
      case SHIPMENT_UPDATED ->
          record(order, type, orderKey + ":shipment:updated:" + order.getShipment().getRevision(),
              Audience.BUYER);
      case SHIPMENT_OUT_FOR_DELIVERY, SHIPMENT_DELIVERED ->
          record(order, type, orderKey + ":shipment:" + type + ":"
              + java.util.Objects.requireNonNullElse(order.getShipment().getRevision(), 1L), Audience.BUYER);
      case REFUND_REQUESTED, REFUND_WITHDRAWN ->
          record(order, type, "refund:" + order.getRefund().getRefundId() + ":" + type,
              Audience.SELLER);
      case REFUND_APPROVED, REFUND_REJECTED, REFUND_COMPLETED ->
          record(order, type, "refund:" + order.getRefund().getRefundId() + ":" + type,
              Audience.BOTH);
      case PURCHASE_CONFIRMED ->
          record(order, type, orderKey + ":purchase-confirmed", Audience.SELLER);
      case SETTLEMENT_COMPLETED, SETTLEMENT_FAILED ->
          record(order, type, "settlement:" + order.getSettlement().getSettlementId() + ":" + type,
              Audience.SELLER);
      default -> throw new IllegalArgumentException("거래 상태 알림 종류가 아닙니다.");
    }
  }

  private enum Audience { BUYER, SELLER, BOTH }

  private void record(PurchaseOrder order, NotificationType type, String key, Audience audience) {
    var buyer = order.getUser().getId();
    var seller = order.getSellerId();
    // 거래의 판매자 스냅샷을 쓰며 두 계정의 잠금 순서를 고정한다.
    Stream<Long> recipients = switch (audience) {
      case BUYER -> Stream.of(buyer);
      case SELLER -> Stream.of(seller);
      case BOTH -> Stream.of(buyer, seller);
    };
    recipients.distinct().sorted()
        .forEach(userId -> service.record(userId, key, type, order.getOrderNumber()));
  }
}
