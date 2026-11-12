# 주문 견적과 결제 금액 정책

이슈 #95. 판매자 수수료는 상품금액 5%(원 미만 버림), 구매자 안전결제 수수료는 0원이다. 배송비는 판매자가 택배 운임을 납부하는 고정 선불 운임이며 판매자에게 전달한다. PG·지급대행 비용은 기존 정책대로 플랫폼이 부담한다.

## 계산과 저장

- `totalAmount = productAmount + shippingAmount + buyerFeeAmount`.
- `sellerFeeAmount = floor(productAmount * 500 / 10000)`.
- `sellerPayoutAmount = productAmount + shippingAmount - sellerFeeAmount`.
- 상품 30,000원·배송 3,000원이면 구매자 청구액 33,000원, 판매자 공제 1,500원, 지급액 31,500원이다.
- 모든 금액은 정수 원이며 최종 금액은 10억원 미만 양수이다. 0원 상품의 PG 결제는 제공하지 않는다. 합산 오버플로를 거절한다.
- 정책 버전 `V2_SELLER_500_BUYER_0`, 상품·배송·양측 수수료·지급액을 orders에 INSERT 전에 고정한다. 설정이나 상품가가 바뀌어도 기존 결제·재시도·정산의 금액을 바꾸지 않는다.

## 상품 배송비

새 거래의 배송비는 `ShippingPolicy.AMOUNT`에서 3,000원으로 고정한다. 상품 생성·수정 시 `shippingAmount`를 생략하면 서버가 3,000원을 저장한다. 값이 있으면 3,000원만 허용하며 0원·다른 금액은 400으로 거절한다.

상품 조회와 `GET /api/posts/{postId}/shipping`은 기존 상품의 저장값이 null·0원·다른 금액이어도 새 거래에 적용할 3,000원을 반환한다. 견적 역시 같은 값을 사용하므로 기존 상품을 일괄 수정하는 DB 작업은 필요 없다. 기존 주문은 주문 당시의 pricing과 amount를 그대로 사용한다.

판매자 전용 `PATCH /api/posts/{postId}/shipping`은 호환성을 위해 유지하며 `{"shippingAmount":3000}`만 받는다. 진행 주문 또는 판매 중이 아닌 상품은 변경할 수 없다. 앱에서는 배송비 입력을 받지 않는다.

## 견적과 주문 API

JWT 필요. `POST /api/orders/quotes` 본문:

```json
{"postId":123,"shippingAddressId":45}
```

201 응답 data는 `quoteId`, `pricing`, `expiresAt`이다. pricing은 `currency=KRW`, `pricingPolicyVersion`, `productAmount`, `shippingAmount`, `buyerFeeAmount`, `totalAmount`, `sellerFeeAmount`, `sellerPayoutAmount`를 포함한다. 구매자 화면은 상품금액·배송비·구매자 수수료·총액을, 판매자 화면은 판매자 수수료·지급액을 표시한다. 판매자 수수료를 구매자 총액에 더하면 안 된다.

견적은 사용자·상품·본인 배송지와 연결되며 5분 유효하다. 상품 예약은 생성하지 않는다. 주문 생성 본문:

```json
{"postId":123,"shippingAddressId":45,"paymentMethod":"CARD","amount":33000,"quoteId":"QUOTE-..."}
```

서버가 사용자 잠금·견적 잠금·상품 잠금 안에서 견적 소유권, 입력 금액, 상품·주소 일치, 만료, 상품가격·현재 고정 배송비·정책·주소 수정 시각을 재검증한다. 변경되면 새 견적을 받도록 409를 반환한다. 동일 견적의 동시 주문 생성은 한 주문으로 직렬화한다. 동일 주문 재요청은 이미 생성된 주문을 반환하고, 만료·취소된 주문을 다시 예약하지 않는다.

생성 응답과 주문 상세에 `pricing`을 추가했다. 앱은 주문 생성 응답의 `pricing.totalAmount`로 PG SDK를 호출한다. 기존 결제 생성 요청의 amount도 같은 총액이어야 한다. PG 승인에는 DB에 저장된 결제 금액을 사용하고 기존 승인·웹훅·복구 대조 로직이 payment/attempt 금액과 PG totalAmount를 검사한다.

- `ORDER_095_001` 409: 본인 견적이 없거나 견적 입력·만료·금액 조건이 달라짐.
- `ORDER_095_002`는 이전 정책의 배송비 미설정 오류이며 고정 배송비 견적에서는 발생하지 않는다.
- `ORDER_095_003` 409: 견적을 지원하는 주문 계약으로 전환 필요.
- 형식·금액 범위 오류는 400, 인증은 401, 배송지 소유권은 기존 주소록의 404, 타인 상품 수정은 403이다.

