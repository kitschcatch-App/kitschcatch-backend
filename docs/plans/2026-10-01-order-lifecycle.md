# 주문 취소·배송·환불·구매 확정·정산 구현 계획

- 이슈: [#45](https://github.com/kitschcatch-App/kitschcatch-backend/issues/45).
- 기준: #44가 병합된 develop `5f6d18f`, 작업 브랜치 `feat/45`.
- Notion의 9개 요청·응답·오류 명세를 2026-10-01 확인했다.

## 구현 계약

공개 `ORD-...` 주문 번호와 JWT 사용자 ID를 사용한다. 기존 상품 → 주문 → 결제 → 결제 시도 잠금 순서를 유지하고 외부 PG 호출은 트랜잭션 밖에서 실행한다. 신규 정보는 주문의 부속 값으로 보존해 조회 시 변경된 상품 정보에 의존하지 않는다.

| API | 계약 |
| --- | --- |
| POST `/api/orders/{orderId}/cancel` | 구매자만 취소. 미결제 주문은 예약과 준비된 결제 시도를 함께 해제. 결제 완료 주문은 배송 전에 기존 PG 취소·복구로 연결. 중복 요청은 기존 결과 반환. |
| POST/PATCH/GET `/api/orders/{orderId}/shipment` | 판매자 등록·수정, 당사자 조회. 송장 등록 시 SHIPPED. 배송사 추적 연동이 없는 상태에서 IN_TRANSIT/DELIVERED를 추정하지 않는다. |
| POST/GET `/api/orders/{orderId}/refunds` | 구매자 전액 환불 요청, 당사자 조회. 주문 금액과 일치 검증. PG 결과 불확실 상태와 완료를 구분한다. 배송 후 자동 지급 정책은 사용자 확인 대기. |
| POST `/api/orders/{orderId}/confirm-purchase` | 구매자 수령 확인, 송장 등록·결제 성공·환불 없음 확인. PURCHASE_CONFIRMED 및 시각 저장. 반복 요청은 동일 결과. |
| POST/GET `/api/orders/{orderId}/settlement` | 정산 실행과 조회. 실제 지급 제공자·수취 정보·수수료 정책은 사용자 확인 대기. 미연동을 지급 완료로 표시하지 않는다. |

취소·환불·확정·배송 경쟁은 같은 주문 잠금으로 직렬화한다. 기존 `/api/payments/{paymentId}/cancel`에도 같은 취소 가능 조건을 적용한다. PG 웹훅과 복구가 늦게 도착해 배송 이후 상품을 임의 재판매하지 않도록 완료 전이를 점검한다.

## 명세 보완

- `ORDER_002/003`은 기존 예약 오류와 충돌하므로 접근 오류는 기존 `ORDER_004`, 업무 상태 오류는 신규 코드로 구분한다.
- 동일 취소·배송 등록·환불·구매 확정 반복 요청은 같은 결과를 반환하고 내용이 달라 충돌하면 409를 반환한다.
- 기존 주문 상태는 결제와 일치시키고 배송 상태를 별도로 제공한다. 구매 확정 상태는 거래 내역 필터에도 추가한다.
- 환불/정산 승인을 비롯해 원문에 없는 정책을 임의의 실자금 지급으로 구현하지 않는다.

## 검증 및 커밋

실제 HTTP·JWT 권한/입력/상태 계약, PG 실패 후 재조회 복구, 두 요청의 경쟁, 기존 경로 우회, 격리 PostgreSQL 수동 SQL 적용·재실행을 검증한다. 전체 회귀와 빌드를 완료한다. 기능별 구현과 해당 검증을 묶어 취소, 배송, 환불, 확정·정산 단위로 커밋하며 최종 운영 문서는 별도 묶음으로 남긴다. Obsidian 작업 기록과 환경 변수 노트를 작성하고 템플릿을 따른 develop 대상 Open PR을 만든다.

## 구현 결과 · 2026-10-01

- 주문 취소·배송·전액 환불·구매 확정 API와 정산 대기/조회/내부 실행 계약을 구현했다.
- 배송 후 환불은 REQUESTED 저장까지만 제공한다. 반품 확인·승인 정책은 답변을 기다리는 상태이며 실제 환불 자동 실행을 추가하지 않았다.
- 실제 지급 제공자·판매자 수취 정보·수수료 정책도 미확정이다. 정산은 WAITING 기록과 실행/결과 검증 구조까지 구현했으며 기본 어댑터는 지급을 차단한다. 이 부분을 실제 지급 연동 완료로 보지 않는다.
- H2 HTTP 45개, 수동 SQL 적용 PostgreSQL HTTP 44개, SQL 4개를 검증했다. 전체 회귀는 663개, 실패·오류·건너뜀 0, 빌드 성공이다.
- 구현 문서: [거래 후속 처리 API](../order-lifecycle.md), [환경 변수·SQL 적용](../order-lifecycle-environment.md).
- 커밋은 취소·배송, 환불·구매 확정, 정산 실행 기반, 최종 문서 묶음으로 구성했다.

## 확인한 Notion 원문

- [주문 취소](https://app.notion.com/p/3c1ee6172f56800fa2e3c43b4080676a).
- 배송 [등록](https://app.notion.com/p/3c1ee6172f56804593a2e612951046b2), [수정](https://app.notion.com/p/3c1ee6172f5680b39f85ff393a05b5c3), [조회](https://app.notion.com/p/3c1ee6172f568053baefdbfde04c9f50).
- 환불 [요청](https://app.notion.com/p/3c1ee6172f5680708226ddb9c9c12e78), [조회](https://app.notion.com/p/3c1ee6172f56802cb5ddeec53be14951).
- [구매 확정](https://app.notion.com/p/3c1ee6172f5680e2afa5f53238f2c912).
- 정산 [실행](https://app.notion.com/p/3c1ee6172f568090b98bdf07603b7ba9), [조회](https://app.notion.com/p/3c1ee6172f56802690ccd2882d97baec).
