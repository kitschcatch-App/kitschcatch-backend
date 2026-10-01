# 주문 취소·배송·환불·구매 확정·정산 API

이슈 [#45](https://github.com/kitschcatch-App/kitschcatch-backend/issues/45). #44 병합 상태에서 시작했다. 총 14개 API와 상태 전이, HTTP·PostgreSQL 검증을 구현했다. 토스 지급대행 v2 어댑터와 검증된 수취인 연결, 반품 승인·거절·철회를 제공한다. [정산·반품 정책](order-payout-return-policy.md)을 적용한다. 실제 토스 송금 검증은 운영 계약·키·수취인 준비 후 진행해야 한다. Notion 원문은 수정하지 않았다.

## 공통 계약

모든 API에 access token이 필요하다. 행위자 ID는 JWT에서 읽으며 수취인 관리 경로의 sellerId만 관리 대상 회원 ID다. DB에 없는 사용자는 401 `AUTH_004`다. 주문 번호는 생성 응답의 공개 `ORD-...` 값이며 내부 숫자 PK가 아니다. 형식은 기존 거래 조회와 동일하다. 요청·조회 권한은 주문 당시 구매자/판매자 스냅샷으로 판단한다. 날짜는 기존 API와 같은 offset 없는 ISO 8601 LocalDateTime이다.

| 메서드·경로 | 권한 | 본문·응답 |
| --- | --- | --- |
| POST `/api/orders/{orderId}/cancel` | 구매자. | `{ "reason": "단순 변심" }` → `orderId`, `status`, `canceledAt`, `processing`. |
| POST `/api/orders/{orderId}/shipment` | 판매자. | `{ "carrierCode": "CJ_LOGISTICS", "trackingNumber": "123456789012" }`. |
| PATCH `/api/orders/{orderId}/shipment` | 판매자. | 같은 두 필드를 모두 전달한다. 누락 필드 유지 방식은 아니다. |
| GET `/api/orders/{orderId}/shipment` | 거래 당사자. | `orderId`, `carrierCode`, `carrierName`, `trackingNumber`, `status`, `registeredAt`, `updatedAt`, `shippedAt`. |
| POST `/api/orders/{orderId}/refunds` | 구매자. | `{ "reason": "상품 설명과 다름", "amount": 12000 }`. 주문 금액 전체만 허용. |
| GET `/api/orders/{orderId}/refunds` | 거래 당사자·반품 운영자. | 기존 필드와 `reviewReason`, `reviewedAt`, `returnReceived`, `withdrawnAt`. |
| POST `/api/orders/{orderId}/refunds/approve` | 반품 운영자. | `{ "refundId": "REF-...", "reason": "반품 검수 확인", "returnReceived": true }`. |
| POST `/api/orders/{orderId}/refunds/reject` | 반품 운영자. | 현재 `refundId`, 거절 `reason`. |
| POST `/api/orders/{orderId}/refunds/withdraw` | 구매자. | 현재 `refundId`. REQUESTED에서만 가능. |
| POST `/api/orders/{orderId}/confirm-purchase` | 구매자. | 본문 없음. `orderId`, `status: PURCHASE_CONFIRMED`, `confirmedAt`. |
| POST `/api/orders/{orderId}/settlement` | 설정된 내부 실행자. | 본문 없음. 지급 제공자·수수료 미설정 시 503. |
| PUT `/api/settlement/recipients/{sellerId}` | 정산 운영자. | `{ "providerSellerId": "토스 Seller ID" }`. 토스 소유 관계와 APPROVED 검증 후 연결. |
| GET `/api/settlement/recipients/{sellerId}` | 본인·정산 운영자. | `sellerId`, `providerSellerId`, `refSellerId`, `verifiedAt`. 계좌 원문 없음. |
| GET `/api/orders/{orderId}/settlement` | 거래 당사자·내부 실행자. | `settlementId`, `orderId`, `sellerId`, `amount`, `fee`, `status`, `requestedAt`, `settledAt`. |

성공은 공통 `success/data` 형식의 HTTP 200이다. 최초 생성 여부에 따라 다른 상태 코드를 반환하지 않는다. PG 요청 실패 시 HTTP 오류가 반환돼도 취소 시도가 이미 저장돼 있을 수 있으므로 상태를 조회한다.

## 취소와 배송

- 사유는 빈 문자열·공백을 허용하지 않으며 최대 200자다. 저장/PG 호출에는 앞뒤 공백을 제거한 값을 사용한다.
- PENDING 주문의 READY/FAILED 결제만 로컬 취소할 수 있다. 준비된 결제 시도는 EXPIRED로 바꾸고 주문/결제와 상품 예약을 함께 해제한다. PROCESSING/UNKNOWN 결제 시도가 있거나 확인이 필요한 결제는 취소하지 않는다.
- PAID 주문은 배송·환불 요청 전에만 취소한다. 기존 PG 결제 취소 시도와 스케줄러/웹훅 복구를 재사용한다. 결과 미확정 중 반복 요청은 PG를 재호출하지 않고 `processing=true`를 반환한다. 완료 후에는 원래 완료 시각을 반환한다.
- 기존 `POST /api/payments/{paymentId}/cancel`도 배송·환불·구매 확정·확인 필요 상태를 검사한다. 이 경로로 새 업무 규칙을 우회할 수 없다.
- 택배사는 현재 명세 예시에 있는 `CJ_LOGISTICS`(CJ대한통운), `HANJIN`(한진택배)을 지원한다. 송장은 하이픈 없는 8~40자리 숫자다. 실제 배송사 송장 유효성을 조회하지 않는다.
- 결제 성공·취소/환불 없음 상태에서 판매자만 등록/수정한다. 같은 송장 재등록은 같은 결과를 반환한다. 다른 송장 재등록은 409이며 변경에는 PATCH를 사용한다. 수정해도 최초 발송 시각은 유지한다.
- 배송사 추적 연동이 없으므로 `SHIPPED`만 반환하며 IN_TRANSIT/DELIVERED를 추정하지 않는다. 주문 자체는 결제 상태 PAID를 유지한다.

## 환불과 구매 확정

환불은 주문당 전액 요청 한 건이다. 같은 금액·정규화된 사유는 같은 요청을 반환하고 다른 사유는 충돌이다. 부분 환불은 거절한다. 현재 결제 수단은 기존 CARD다.

배송 전 환불은 PG 취소를 선점한 뒤 트랜잭션 밖에서 전액 취소를 요청한다. 성공하면 `COMPLETED`, 주문은 `REFUNDED`, 결제는 `CANCELED`가 되며 상품 예약이 해제된다. 타임아웃이나 부분 취소 응답은 완료가 아니며 환불은 `PROCESSING`을 유지한다. 결제 상세의 recoveryState로 운영 확인 필요 여부를 확인한다. 기존 [Toss 결제 취소 API](https://docs.tosspayments.com/reference)를 사용한다.

배송 후 환불은 REQUESTED로 접수한다. 운영자만 실물 반품 수령과 증빙을 확인하여 승인할 수 있으며 `returnReceived=true`와 검수 사유가 필수다. 승인과 PG 취소 시도 저장은 같은 트랜잭션에서 실행하고 PG 호출은 트랜잭션 밖에서 처리한다. PG 전액 취소가 확인되면 COMPLETED로 바꾸며 발송한 상품은 자동 재판매하지 않는다.

운영자는 사유를 남겨 REJECTED로 거절하고, 구매자는 REQUESTED 요청만 WITHDRAWN으로 철회할 수 있다. 승인·거절·철회는 현재 환불 ID와 주문 잠금으로 대조한다. 거절·철회 후에는 구매 확정과 송장 수정이 다시 가능하다. 주문당 한 건 정책에 따라 동일 환불 요청은 거절·철회 후에도 기존 결과를 반환한다. 이의 제기는 고객지원에서 처리한다. 처리자·사유·검수 시각과 철회 시각을 보존한다. PROCESSING/COMPLETED는 철회·거절할 수 없으며 승인 재전송도 PG를 중복 호출하지 않는다.

구매 확정은 구매자가 송장이 등록된 상품을 수령했다고 확인하는 동작이다. 자동 배송 완료 판정이 아니다. 결제 SUCCESS, 진행 중 환불/취소 요청 없음, 확인 필요 표시 없음 조건을 검사하고 `PURCHASE_CONFIRMED`를 저장한다. 반복 요청은 같은 시각을 반환한다. 구매 확정 시 정산 WAITING 기록도 만든다.

기존 거래 상세에 `confirmedAt`, `shipment`, `refund`가 추가된다. 구매·판매 목록의 `status` 필터는 PURCHASE_CONFIRMED를 추가 지원한다. PG가 구매 확정 후 취소 등 다른 결과를 통보하면 주문 확정을 되돌리거나 재판매하지 않고 REVIEW_REQUIRED로 표시해 정산을 차단한다. 이후 DONE 응답이 오더라도 이 확인 필요 표시를 임의 해제하지 않는다.

## 토스 지급대행과 수취인

운영자가 토스 상점관리자에서 계좌·본인확인·KYC를 완료한 셀러를 연결한다. 서버는 회원 ID와 토스 Seller ID만 저장한다. refSellerId는 `KC` + 회원 ID의 대문자 36진수(최소 5자리, 앞쪽 0 채움)다. 연결 시 토스 GET 조회로 식별자와 APPROVED를 대조하며 다른 판매자·부분 승인·KYC 대기 상태를 거절한다. 기존 Seller ID 교체는 허용하지 않는다.

JWT 행위자가 정산 allowlist에 포함돼야 실행할 수 있다. 구매 확정, 결제 SUCCESS, 진행 중 환불 없음, REVIEW_REQUIRED 없음 조건을 재검사한다. 설정이나 키 누락은 503으로 차단하고 수취인 미등록은 404다.

수수료는 기본 5%(500 basis points)이며 `floor(주문금액 × basisPoints / 10000)`다. 최초 실행 때 수수료·지급액·수취인을 고정한다. 이후 설정이 바뀌어도 같은 지급의 금액을 다시 계산하지 않는다. 최초 실행 전 금액은 null이며 사전 검사 실패 후 WAITING에는 저장된 금액이 있을 수 있다.

실제 HTTP 어댑터는 다음 절차를 수행한다.

1. 한국 시간 평일 08:00~15:00, 수취인의 APPROVED·소유 관계, 토스 지급 가능 KRW 잔액을 확인한다. 공휴일 여부는 토스가 최종 판정한다.
2. 고정 settlementId를 refPayoutId와 Idempotency-Key로 사용하여 EXPRESS 지급 한 건을 요청한다. JWE `dir/A256GCM`과 iat·nonce 헤더로 본문을 암호화하고 응답을 복호화한다.
3. 요청 식별자·수취인·정수 금액·통화·업체 지급 ID와 상태를 대조한다. 접수는 PROCESSING이며 COMPLETED 증거가 있어야 완료한다. settledAt은 서버가 완료를 확인한 시각이다.
4. 업체 지급 ID가 있으면 단건 조회하고, 응답 유실로 ID가 없으면 페이지 목록의 refPayoutId로 찾는다. 페이지 한도·반복 커서·불일치는 UNKNOWN/502로 유지한다. 찾지 못해도 새 지급을 보내지 않는다.

송금 POST 전 검사 실패는 WAITING으로 돌아가 같은 식별자·금액으로 재검사할 수 있다. POST를 전송한 이후의 실패·유실은 UNKNOWN 또는 FAILED로 남기며 반복 실행은 조회만 한다. 완료된 결과와 최초 실행 시각을 보존한다. DB 선점 직후 서버 종료, 공휴일 등 POST 오류, 목록에서 확인할 수 없는 지급은 토스 원장을 운영자가 확인해야 한다. 새 식별자로 자동 재송금하지 않는다.

현재 정산 주기 조회·지급 웹훅은 없으며 운영자 POST 재호출로 최신 상태를 확인한다. 토스 지급대행 계약·키·판매자 등록은 운영 준비이며 실제 송금 테스트는 이번 로컬 검증에 포함하지 않는다.

## 오류와 명세 차이

| HTTP·코드 | 조건 |
| --- | --- |
| 400 COMMON_001/002 | 본문 검증 실패 또는 공개 주문 번호 형식 오류. |
| 401 AUTH_004 | 인증 누락/무효, DB에 없는 사용자. |
| 403 ORDER_004 | 거래 당사자/구매자가 아님. |
| 404 ORDER_001 | 주문 없음. |
| 409 ORDER_005/006 | 처리 불가 상태 / 진행 중 요청과 내용 충돌. |
| 404 SHIPMENT_001, 403 SHIPMENT_002 | 배송 정보 없음 / 판매자 아님. |
| 400 REFUND_001, 409 REFUND_002, 404 REFUND_003 | 전액과 다른 환불 금액 / 다른 환불 요청 / 환불 없음. |
| 403 REFUND_004, 400 REFUND_005 | 반품 운영자 권한 없음 / 반품 수령 확인 누락. |
| 404 SETTLEMENT_007, 409 SETTLEMENT_008/009 | 검증된 수취인 없음 / 수취인 검증 실패 / 지급 시간·잔액 확인 필요. |
| 409 SETTLEMENT_001, 404 SETTLEMENT_003 | 정산 불가 상태 / 정산 없음. |
| 403 SETTLEMENT_004, 503 SETTLEMENT_005 | 내부 실행자 아님 / 지급 제공자·수수료 없음. |
| 502 SETTLEMENT_006 | 지급 호출·조회 실패. UNKNOWN 저장 후 조회 복구. |
| 400 PAYMENT_003/005, 500 PAYMENT_004 | 기존 결제 상태 오류 / PG 요청 실패 / Toss 설정 누락. |

원문의 ORDER_002/003은 기존 예약 오류와 충돌하므로 재사용하지 않았다. 배송 입력 오류는 공통 Bean Validation의 COMMON_001로 통일했다. 정산·환불·배송의 반복 요청은 동일 결과를 반환하며 다른 내용은 충돌로 구분한다. 원문의 정산 예시 COMPLETED, 배송 조회 IN_TRANSIT, 환불 요청 REQUESTED를 모든 상황의 고정 성공 응답으로 사용하지 않는다.

## 검증

ISSUE27/31/39/41/43/45 격리 PostgreSQL 검증을 모두 활성화한 `./gradlew test build --console=plain`이 전체 **705개, 실패 0, 오류 0, 건너뜀 0**, 빌드 성공으로 통과했다. 최초 clean 회귀에서 테스트 대역 재설정 오류 2개를 수정한 뒤 전체 회귀를 다시 실행했다. H2와 수동 SQL을 두 번 적용한 격리 PostgreSQL의 실제 HTTP·JWT 계약, 반품 승인/철회·정산 동시성, PG 유실 복구를 검증했다. 로컬 HTTP 지급 서버로 Basic 인증·JWE 요청과 응답·금액/수취인 대조·페이지 조회·타임아웃을 검증한다. 추가 SQL은 검수 이력과 수취인 스냅샷 보존, 회원 참조·유일 제약, 오류 시 롤백을 검증한다.

운영 DB 적용·배포, 실제 PG 결제/환불, 판매자 지급, 배송사 추적, 실제 S3 검증은 수행하지 않았다. [환경 변수·배포 순서](order-lifecycle-environment.md).
