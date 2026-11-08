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

상품 생성·수정 JSON과 상품 조회 응답에 `shippingAmount`를 추가했다. null/생략은 기존 미설정·값 보존, 0은 무료, 양수는 고정 선불 배송비다. 기존 상품 배송비는 무료로 추정하지 않는다. 배송비 설정 전에는 견적을 생성할 수 없다.

`GET /api/posts/{postId}/shipping`, 판매자 전용 `PATCH /api/posts/{postId}/shipping`도 제공하며 PATCH 본문은 `{"shippingAmount":3000}`이다. 진행 주문 또는 판매 중이 아닌 상품의 배송비 변경은 차단한다. 지역별 추가 운임·착불은 이 계약의 고정 운임과 구분해 별도 정책이 필요하다.

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

서버가 사용자 잠금·견적 잠금·상품 잠금 안에서 견적 소유권, 입력 금액, 상품·주소 일치, 만료, 상품가격·배송비·정책·주소 수정 시각을 재검증한다. 변경되면 새 견적을 받도록 409를 반환한다. 동일 견적의 동시 주문 생성은 한 주문으로 직렬화한다. 동일 주문 재요청은 이미 생성된 주문을 반환하고, 만료·취소된 주문을 다시 예약하지 않는다.

생성 응답과 주문 상세에 `pricing`을 추가했다. 앱은 주문 생성 응답의 `pricing.totalAmount`로 PG SDK를 호출한다. 기존 결제 생성 요청의 amount도 같은 총액이어야 한다. PG 승인에는 DB에 저장된 결제 금액을 사용하고 기존 승인·웹훅·복구 대조 로직이 payment/attempt 금액과 PG totalAmount를 검사한다.

- `ORDER_095_001` 409: 본인 견적이 없거나 견적 입력·만료·금액 조건이 달라짐.
- `ORDER_095_002` 409: 상품 배송비 미설정.
- `ORDER_095_003` 409: 견적을 지원하는 주문 계약으로 전환 필요.
- 형식·금액 범위 오류는 400, 인증은 401, 배송지 소유권은 기존 주소록의 404, 타인 상품 수정은 403이다.

## 환불·정산과 기존 주문

amount의 의미는 최종 청구액이다. Payment와 PaymentAttempt는 해당 금액을 저장한다. 기존 전액 환불 경로에서 상품금액만 보내면 거절하고 실제 결제 총액이 일치해야 한다. PG 전액 취소는 배송비를 포함한 총액을 환불한다. 반품 운임은 기존 정책대로 별도 처리하며 부분 환불로 임의 차감하지 않는다. 승인·취소·재시도 멱등키와 상태 머신을 유지한다.

새 주문은 정산 시 저장된 sellerFeeAmount와 sellerPayoutAmount를 사용한다. 배송비에는 판매자 수수료가 붙지 않는다. 기존 주문은 pricing/quoteId가 null인 레거시로 유지하고 이미 저장된 정산 금액을 우선하며 기존 정산 계산 경로를 사용한다. 기존 결제액을 상품 현재 가격으로 덮거나 배송비·수수료를 소급 청구하지 않는다.

## 배포와 앱 전환

1. 기존 주문·상품·회원 테이블에 `095_order_pricing.sql`을 코드 배포 전에 적용한다. 반복 적용 가능하며 기존 amount·정산 값은 보존한다. SQL에는 원 단위 합계·5% 절사·양수 지급액과 운임 제약이 있다.
2. 서버와 앱에 배송비 입력·견적·총액 표시·결제 SDK 전달을 연결한다. 판매자가 기존 상품의 배송 조건을 명시한다.
3. `APP_ORDERS_QUOTE_REQUIRED` 기본 false는 이전 앱의 미설정 상품에 대한 기존 주문 계약을 유지한다. 배송비가 설정된 상품은 이 값과 무관하게 quoteId가 필수다. 추가비용을 누락한 주문을 허용하지 않는다.
4. 앱 전환 후 `APP_ORDERS_QUOTE_REQUIRED=true`로 모든 신규 주문에 견적을 요구한다. 기존 주문 결제·환불에는 소급하지 않는다.

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
