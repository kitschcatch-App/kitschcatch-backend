# #54 결제 복구 응답·PG 오류·PostgreSQL 검증.

- 이슈: [#54](https://github.com/kitschcatch-App/kitschcatch-backend/issues/54).
- 기준: develop의 `5f6d18fb2d4d8fc1e2f435919a548c64d33c9627`. 병합된 #29/#30의 후속 보완.
- 기존 [설계](plans/2026-09-16-payment-recovery.md)의 제안 설정 전체를 구현 완료로 간주하지 않는다.
- Notion 원문은 접근되지 않아 저장소 DTO·테스트·기존 설계와 이슈를 기준으로 구현했다. 외부 명세는 수정하지 않았다.

## HTTP 계약.

| 요청 | 결과 | HTTP 및 앱 동작 |
| --- | --- | --- |
| POST confirm / cancel | PROCESSING | 202. success=true, 기존 data 형식과 paymentId 유지. GET으로 상태를 다시 확인한다. |
| POST confirm | SUCCESS / 확정 FAILED | 200. data.status로 결제 성공·실패를 구분한다. failureCode와 retryAllowed를 확인한다. |
| POST cancel | CANCELED | 200. 이미 취소된 결제 재요청도 완료 상태를 반환한다. |
| GET payment | 모든 상태 | 기존 200 DB 조회. PG를 호출하지 않는다. |
| POST retry | 새 시도 또는 기존 후속 시도 | 기존 200. 유효한 현재 PREPARED 시도에만 OPEN_PAYMENT_WINDOW를 반환한다. |

202는 결제 성공이나 실패 확정을 뜻하지 않는다. 200만 처리했던 클라이언트는 202를 정상 수신하고 data.status를 읽어야 한다. 모바일 소스는 이 저장소에 없어 실제 앱 적용은 미검증이다. 승인 거절 응답의 success=true는 요청 처리 완료를 뜻하며 결제 승인 성공은 data.status=SUCCESS로 판단한다.

첫 승인 시도(sequence=1)에 한해 attemptId 생략을 허용한다. 재시도부터 발급된 attemptId와 새 paymentKey가 필요하다. 같은 현재 시도·동일 키의 처리 중/완료 승인 재요청은 PG를 호출하지 않는다. 다른 키·오래된 시도·타인의 결제는 기존 비즈니스 오류/404 정책으로 차단한다. 내부 orderId 의미는 유지하고 앱 결제창은 발급된 pgOrderId를 사용해야 한다.

## PG 오류 판정.

[Toss 공식 오류 계약](https://docs.tosspayments.com/reference/error-codes)의 카드 승인 오류 중 HTTP 403과 REJECT_CARD_PAYMENT 또는 REJECT_CARD_COMPANY 조합만 해당 승인 요청의 확정 거절로 처리한다. 이 프로젝트의 보수적인 허용 목록이며 HTTP 4xx 전체를 실패로 묶지 않는다.

- 승인 확정 거절은 현재 승인 시도만 FAILED로 변경한다. 예약이 유효하면 재시도 준비가 가능하고, 이미 만료됐으면 기존 주문 만료 규칙으로 종료한다.
- ALREADY_PROCESSED_PAYMENT, ALREADY_PROCESSING_REQUEST, PROVIDER_ERROR, 인증/설정 오류, NOT_FOUND_PAYMENT, 429, 5xx, 미등록 코드, 잘못된 JSON, 통신/타임아웃 오류는 불확실 상태다. 동일 승인 재전송·새 재시도·예약 해제를 허용하지 않는다.
- 조회·취소에서 같은 거절 코드가 나와도 승인 실패 근거로 사용하지 않는다. 취소 불확실은 PAID/SOLD_OUT을 보호하며 자동 취소 재전송도 하지 않는다.
- PG 예외는 안전한 코드와 확정 여부만 담는다. 원문 message, HTTP 오류 본문, 전송 예외 상세는 공개 응답에 노출하지 않는다.
- PG 성공 응답도 식별자·금액·상태를 확인한다. 성공 결제의 외부 취소 재조회에서도 같은 검증을 먼저 수행한다.

## 작업자와 상태 보호.

상품 → 주문 → 결제 → 시도 잠금 순서를 유지한다. 외부 HTTP 중 DB 잠금은 보유하지 않는다. 시도 claim 트랜잭션은 시도만 잠그고 종료한다. PG 결과 반영은 현재 시도와 종료 상태를 다시 검사한다. 정상 승인과 웹훅/스케줄러 경합에서 취소 완료가 늦은 DONE으로 되돌아가지 않는다.

웹훅과 주기 워커는 동일 시도 lease를 공유하고, PG 조회는 저장된 시도의 식별자를 사용한다. 유효한 token과 만료 전 lease가 있는 작업자만 결과·조회 실패를 반영한다. 수신함 완료/재예약에도 lease token과 만료를 검사한다.

이전 실패 시도의 모순 성공은 REVIEW_REQUIRED로 남기되 과거 FAILED/SUCCEEDED 이력을 UNKNOWN으로 바꾸지 않는다. 현재 PREPARED 시도와 부분 유일 인덱스의 충돌을 방지한다. 운영 확인 표시가 있으면 READY/FAILED 예약도 만료시키지 않는다. 불확실 결과는 재조회 시각을 뒤로 보내 다른 후보가 처리될 수 있게 한다.

## 실제 설정과 #45 호환.

| 설정 또는 동작 | 현재 값 |
| --- | --- |
| app.payments.recovery.enabled | false. 명시적으로 true일 때만 주기/웹훅 워커 생성. |
| app.payments.recovery.scan-delay / success-scan-delay | 30s / 1h. 성공 결제는 마지막 검증 1시간 이후 조회. |
| app.payments.recovery.batch-size | 100. |
| 시도·수신함 lease | 코드의 45초. |
| 불확실 재조회 | 30초부터 지수 증가, 최대 900초. |
| 운영 확인 상태의 지원 밖 결과 | 15분 뒤 재조회. |
| app.toss-payments.connect-timeout / read-timeout | 3s / 10s. APP_TOSS_PAYMENTS_CONNECT_TIMEOUT / APP_TOSS_PAYMENTS_READ_TIMEOUT으로 변경. 양수만 허용. |

read timeout은 lease보다 짧게 유지해야 한다. 후보 조회는 애플리케이션 시각을 바인딩한다. 기존 LocalDateTime 저장 정책을 유지하며 과거 PG 시각을 일괄 재해석하지 않는다.

#45는 같은 PaymentService, PaymentTransactionService, PaymentRecoveryService와 예약 만료 경로를 재사용할 수 있다. PROCESSING은 CONFIRM/CANCEL을 구분하고, 불확실 상태에서 환불 완료·예약 해제·재판매로 전환하면 안 된다. 이 PR은 #45 배송/환불/정산 정책이나 미커밋 코드를 포함하지 않는다. 실제 Toss/S3·운영 DB·알림 전송은 별도 검증이며 보류 상태를 유지한다.

## 029 SQL 적용 계약.

`src/main/resources/db/manual/029_payment_recovery.sql`은 쓰기 중단과 백업을 전제로 한 수동 트랜잭션이다. 사전 키/주문 중복·금액/상태 모순 검사, 구버전 payment_status CHECK의 FAILED 확장, 부분 유일 인덱스, backfill, 완료 표식까지 원자적으로 적용한다.

READY는 PREPARED, SUCCESS는 SUCCEEDED, PROCESSING/PENDING은 승인 미확정, PROCESSING/PAID는 취소 미확정과 별도 승인 이력으로 보완한다. 구 승인·취소 멱등키는 confirm-/cancel-와 기존 paymentKey 조합으로 보존한다. 구 시도 ID는 LEGACY-<내부 PK>로 50자 제한을 넘지 않는다. CANCELED 구 데이터에 가짜 PG 거래 이력을 만들지 않는다.

완료 표식 payment_recovery_029_applied가 있으면 재실행은 상태·시도·키를 변경하지 않는다. 실패 시 표식/DDL/backfill을 모두 롤백한다. 초안 029를 이미 적용한 DB에는 수정본을 그대로 덧씌우지 말고 설치 상태를 확인해야 한다. 이번 작업은 기존 배포 DB를 확인하거나 변경하지 않았다. 구버전 서버로의 단순 바이너리 롤백과 이력 삭제 SQL은 제공하지 않는다.

## 검증과 제한.

PostgreSQL 14.18의 채팅 전용 인스턴스(루프백 55454)·DB와 매번 새 issue54 스키마를 사용한다. 새 테스트는 ISSUE54_TEST_DB_URL, ISSUE54_TEST_DB_USER, ISSUE54_TEST_DB_PASSWORD를 받는다. URL이 없으면 PostgreSQL 테스트는 skipped다. ISSUE27 회귀는 테이블을 생성/삭제하므로 별도 전용 DB에서만 실행해야 한다.

- 실제 029 SQL 적용·재실행, 활성 시도 부분 유일 제약, 사전 검사 및 backfill 이후 실패의 전체 롤백.
- 실제 PostgreSQL 복수 워커 조회 한 번, lease 만료/인계와 이전 토큰 성공/오류 폐기, 동시 재시도 한 건.
- 불확실 결과의 재승인/만료 차단, 과거 실패 시도의 모순 성공 보호, 첫 시도 호환과 재시도 attemptId 필수.
- 웹훅 취소와 늦은 승인 경합, 결과 반영 후 수신함 재처리, 버려진 수신함 lease 인계.
- 로컬 HTTP PG 대역을 사용한 서버 컨텍스트 종료/재시작 복구와 재승인 0회, 실제 read timeout.
- HTTP 오류 코드/메서드/상태 조합과 원문 비노출, MockMvc confirm/cancel 202 및 GET 200.

전체 테스트·빌드와 독립 리뷰의 최종 결과는 PR에 기록한다. 다른 기능의 선택적 PostgreSQL 테스트는 해당 DB 환경 변수를 지정하지 않아 skipped일 수 있다. 실제 Toss 승인/조회/취소, HTTPS 웹훅, 운영 적용/배포, 프로세스 강제 종료/OS 재부팅, 모바일 UX는 수행하지 않았다. 재시작 증거는 테스트 JVM에서 HTTP 서버 컨텍스트를 닫고 새 컨텍스트로 시작한 범위다.