## 환불·정산과 기존 주문

amount의 의미는 최종 청구액이다. Payment와 PaymentAttempt는 해당 금액을 저장한다. 기존 전액 환불 경로에서 상품금액만 보내면 거절하고 실제 결제 총액이 일치해야 한다. PG 전액 취소는 배송비를 포함한 총액을 환불한다. 반품 운임은 기존 정책대로 별도 처리하며 부분 환불로 임의 차감하지 않는다. 승인·취소·재시도 멱등키와 상태 머신을 유지한다.

새 주문은 정산 시 저장된 sellerFeeAmount와 sellerPayoutAmount를 사용한다. 배송비에는 판매자 수수료가 붙지 않는다. 기존 주문은 pricing/quoteId가 null인 레거시로 유지하고 이미 저장된 정산 금액을 우선하며 기존 정산 계산 경로를 사용한다. 기존 결제액을 상품 현재 가격으로 덮거나 배송비·수수료를 소급 청구하지 않는다.

## 배포와 앱 전환

1. 기존 `095_order_pricing.sql` 적용이 전제다. 이번 고정 배송비 변경에는 추가 스키마 변경이 없다.
2. 앱은 `POST /api/orders/quotes`로 받은 `pricing`을 표시하고, `quoteId`와 `pricing.totalAmount`로 주문을 생성한다. 주문 응답의 저장된 총액이 화면 견적과 같을 때만 토스 결제창을 연다. 견적 만료·상품가·주소 변경 시 새 견적을 표시하고 사용자가 다시 결제하도록 한다.
3. 새 앱과 서버의 적용 시점을 맞춘다. `APP_ORDERS_QUOTE_REQUIRED=true`를 명시한다. 기본값도 true이다. false는 구버전 호환성 확인용이며 배송비 미설정 과거 상품의 기존 주문 경로를 허용하므로 고정 배송비 서비스에서는 사용하지 않는다.
4. 구버전 앱의 견적 없는 신규 주문은 409 `ORDER_095_003`으로 차단된다. 기존 주문의 결제 재시도·취소·환불·정산은 저장된 금액을 보존한다. 정책 변경 전에 생성한 미사용 견적의 배송비가 3,000원과 다르면 409로 거절한다. 같은 금액과 정책의 유효한 견적은 유지한다.
5. 개발 검증은 상품 30,000원·배송 3,000원·구매자 수수료 0원·청구 33,000원·판매자 수수료 1,500원·지급 31,500원을 기준으로 한다. UI 테스트의 토스 SDK 대역과 백엔드 PG 대역은 실제 금융 거래의 증거가 아니다.

## 검증

`OrderPricingHttpTest`는 배송지 HTTP 계약을 상속하고 실제 JWT·DB·HTTP로 견적, 소유권, 조건 변경, 중복 요청, 금액 절사, PG 승인·전액 환불과 정산을 검증한다. PG는 모의 클라이언트이며 실제 청구는 수행하지 않는다. PostgreSQL에서 최소 users/posts/orders만 있는 임시 스키마에 SQL을 두 번 적용해 기존 주문 보존과 배송비·수수료 제약을 확인한다.

```sh
ADDRESS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55493/address93 ./gradlew test --tests '*OrderPricingHttpTest' --no-daemon --max-workers=1
./gradlew test build --no-daemon --max-workers=1
```

DB 인증은 배송지 테스트의 ADDRESS_TEST_DB_USER/PASSWORD를 사용한다. PostgreSQL 실행은 create-drop을 사용하는 전용 폐기 가능 DB만 지정한다. CI의 배송지 전용 PostgreSQL DB에서도 함께 실행된다. 앱 화면, 토스 실거래, 운영 DB 적용·배포는 별도 검증이다.

## 실제 실행 결과

- Java 21 `./gradlew test build --no-daemon --max-workers=1`: 성공. JUnit XML 합계 1,199개, 성공 863개, 실패/오류 0개, 환경 조건 건너뜀 336개.
- 별도 PostgreSQL 14에서 OrderPricingHttpTest 18개 성공, 실패/오류/건너뜀 0개. 최종 응답 필드를 포함한 재실행도 성공했다.
- 실제 PG 대신 모의 클라이언트로 33,000원 승인·전액 환불과 중복 호출 방지, 정산 설정 9%에도 주문 당시 5%·31,500원 지급 유지 등을 확인했다.
- `git diff --check` 통과. 운영 DB 적용·배포와 실제 청구·배송은 수행하지 않았다.
