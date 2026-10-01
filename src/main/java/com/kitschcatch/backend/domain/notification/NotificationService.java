// 알림·발송 작업을 원래 거래 트랜잭션에 저장하고 개인 알림함을 관리한다.
package com.kitschcatch.backend.domain.notification;

import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.*;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private final UserRepository users;
  private final NotificationRepository notifications;
  private final DeviceTokenRepository tokens;
  private final PushDeliveryRepository deliveries;

  public NotificationService(
      UserRepository users,
      NotificationRepository notifications,
      DeviceTokenRepository tokens,
      PushDeliveryRepository deliveries) {
    this.users = users;
    this.notifications = notifications;
    this.tokens = tokens;
    this.deliveries = deliveries;
  }

  @Transactional
  public void record(long userId, String eventKey, NotificationType type, String targetId) {
    users.findByIdForUpdate(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    // 앞서 로드된 User가 있더라도 잠금 뒤 DB의 현재 종료 상태를 다시 확인한다.
    if (!users.existsByIdAndWithdrawnAtIsNull(userId)
        || notifications.existsByEventKeyAndUserId(eventKey, userId)) return;
    var notification =
        notifications.saveAndFlush(new Notification(userId, eventKey, type, targetId));
    for (var token : tokens.findByUserIdAndActiveTrueOrderById(userId))
      deliveries.save(new PushDelivery(notification.getId(), token));
  }

  @Transactional(readOnly = true)
  public Inbox list(long userId, int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
    var result =
        notifications.findByUserId(
            userId,
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    return new Inbox(
        result.stream().map(Item::from).toList(),
        page,
        size,
        result.getTotalElements(),
        result.getTotalPages(),
        notifications.countByUserIdAndReadAtIsNull(userId));
  }

  @Transactional
  public void read(long userId, long id) {
    if (id <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST);
    if (notifications.findByIdAndUserId(id, userId).isEmpty())
      throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
    notifications.read(userId, id, LocalDateTime.now());
  }

  @Transactional
  public int readAll(long userId) {
    return notifications.readAll(userId, LocalDateTime.now());
  }

  public record Item(
      long id,
      NotificationType type,
      String title,
      String targetId,
      LocalDateTime createdAt,
      LocalDateTime readAt) {
    static Item from(Notification n) {
      return new Item(
          n.getId(), n.getType(), n.getTitle(), n.getTargetId(), n.getCreatedAt(), n.getReadAt());
    }
  }

  public record Inbox(
      List<Item> notifications,
      int page,
      int size,
      long totalElements,
      int totalPages,
      long unreadCount) {}
}
