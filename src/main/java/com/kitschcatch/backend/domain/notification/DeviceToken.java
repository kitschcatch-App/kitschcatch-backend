// 기기 토큰의 현재 소유자와 계정 전환 세대를 보존한다.
package com.kitschcatch.backend.domain.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "device_tokens",
    uniqueConstraints = @UniqueConstraint(name = "uk_device_tokens_token", columnNames = "token"))
@Getter
@NoArgsConstructor
public class DeviceToken {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 2048)
  private String token;

  @Column(nullable = false)
  private Long userId;

  @Column(nullable = false, length = 16)
  private String platform;

  @Column(nullable = false)
  private boolean active;

  @Column(nullable = false)
  private long ownershipVersion;

  public DeviceToken(long userId, String token, String platform) {
    this.token = token;
    this.userId = userId;
    this.platform = platform;
    this.active = true;
  }

  public void register(long userId, String platform) {
    if (!this.userId.equals(userId) || !active) ownershipVersion++;
    this.userId = userId;
    this.platform = platform;
    active = true;
  }

  public void deactivate() {
    active = false;
    ownershipVersion++;
  }
}
