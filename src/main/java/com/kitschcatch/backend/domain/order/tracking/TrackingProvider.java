// 공식 배송 조회 API로 구독 등록과 정규화된 배송 이벤트 조회를 수행한다.
package com.kitschcatch.backend.domain.order.tracking;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
public class TrackingProvider {
    public enum Status { UNKNOWN, WAITING_FOR_PICKUP, IN_TRANSIT, OUT_FOR_DELIVERY, AVAILABLE_FOR_PICKUP, DELIVERED, EXCEPTION }
    public record Event(Status status, OffsetDateTime occurredAt) {}
    public record Snapshot(Event latest, List<Event> events) {}

    private static final String LOOKUP = """
        query Track($carrierId: ID!, $trackingNumber: String!) {
          track(carrierId: $carrierId, trackingNumber: $trackingNumber) {
            lastEvent { time status { code } }
            events(last: 10) { edges { node { time status { code } } } }
          }
        }
        """;
    private static final String REGISTER = """
        mutation Register($input: RegisterTrackWebhookInput!) { registerTrackWebhook(input: $input) }
        """;
    private final RestClient http;
    private final URI callbackBase;

    public TrackingProvider(@Value("${app.shipment.tracking.client-id:}") String clientId,
                            @Value("${app.shipment.tracking.client-secret:}") String secret,
                            @Value("${app.shipment.tracking.callback-base:}") String base,
                            @Value("${app.shipment.tracking.enabled:false}") boolean enabled) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder().baseUrl("https://apis.tracker.delivery/graphql").requestFactory(factory)
            .defaultHeader("Authorization", "TRACKQL-API-KEY " + clientId + ":" + secret).build();
        this.callbackBase = base.isBlank() ? null : URI.create(base);
        if (enabled && (clientId.isBlank() || secret.isBlank() || callbackBase == null
            || !"https".equals(callbackBase.getScheme()) || callbackBase.getHost() == null
            || callbackBase.getUserInfo() != null || callbackBase.getQuery() != null || callbackBase.getFragment() != null
            || !List.of("", "/").contains(callbackBase.getPath()))) {
            throw new IllegalStateException("배송 추적 인증과 HTTPS 콜백 주소를 설정해야 합니다.");
        }
    }

    public void subscribe(String carrier, String number, UUID token, OffsetDateTime expiresAt) {
        var callback = callbackBase.resolve("/api/shipment-tracking/webhooks/" + token);
        var input = Map.of("carrierId", carrier, "trackingNumber", number, "callbackUrl", callback.toString(),
            "expirationTime", expiresAt.toString());
        var body = call(REGISTER, Map.of("input", input));
        if (hasErrors(body) || !body.path("data").path("registerTrackWebhook").asBoolean())
            throw new IllegalStateException("배송 추적 구독에 실패했습니다.");
    }

    public Snapshot lookup(String carrier, String number) {
        var body = call(LOOKUP, Map.of("carrierId", carrier, "trackingNumber", number));
        if (hasErrors(body)) {
            var errors = body.path("errors");
            if (errors.size() == 1 && "NOT_FOUND".equals(errors.get(0).path("extensions").path("code").asText()))
                return new Snapshot(null, List.of());
            throw new IllegalStateException("배송 상태 조회에 실패했습니다.");
        }
        var track = body.path("data").path("track");
        if (track.isMissingNode()) throw new IllegalStateException("배송 조회 응답이 올바르지 않습니다.");
        var events = new ArrayList<Event>();
        for (var edge : track.path("events").path("edges")) {
            var event = parse(edge.path("node"));
            if (event != null) events.add(event);
        }
        var latest = parse(track.path("lastEvent"));
        if (latest != null) events.add(latest);
        return new Snapshot(latest, List.copyOf(events));
    }

    private JsonNode call(String query, Map<String, ?> variables) {
        var body = http.post().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(Map.of("query", query, "variables", variables)).retrieve().body(JsonNode.class);
        if (body == null) throw new IllegalStateException("배송 조회 응답이 비어 있습니다.");
        return body;
    }

    private static boolean hasErrors(JsonNode body) {
        return !body.path("errors").isEmpty();
    }

    private static Event parse(JsonNode node) {
        if (!node.hasNonNull("time")) return null;
        var status = switch (node.path("status").path("code").asText().toUpperCase(java.util.Locale.ROOT)) {
            case "INFORMATION_RECEIVED", "AT_PICKUP" -> Status.WAITING_FOR_PICKUP;
            case "IN_TRANSIT" -> Status.IN_TRANSIT;
            case "OUT_FOR_DELIVERY" -> Status.OUT_FOR_DELIVERY;
            case "AVAILABLE_FOR_PICKUP" -> Status.AVAILABLE_FOR_PICKUP;
            case "DELIVERED" -> Status.DELIVERED;
            case "EXCEPTION", "ATTEMPT_FAIL" -> Status.EXCEPTION;
            default -> Status.UNKNOWN;
        };
        return new Event(status, OffsetDateTime.parse(node.path("time").asText()));
    }
}
