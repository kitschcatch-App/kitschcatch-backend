// 로컬 HTTP 대역에서 FCM 인증·응답·오류를 실제 푸시 없이 검증한다.
package com.kitschcatch.backend.domain.notification;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class FcmPushGatewayTest {
  HttpServer server;
  PushProperties properties;
  FcmPushGateway gateway;
  KeyPair key;
  AtomicInteger oauthRequests;
  AtomicInteger fcmRequests;
  volatile String assertion;
  volatile String authorization;
  volatile String messageBody;
  int responseStatus = 200;
  String responseBody = "{\"name\":\"projects/test/messages/1\"}";
  String retryAfter;
  int oauthStatus = 200;

  @BeforeEach
  void setup() throws Exception {
    key = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    oauthRequests = new AtomicInteger();
    fcmRequests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          oauthRequests.incrementAndGet();
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertion =
              URLDecoder.decode(
                  body.substring(body.indexOf("assertion=") + 10), StandardCharsets.UTF_8);
          byte[] data =
              (oauthStatus == 200
                      ? "{\"access_token\":\"synthetic-oauth\",\"expires_in\":3600}"
                      : "{\"error\":\"invalid_grant\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(oauthStatus, data.length);
          exchange.getResponseBody().write(data);
          exchange.close();
        });
    server.createContext(
        "/v1/projects/test/messages:send",
        exchange -> {
          fcmRequests.incrementAndGet();
          authorization = exchange.getRequestHeaders().getFirst("Authorization");
          messageBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          byte[] data = responseBody.getBytes(StandardCharsets.UTF_8);
          if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(responseStatus, data.length);
          exchange.getResponseBody().write(data);
          exchange.close();
        });
    server.start();
    properties = new PushProperties();
    properties.setEnabled(true);
    properties.setProjectId("test");
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    properties.setBaseUrl(base);
    properties.setTokenUrl(base + "/token");
    properties.setClientEmail("test@synthetic.invalid");
    properties.setPrivateKey(
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(key.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----");
    gateway = new FcmPushGateway(properties);
  }

  @AfterEach
  void close() {
    server.stop(0);
  }

  PushGateway.Result send() {
    var token = new DeviceToken(1, "synthetic-fcm-token", "IOS");
    var notification = new Notification(1, "event", NotificationType.CHAT_MESSAGE, "42");
    ReflectionTestUtils.setField(notification, "id", 7L);
    return gateway.send(token, notification);
  }

  @Test
  void signsScopedOAuthAssertionSendsBearerAndCachesCredential() throws Exception {
    assertThat(send().success()).isTrue();
    assertThat(send().messageId()).isEqualTo("projects/test/messages/1");
    assertThat(authorization).isEqualTo("Bearer synthetic-oauth");
    assertThat(oauthRequests.get()).isEqualTo(1);
    assertThat(fcmRequests.get()).isEqualTo(2);
    var jwt = SignedJWT.parse(assertion);
    assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) key.getPublic()))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("test@synthetic.invalid");
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(properties.getTokenUrl());
    assertThat(jwt.getJWTClaimsSet().getStringClaim("scope"))
        .isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
    var body = JsonMapper.builder().build().readValue(messageBody, Map.class);
    var message = (Map<?, ?>) body.get("message");
    assertThat(message.get("token")).isEqualTo("synthetic-fcm-token");
    assertThat((Map) message.get("data"))
        .containsEntry("notificationId", "7")
        .containsEntry("type", "CHAT_MESSAGE")
        .containsEntry("targetId", "42");
  }

  @Test
  void unregisteredTypedErrorInvalidatesTokenButOther404DoesNot() {
    responseStatus = 404;
    responseBody =
        "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}";
    assertThat(send().invalidToken()).isTrue();
    assertThat(send().retryable()).isFalse();
    responseBody = "{\"error\":{\"status\":\"NOT_FOUND\"}}";
    assertThat(send().invalidToken()).isFalse();
  }

  @Test
  void retryAfterAndTemporaryAuthFailuresAreRetryable() {
    responseStatus = 429;
    retryAfter = "120";
    assertThat(send().retryAfterSeconds()).isEqualTo(120);
    assertThat(send().retryable()).isTrue();
    responseStatus = 503;
    assertThat(send().retryable()).isTrue();
    responseStatus = 401;
    send();
    send();
    assertThat(oauthRequests.get()).isEqualTo(2);
    responseStatus = 403;
    assertThat(send().retryable()).isTrue();
  }

  @Test
  void invalidPayloadIsTerminalWithoutDeactivatingTokenAndMalformedSuccessRetries() {
    responseStatus = 400;
    responseBody =
        "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"INVALID_ARGUMENT\"}]}}";
    var result = send();
    assertThat(result.retryable()).isFalse();
    assertThat(result.invalidToken()).isFalse();
    assertThat(result.code()).isEqualTo("INVALID_ARGUMENT");
    responseStatus = 200;
    responseBody = "invalid json";
    assertThat(send().code()).isEqualTo("MALFORMED_RESPONSE");
  }

  @Test
  void oauthFailureAndTransportFailureExposeOnlySafeCode() {
    oauthStatus = 400;
    assertThat(send().code()).isEqualTo("TRANSPORT_OR_AUTH_FAILURE");
    assertThat(fcmRequests.get()).isZero();
    server.stop(0);
    assertThat(send().retryable()).isTrue();
  }

  @Test
  void disabledProviderMakesNoHttpRequests() {
    properties.setEnabled(false);
    assertThat(send().code()).isEqualTo("DISABLED");
    assertThat(oauthRequests.get()).isZero();
    assertThat(fcmRequests.get()).isZero();
  }
}
