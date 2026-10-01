// 인증된 사용자의 기기 등록·해제와 알림 조회·읽음 API를 제공한다.
package com.kitschcatch.backend.domain.notification;

import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@Tag(name = "알림", description = "개인 기기 토큰과 알림함")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {
  private final DeviceTokenService tokens;
  private final NotificationService notifications;

  public NotificationController(DeviceTokenService tokens, NotificationService notifications) {
    this.tokens = tokens;
    this.notifications = notifications;
  }

  @PostMapping("/api/users/me/device-tokens")
  @Operation(summary = "기기 등록", description = "같은 FCM 토큰은 마지막 등록 계정으로 이전됩니다. 반복 등록은 멱등합니다.")
  public ApiResponse<Void> register(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody DeviceTokenRequest request) {
    tokens.register(user.userId(), request);
    return ApiResponse.success(null);
  }

  @DeleteMapping("/api/users/me/device-tokens")
  @Operation(summary = "기기 해제", description = "JSON token을 전달합니다. 자기 계정 소유만 해제하며 없는 토큰도 성공합니다.")
  public ApiResponse<Void> remove(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody DeleteDeviceTokenRequest request) {
    tokens.remove(user.userId(), request.token());
    return ApiResponse.success(null);
  }

  @GetMapping("/api/notifications")
  @Operation(
      summary = "내 알림함",
      description = "page 0..10000, size 1..100. 생성 시각과 ID 역순이며 unreadCount는 내 전체 미읽음 수입니다.")
  public ApiResponse<NotificationService.Inbox> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(notifications.list(user.userId(), page, size));
  }

  @PatchMapping("/api/notifications/{notificationId}/read")
  @Operation(
      summary = "알림 읽음",
      description = "자기 알림만 읽습니다. 반복 호출은 최초 읽음 시각을 보존합니다. 없거나 타인 알림은 NOTIFICATION_001입니다.")
  public ApiResponse<Void> read(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long notificationId) {
    notifications.read(user.userId(), notificationId);
    return ApiResponse.success(null);
  }

  @PatchMapping("/api/notifications/read-all")
  @Operation(summary = "전체 읽음", description = "현재 사용자 미읽음만 갱신하며 updatedCount를 반환합니다.")
  public ApiResponse<Map<String, Integer>> readAll(
      @AuthenticationPrincipal AuthenticatedUser user) {
    return ApiResponse.success(Map.of("updatedCount", notifications.readAll(user.userId())));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ApiResponse<Void> invalidParameter(MethodArgumentTypeMismatchException exception) {
    return ApiResponse.fail(
        switch (exception.getName()) {
          case "page", "size" -> ErrorCode.INVALID_INPUT_VALUE;
          default -> ErrorCode.BAD_REQUEST;
        });
  }
}
