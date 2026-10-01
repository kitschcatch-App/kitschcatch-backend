// 서비스 계정 OAuth 인증과 FCM HTTP v1 발송 및 오류 분류를 처리한다.
package com.kitschcatch.backend.domain.notification;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FcmPushGateway implements PushGateway {
  private final PushProperties properties;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private String accessToken;
  private Instant expiresAt = Instant.EPOCH;

  public FcmPushGateway(PushProperties properties) {
    this.properties = properties;
  }

  @Override
  public Result send(DeviceToken token, Notification notification) {
    if (!properties.isEnabled()) return Result.retry("DISABLED");
    try {
      if (!properties.getProjectId().matches("[a-zA-Z0-9-]+")) return Result.retry("CONFIGURATION");
      String credential = accessToken();
      var message =
          Map.of(
              "token",
              token.getToken(),
              "notification",
              Map.of("title", notification.getTitle()),
              "data",
              Map.of(
                  "notificationId",
                  notification.getId().toString(),
                  "type",
                  notification.getType().name(),
                  "targetId",
                  notification.getTargetId()));
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      properties.getBaseUrl()
                          + "/v1/projects/"
                          + properties.getProjectId()
                          + "/messages:send"))
              .timeout(Duration.ofSeconds(3))
              .header("Authorization", "Bearer " + credential)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      json.writeValueAsString(Map.of("message", message))))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      Map<?, ?> body = parse(response.body());
      if (status >= 200 && status < 300) {
        if (!(body.get("name") instanceof String name) || name.isBlank() || name.length() > 512)
          return Result.retry("MALFORMED_RESPONSE");
        return new Result(true, false, false, "OK", (String) body.get("name"), 0);
      }
      String code = errorCode(body, status);
      if (status == 401) invalidateCredential();
      boolean invalidToken = status == 404 && code.equals("UNREGISTERED");
      boolean retry = status == 401 || status == 403 || status == 429 || status >= 500;
      return new Result(false, retry, invalidToken, code, null, retryAfter(response));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return Result.retry("INTERRUPTED");
    } catch (Exception exception) {
      // 제공자 응답·토큰·키·인증 오류의 원문을 로그나 사용자 응답에 노출하지 않는다.
      return Result.retry("TRANSPORT_OR_AUTH_FAILURE");
    }
  }

  private synchronized String accessToken() throws Exception {
    if (accessToken != null && expiresAt.isAfter(Instant.now().plusSeconds(60))) return accessToken;
    String pem =
        properties
            .getPrivateKey()
            .replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    var key =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(properties.getClientEmail())
            .audience(properties.getTokenUrl())
            .claim("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .build();
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(key));
    var request =
        HttpRequest.newBuilder(URI.create(properties.getTokenUrl()))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="
                        + URLEncoder.encode(jwt.serialize(), StandardCharsets.UTF_8)))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    var body = parse(response.body());
    if (response.statusCode() != 200
        || !(body.get("access_token") instanceof String value)
        || value.isBlank()
        || !(body.get("expires_in") instanceof Number seconds)
        || seconds.longValue() <= 0) throw new IllegalStateException("OAuth failed");
    accessToken = (String) body.get("access_token");
    expiresAt = now.plusSeconds(Math.min(3600, ((Number) body.get("expires_in")).longValue()));
    return accessToken;
  }

  private synchronized void invalidateCredential() {
    accessToken = null;
    expiresAt = Instant.EPOCH;
  }

  private Map<?, ?> parse(String body) {
    try {
      return json.readValue(body, Map.class);
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private String errorCode(Map<?, ?> body, int status) {
    if (body.get("error") instanceof Map<?, ?> error
        && error.get("details") instanceof List<?> details) {
      for (var detail : details)
        if (detail instanceof Map<?, ?> d
            && "type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(d.get("@type"))
            && d.get("errorCode") instanceof String code
            && code.matches("[A-Z_]{1,80}")) return code;
    }
    return "HTTP_" + status;
  }

  private long retryAfter(HttpResponse<?> response) {
    String value = response.headers().firstValue("Retry-After").orElse("60");
    try {
      return Math.max(60, Math.min(86400, Long.parseLong(value)));
    } catch (NumberFormatException ignored) {
      try {
        return Math.max(
            60,
            Math.min(
                86400,
                Duration.between(
                        Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant())
                    .getSeconds()));
      } catch (Exception invalid) {
        return 60;
      }
    }
  }
}
