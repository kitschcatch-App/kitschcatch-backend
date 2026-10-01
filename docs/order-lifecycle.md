# 주문 취소·배송·환불·구매 확정·정산 API

이슈 [#45](https://github.com/kitschcatch-App/kitschcatch-backend/issues/45). #44 병합 상태에서 시작했다. 아래 9개 경로와 상태 전이, HTTP·PostgreSQL 검증을 추가했다. **실제 판매자 지급 연동과 배송 후 반품 확인·승인은 미완료다.** 기본 정산 제공자는 지급을 차단한다. Notion 원문은 수정하지 않았다.

## 공통 계약

모든 API에 access token이 필요하다. 사용자 ID를 요청으로 받지 않으며 DB에 없는 사용자는 401 `AUTH_004`다. 주문 번호는 생성 응답의 공개 `ORD-...` 값이며 내부 숫자 PK가 아니다. 형식은 기존 거래 조회와 동일하다. 요청·조회 권한은 주문 당시 구매자/판매자 스냅샷으로 판단한다. 날짜는 기존 API와 같은 offset 없는 ISO 8601 LocalDateTime이다.

| 메서드·경로 | 권한 | 본문·응답 |
| --- | --- | --- |
| POST `/api/orders/{orderId}/cancel` | 구매자. | `{ "reason": "단순 변심" }` → `orderId`, `status`, `canceledAt`, `processing`. |
| POST `/api/orders/{orderId}/shipment` | 판매자. | `{ "carrierCode": "CJ_LOGISTICS", "trackingNumber": "123456789012" }`. |
| PATCH `/api/orders/{orderId}/shipment` | 판매자. | 같은 두 필드를 모두 전달한다. 누락 필드 유지 방식은 아니다. |
| GET `/api/orders/{orderId}/shipment` | 거래 당사자. | `orderId`, `carrierCode`, `carrierName`, `trackingNumber`, `status`, `registeredAt`, `updatedAt`, `shippedAt`. |
| POST `/api/orders/{orderId}/refunds` | 구매자. | `{ "reason": "상품 설명과 다름", "amount": 12000 }`. 주문 금액 전체만 허용. |
| GET `/api/orders/{orderId}/refunds` | 거래 당사자. | `refundId`, `orderId`, `amount`, `status`, `reason`, `requestedAt`, `completedAt`. |
| POST `/api/orders/{orderId}/confirm-purchase` | 구매자. | 본문 없음. `orderId`, `status: PURCHASE_CONFIRMED`, `confirmedAt`. |
| POST `/api/orders/{orderId}/settlement` | 설정된 내부 실행자. | 본문 없음. 지급 제공자·수수료 미설정 시 503. |
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

**배송 후 환불은 REQUESTED 접수까지만 구현했다.** 반품 확인/승인 주체·정책이 명세에 없어 구매자의 요청만으로 PG 취소를 실행하지 않는다. 승인·거절·요청 철회 API와 반품 확인 절차는 후속 구현이 필요하다. 이 상태에서는 구매 확정과 송장 변경이 차단된다. 실제 PG 전액 취소가 외부에서 확인되면 기존 복구 경로로 완료될 수 있지만 발송한 상품은 자동 재판매하지 않는다. DB를 직접 수정하는 승인 절차는 제공하지 않는다.

구매 확정은 구매자가 송장이 등록된 상품을 수령했다고 확인하는 동작이다. 자동 배송 완료 판정이 아니다. 결제 SUCCESS, 환불/취소 요청 없음, 확인 필요 표시 없음 조건을 검사하고 `PURCHASE_CONFIRMED`를 저장한다. 반복 요청은 같은 시각을 반환한다. 구매 확정 시 정산 WAITING 기록도 만든다.

기존 거래 상세에 `confirmedAt`, `shipment`, `refund`가 추가된다. 구매·판매 목록의 `status` 필터는 PURCHASE_CONFIRMED를 추가 지원한다. PG가 구매 확정 후 취소 등 다른 결과를 통보하면 주문 확정을 되돌리거나 재판매하지 않고 REVIEW_REQUIRED로 표시해 정산을 차단한다. 이후 DONE 응답이 오더라도 이 확인 필요 표시를 임의 해제하지 않는다.

## 정산 실행과 미완료 연동

정산 금액/수수료는 WAITING에서 아직 정해지지 않았으면 null이다. 기본 구현에는 실제 지급 업체 어댑터가 없으며 환경 변수만 채워도 실제 송금은 활성화되지 않는다. 내부 실행자 요청에도 503 `SETTLEMENT_005`를 반환하고 WAITING을 유지한다.

`SettlementGateway`는 실제 지급 제공자 연동 계약이다. 어댑터는 다음을 충족해야 한다.

1. 내부 sellerId에 대응하는 검증된 지급 대상과 수취 정보를 조회한다. 클라이언트가 임의의 수취 계좌/판매자를 전달하지 않는다.
2. `settlementId`를 안정적인 지급 멱등 식별자로 사용한다. 총 주문 금액에서 저장된 수수료를 뺀 KRW 금액을 지급한다.
3. 요청 결과 미확정 시 같은 식별자로 조회한다. 조회 결과가 없다고 새 지급을 시작하지 않는다.
4. 실제 지급이 완료된 경우에만 COMPLETED와 지급 참조·완료 시각·금액·통화를 반환한다. 접수 성공을 지급 완료로 처리하지 않는다.
5. 업체의 타임아웃·인증·암호화·상태 조회/복구 계약을 구현하고 테스트 환경에서 실제 검증한다.

내부 실행자 allowlist는 기본 빈 집합이다. JWT의 사용자 ID가 설정에 포함돼야 실행할 수 있다. 일반 구매자/판매자는 조회만 가능하다. 구매 확정, 결제 SUCCESS, 환불 없음과 확인 필요 없음 조건을 재검사한다.

수수료율은 확정된 설정이 있어야 하며 기본값을 임의로 정하지 않았다. 첫 실행 시 `floor(주문 금액 × basisPoints / 10000)`를 저장하고 이후 설정이 바뀌어도 진행 중 금액을 다시 계산하지 않는다. 테스트의 500(5%)는 검증용 값이며 운영 정책이 아니다.

`WAITING → PROCESSING → COMPLETED/FAILED/UNKNOWN`을 구분한다. 첫 실행만 지급 요청을 호출하며, 이후 실행은 기존 지급 조회만 한다. 서버가 선점 저장 후 외부 호출 전에 종료된 경우도 조회 또는 운영 확인으로 해결해야 하며 자동 재송금하지 않는다. 확정 실패의 새 지급 재시도·정산 스케줄러·판매자 계좌 등록은 이번 범위에 없다. 실제 지급 연동 검증 전에는 이 기능을 정산 완료로 표시하지 않는다.

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
| 409 SETTLEMENT_001, 404 SETTLEMENT_003 | 정산 불가 상태 / 정산 없음. |
| 403 SETTLEMENT_004, 503 SETTLEMENT_005 | 내부 실행자 아님 / 지급 제공자·수수료 없음. |
| 502 SETTLEMENT_006 | 지급 호출·조회 실패. UNKNOWN 저장 후 조회 복구. |
| 400 PAYMENT_003/005, 500 PAYMENT_004 | 기존 결제 상태 오류 / PG 요청 실패 / Toss 설정 누락. |

원문의 ORDER_002/003은 기존 예약 오류와 충돌하므로 재사용하지 않았다. 배송 입력 오류는 공통 Bean Validation의 COMMON_001로 통일했다. 정산·환불·배송의 반복 요청은 동일 결과를 반환하며 다른 내용은 충돌로 구분한다. 원문의 정산 예시 COMPLETED, 배송 조회 IN_TRANSIT, 환불 요청 REQUESTED를 모든 상황의 고정 성공 응답으로 사용하지 않는다.

## 검증

기능 테스트 93개: H2 HTTP 45개, 실제 수동 SQL을 적용한 PostgreSQL HTTP 44개, SQL 전용 4개가 실패·오류·건너뜀 없이 통과했다. ISSUE27/31/39/41/43/45 PostgreSQL 변수를 모두 지정한 `./gradlew clean test build --console=plain`은 전체 663개, 실패 0, 오류 0, 건너뜀 0으로 통과했다. 검증 후 임시 이슈 스키마 0개와 격리 PostgreSQL 종료를 확인했다.

권한·전액/상태·중복·동시 요청, PG 실패 후 복구, 실제 지급 대역의 금액·통화·식별자·지급 참조 검증, 트랜잭션 밖 외부 호출, 기존 API 취소 우회 차단을 확인했다. SQL은 재실행 시 취소 이력·환불 정보를 보존하고 기존 주문의 송장/구매 확정을 만들지 않는다. 알 수 없는 주문 상태가 있으면 DDL 전체가 롤백된다.

운영 DB 적용·배포, 실제 PG 결제/환불, 판매자 지급, 배송사 추적, 실제 S3 검증은 수행하지 않았다. [환경 변수·배포 순서](order-lifecycle-environment.md).
