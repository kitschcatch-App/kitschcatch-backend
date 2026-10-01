// 로컬 HTTP 서버에서 토스 지급 암호화·인증·증거 대조와 조회 복구를 검증한다.
package com.kitschcatch.backend.domain.order.settlement;
import static org.assertj.core.api.Assertions.*;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;
class HttpTossPayoutGatewayTest {
    static final String KEY="01".repeat(32);
    HttpServer server;
    HttpTossPayoutGateway gateway;
    ObjectMapper json=new ObjectMapper();
    Queue<Reply> replies=new ConcurrentLinkedQueue<>();
    List<Captured> captured=new CopyOnWriteArrayList<>();
    record Reply(int status,String body,long delay) {}
    record Captured(String method,String path,Map<String,List<String>> headers,String body) {
        String header(String name) { return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).findFirst().orElseThrow().getValue().getFirst(); }
    }
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            captured.add(new Captured(e.getRequestMethod(),e.getRequestURI().toString(),new HashMap<>(e.getRequestHeaders()),new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            Reply r=replies.poll();if(r==null) r=new Reply(500,"unexpected",0);
            try { if(r.delay()>0) Thread.sleep(r.delay()); } catch(InterruptedException ex) {Thread.currentThread().interrupt();}
            byte[] body=r.body().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(r.status(),body.length);try(var out=e.getResponseBody()){out.write(body);}finally{e.close();}
        });server.start();gateway=client(5000,10,true);
    }
    HttpTossPayoutGateway client(int timeout,int pages,boolean enabled) {
        return new HttpTossPayoutGateway(RestClient.builder(),new TossPayoutProperties(enabled,"http://127.0.0.1:"+server.getAddress().getPort(),"test_sk_payout",KEY,timeout,pages),java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T01:00:00Z"),java.time.ZoneOffset.UTC));
    }
    @AfterEach void stop() {server.stop(0);}
    SettlementGateway.Command command(String reference) {return new SettlementGateway.Command("SET-123",10,11400,600,"KRW","seller_123",reference);}
    void reply(String body) {replies.add(new Reply(200,body,0));}
    String envelope(String type,Object value) {return json.writeValueAsString(Map.of("entityType",type,"entityBody",value));}
    String seller(String status,String reference) {return envelope("seller",Map.of("id","seller_123","refSellerId",reference,"status",status,"account",Map.of("accountNumber","NEVER_STORE_THIS")));}
    Map<String,Object> payout(String status) {return new HashMap<>(Map.of("id","payout_123","refPayoutId","SET-123","destination","seller_123","amount",Map.of("currency","KRW","value",11400),"status",status));}
    String list(List<?> items,boolean more,String cursor) {
        Map<String,Object> body=new HashMap<>();body.put("items",items);body.put("hasMore",more);body.put("nextCursor",cursor);body.put("size",items.size());return envelope("payout-list",body);
    }
    String encrypted(String value) throws Exception {
        var jwe=new JWEObject(new JWEHeader(JWEAlgorithm.DIR,EncryptionMethod.A256GCM),new Payload(value));
        jwe.encrypt(new DirectEncrypter(HexFormat.of().parseHex(KEY)));return jwe.serialize();
    }
    void preflight() {reply(seller("APPROVED",SettlementGateway.sellerReference(10)));reply(envelope("balance",Map.of("availableAmount",Map.of("currency","KRW","value",999999))));}
    @Test void encryptedRequestUsesVerifiedRecipientAmountAndStableIdempotencyKey() throws Exception {
        preflight();reply(encrypted(list(List.of(payout("REQUESTED")),false,null)));
        var result=gateway.request(command(null));assertThat(result.status()).isEqualTo(SettlementGateway.Status.PENDING);
        assertThat(result.providerReference()).isEqualTo("payout_123");assertThat(result.settledAt()).isNull();
        assertThat(captured).hasSize(3);
        for(var request:captured) assertThat(request.header("Authorization")).isEqualTo("Basic "+Base64.getEncoder().encodeToString("test_sk_payout:".getBytes(StandardCharsets.UTF_8)));
        var request=captured.getLast();assertThat(request.method()).isEqualTo("POST");assertThat(request.path()).isEqualTo("/v2/payouts");
        assertThat(request.header("Idempotency-Key")).isEqualTo("SET-123");assertThat(request.header("TossPayments-api-security-mode")).isEqualTo("ENCRYPTION");
        assertThat(request.body()).doesNotContain("seller_123","11400","NEVER_STORE_THIS");
        var jwe=JWEObject.parse(request.body());assertThat(jwe.getHeader().getAlgorithm()).isEqualTo(JWEAlgorithm.DIR);
        assertThat(jwe.getHeader().getEncryptionMethod()).isEqualTo(EncryptionMethod.A256GCM);
        assertThat(jwe.getHeader().getCustomParam("nonce").toString()).hasSize(36);
        assertThatCode(()->java.time.OffsetDateTime.parse(jwe.getHeader().getCustomParam("iat").toString())).doesNotThrowAnyException();
        jwe.decrypt(new DirectDecrypter(HexFormat.of().parseHex(KEY)));var payload=json.readTree(jwe.getPayload().toString());
        assertThat(payload.isArray()).isTrue();assertThat(payload.size()).isEqualTo(1);
        assertThat(payload.get(0).path("destination").asString()).isEqualTo("seller_123");
        assertThat(payload.get(0).path("amount").path("value").asLong()).isEqualTo(11400);
        assertThat(payload.get(0).path("refPayoutId").asString()).isEqualTo("SET-123");
    }
    @Test void unapprovedOrForeignRecipientAndInsufficientBalanceNeverPostMoney() {
        reply(seller("KYC_REQUIRED",SettlementGateway.sellerReference(10)));
        assertThatThrownBy(()->gateway.request(command(null))).isInstanceOf(SettlementNotSubmittedException.class);
        reply(seller("APPROVED","FOREIGN"));assertThatThrownBy(()->gateway.request(command(null))).isInstanceOf(SettlementNotSubmittedException.class);
        reply(seller("APPROVED",SettlementGateway.sellerReference(10)));reply(envelope("balance",Map.of("availableAmount",Map.of("currency","KRW","value",11399))));
        assertThatThrownBy(()->gateway.request(command(null))).isInstanceOf(SettlementNotSubmittedException.class);
        assertThat(captured).allSatisfy(r->assertThat(r.method()).isEqualTo("GET"));
    }
    @ParameterizedTest @ValueSource(strings={"destination","refPayoutId","currency","amount","fraction","id","status"})
    void mismatchedOrMalformedEvidenceNeverCompletes(String field) {
        var data=payout("COMPLETED");
        switch(field) {
            case "currency" -> data.put("amount",Map.of("currency","USD","value",11400));
            case "amount" -> data.put("amount",Map.of("currency","KRW","value",11401));
            case "fraction" -> data.put("amount",Map.of("currency","KRW","value",11400.1));
            default -> data.put(field,"WRONG");
        }
        reply(envelope("payout",data));
        assertThatThrownBy(()->gateway.lookup(command("payout_123"))).isInstanceOf(BusinessException.class);
    }
    @Test void unknownRequestIsFoundOnLaterPageWithoutAnotherPost() {
        var unrelated=payout("COMPLETED");unrelated.put("refPayoutId","OTHER");
        reply(list(List.of(unrelated),true,"cursor_1"));reply(list(List.of(payout("COMPLETED")),false,null));
        var result=gateway.lookup(command(null));assertThat(result.status()).isEqualTo(SettlementGateway.Status.COMPLETED);assertThat(result.settledAt()).isNotNull();
        assertThat(captured.stream().map(Captured::path)).containsExactly("/v2/payouts?limit=100","/v2/payouts?limit=100&startingAfter=cursor_1");
        assertThat(captured).allSatisfy(r->assertThat(r.method()).isEqualTo("GET"));
    }
    @Test void outsideExpressHoursNeverMakesAnExternalRequest() {
        var outside=new HttpTossPayoutGateway(RestClient.builder(),new TossPayoutProperties(true,"http://127.0.0.1:"+server.getAddress().getPort(),"test_sk_payout",KEY,5000,10),
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-03T01:00:00Z"),java.time.ZoneOffset.UTC));
        assertThatThrownBy(()->outside.request(command(null))).isInstanceOf(SettlementNotSubmittedException.class);
        assertThat(captured).isEmpty();
    }
    @Test void knownProviderReferenceUsesDirectLookup() {
        reply(envelope("payout",payout("FAILED")));
        assertThat(gateway.lookup(command("payout_123")).status()).isEqualTo(SettlementGateway.Status.FAILED);
        assertThat(captured.getFirst().path()).isEqualTo("/v2/payouts/payout_123");
    }
    @Test void missingLookupResultDoesNotSubmitANewPayout() {
        reply(list(List.of(),false,null));assertThat(gateway.lookup(command(null))).isNull();assertThat(captured).hasSize(1);
    }
    @Test void repeatedCursorAndPageLimitFailClosed() {
        reply(list(List.of(),true,"cursor_1"));reply(list(List.of(),true,"cursor_1"));
        assertThatThrownBy(()->gateway.lookup(command(null))).isInstanceOf(BusinessException.class);
        reply(list(List.of(),true,"cursor_2"));assertThatThrownBy(()->client(5000,1,true).lookup(command(null))).isInstanceOf(BusinessException.class);
        assertThat(captured).hasSize(3);
    }
    @Test void encryptedErrorAndInvalidCiphertextAreSanitized() throws Exception {
        preflight();replies.add(new Reply(400,encrypted("{\"error\":{\"message\":\"PRIVATE_BANK_DATA\"}}"),0));
        assertThatThrownBy(()->gateway.request(command(null))).isInstanceOf(BusinessException.class).hasMessageNotContaining("PRIVATE_BANK_DATA").hasMessageNotContaining(KEY);
        preflight();reply("not-a-jwe");assertThatThrownBy(()->gateway.request(command(null))).isInstanceOf(BusinessException.class);
    }
    @Test void disabledKeysAndTimeoutAreNotAcceptedAsEvidence() {
        assertThatThrownBy(()->client(5000,10,false).recipient("seller_123")).isInstanceOf(BusinessException.class);assertThat(captured).isEmpty();
        replies.add(new Reply(200,seller("APPROVED",SettlementGateway.sellerReference(10)),350));
        assertThatThrownBy(()->client(100,10,true).recipient("seller_123")).isInstanceOf(BusinessException.class);
    }
}
