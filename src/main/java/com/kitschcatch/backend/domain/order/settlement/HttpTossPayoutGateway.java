// 토스 지급대행 v2의 암호화 요청과 수취인·지급 증거 검증을 수행한다.
package com.kitschcatch.backend.domain.order.settlement;

import com.kitschcatch.backend.global.exception.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;

public class HttpTossPayoutGateway implements SettlementGateway {
    private final RestClient client;
    private final TossPayoutProperties properties;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();
    public HttpTossPayoutGateway(RestClient.Builder builder,TossPayoutProperties properties) {
        this(builder,properties,Clock.systemUTC());
    }
    public HttpTossPayoutGateway(RestClient.Builder builder,TossPayoutProperties properties,Clock clock) {
        this.clock=clock;
        this.properties=properties;
        URI uri=URI.create(properties.baseUrl());
        boolean local=Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost());
        if(uri.getUserInfo()!=null || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()))))
            throw new IllegalArgumentException("지급대행 주소는 HTTPS 또는 로컬 테스트 주소여야 합니다.");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.timeoutMillis())).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofMillis(properties.timeoutMillis()));
        client=builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }
    public boolean configured() { return properties.configured(); }
    private void requireConfigured() {
        if(!configured()) throw new BusinessException(ErrorCode.SETTLEMENT_NOT_CONFIGURED);
    }
    private String authorization() {
        return "Basic "+Base64.getEncoder().encodeToString((properties.secretKey()+":").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private JsonNode get(String path) {
        requireConfigured();
        try { return json.readTree(client.get().uri(path).header("Authorization",authorization()).retrieve().body(String.class)); }
        catch(RuntimeException failure) { throw unavailable(); }
    }
    public Recipient recipient(String id) {
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,35}")) throw unavailable();
        var body=body(get("/v2/sellers/"+id),"seller");
        var result=new Recipient(text(body,"id"),text(body,"refSellerId"),text(body,"status"));
        if(!id.equals(result.id())) throw unavailable();
        return result;
    }
    public Result request(Command command) {
        requireConfigured();
        if(command.amount()<=0 || command.amount()>=1_000_000_000L || !"KRW".equals(command.currency())) throw unavailable();
        try { preflight(command); }
        catch(BusinessException failure) { throw new SettlementNotSubmittedException(failure.getErrorCode()); }
        catch(RuntimeException failure) { throw new SettlementNotSubmittedException(ErrorCode.SETTLEMENT_PROVIDER_FAILED); }
        try {
            String payload=json.writeValueAsString(List.of(Map.of("refPayoutId",command.settlementId(),
                "destination",command.destination(),"scheduleType","EXPRESS",
                "amount",Map.of("currency",command.currency(),"value",command.amount()),"transactionDescription","키치캐치")));
            String encrypted=encrypt(payload);
            String response=client.post().uri("/v2/payouts").header("Authorization",authorization())
                .header("Idempotency-Key",command.settlementId()).header("TossPayments-api-security-mode","ENCRYPTION")
                .contentType(MediaType.TEXT_PLAIN).body(encrypted).retrieve().body(String.class);
            var items=body(json.readTree(decrypt(response)),"payout-list").path("items");
            if(!items.isArray() || items.size()!=1) throw unavailable();
            return result(items.get(0),command);
        } catch(Exception failure) { throw unavailable(); }
    }
    private void preflight(Command command) {
        var now=ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of("Asia/Seoul"));
        if(now.getDayOfWeek()==DayOfWeek.SATURDAY || now.getDayOfWeek()==DayOfWeek.SUNDAY
            || now.getHour()<8 || now.getHour()>=15) throw new BusinessException(ErrorCode.SETTLEMENT_PRECHECK_FAILED);
        var seller=recipient(command.destination());
        if(!SettlementGateway.sellerReference(command.sellerId()).equals(seller.refSellerId()) || !"APPROVED".equals(seller.status()))
            throw new BusinessException(ErrorCode.SETTLEMENT_RECIPIENT_INVALID);
        var balance=body(get("/v2/balances"),"balance").path("availableAmount");
        if(!"KRW".equals(text(balance,"currency")) || !balance.path("value").isNumber()
            || balance.path("value").decimalValue().compareTo(java.math.BigDecimal.valueOf(command.amount()))<0) throw new BusinessException(ErrorCode.SETTLEMENT_PRECHECK_FAILED);
    }
    public Result lookup(Command command) {
        if(command.providerReference()!=null) {
            if(!command.providerReference().matches("[A-Za-z0-9_-]{1,35}")) throw unavailable();
            return result(body(get("/v2/payouts/"+command.providerReference()),"payout"),command);
        }
        String cursor=null;
        Set<String> visited=new HashSet<>();
        for(int page=0;page<properties.lookupMaxPages();page++) {
            String path="/v2/payouts?limit=100"+(cursor==null?"":"&startingAfter="+cursor);
            var list=body(get(path),"payout-list"); var items=list.path("items");
            if(!items.isArray()) throw unavailable();
            for(var item:items) if(command.settlementId().equals(text(item,"refPayoutId"))) return result(item,command);
            if(!list.path("hasMore").isBoolean()) throw unavailable();
            if(!list.path("hasMore").booleanValue()) return null;
            cursor=text(list,"nextCursor");
            if(cursor==null || !cursor.matches("[A-Za-z0-9_-]{1,35}") || !visited.add(cursor)) throw unavailable();
        }
        throw unavailable();
    }
    private Result result(JsonNode p,Command command) {
        String id=text(p,"id"), ref=text(p,"refPayoutId"), destination=text(p,"destination"), currency=text(p.path("amount"),"currency");
        var amount=p.path("amount").path("value");
        long value;
        try { if(!amount.isNumber()) throw unavailable(); value=amount.decimalValue().longValueExact(); }
        catch(ArithmeticException failure) { throw unavailable(); }
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,35}") || !command.settlementId().equals(ref)
            || !command.destination().equals(destination) || !command.currency().equals(currency) || value!=command.amount()
            || (command.providerReference()!=null && !command.providerReference().equals(id))) throw unavailable();
        Status status=switch(Objects.toString(text(p,"status"),"")) {
            case "REQUESTED","IN_PROGRESS" -> Status.PENDING;
            case "COMPLETED" -> Status.COMPLETED;
            case "FAILED","CANCELED","DELETED","REJECTED" -> Status.FAILED;
            default -> throw unavailable();
        };
        return new Result(ref,value,currency,status,id,status==Status.COMPLETED?LocalDateTime.now():null,destination);
    }
    private JsonNode body(JsonNode node,String type) {
        if(node==null || !type.equals(text(node,"entityType")) || !node.path("entityBody").isObject()) throw unavailable();
        return node.path("entityBody");
    }
    private String text(JsonNode node,String field) { return node.path(field).isString()?node.path(field).asString():null; }
    private String encrypt(String payload) throws Exception {
        var header=new JWEHeader.Builder(JWEAlgorithm.DIR,EncryptionMethod.A256GCM)
            .customParam("iat",OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")))
            .customParam("nonce",UUID.randomUUID().toString()).build();
        var jwe=new JWEObject(header,new Payload(payload));jwe.encrypt(new DirectEncrypter(HexFormat.of().parseHex(properties.securityKey())));
        return jwe.serialize();
    }
    private String decrypt(String payload) throws Exception {
        var jwe=JWEObject.parse(payload);
        if(!JWEAlgorithm.DIR.equals(jwe.getHeader().getAlgorithm()) || !EncryptionMethod.A256GCM.equals(jwe.getHeader().getEncryptionMethod())) throw unavailable();
        jwe.decrypt(new DirectDecrypter(HexFormat.of().parseHex(properties.securityKey())));return jwe.getPayload().toString();
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.SETTLEMENT_PROVIDER_FAILED); }
}
