// 기본 비활성 푸시와 FCM 서비스 계정 인증 설정을 읽는다.
package com.kitschcatch.backend.domain.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("app.notifications.push")
@Getter
@Setter
public class PushProperties {
  private boolean enabled = false;
  private String projectId = "";
  private String baseUrl = "https://fcm.googleapis.com";
  private String tokenUrl = "https://oauth2.googleapis.com/token";
  private String clientEmail = "";
  private String privateKey = "";
}
