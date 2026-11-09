# 정산 결과 자동 확인 (#116)

기존 운영자 지급 실행이 최초 지급을 요청한다. 후속 작업은 기존 정산 ID 또는 확인된 지급 ID로 결과를 조회한다. 대기 건에 새 지급을 시작하지 않는다. 금액·통화·수취 대상·외부 식별자가 일치해야 검증 결과를 반영한다.

| 메서드 | 경로 | 응답 |
| --- | --- | --- |
| GET | `/api/admin/settlements?page=0&size=20` | 처리 중·결과 불명·확인된 실패 목록. |
| GET | `/api/admin/settlements/{settlementId}` | 단일 지급 상태와 동기화 상태. |
| POST | `/api/admin/settlements/{settlementId}/refresh` | 후속 조회 예약과 현재 상태. HTTP 202. |

기존 정산 운영자 설정을 사용한다. 고객 화면은 기존 주문 정산 응답의 상태를 사용한다. 운영자 화면에는 `settlementStatus`, `syncState`, `attempts`, `lastCheckedAt`, `nextCheckAt`, `lastError`를 표시한다. 동기화 상태 `QUEUED`, `DONE`, `REVIEW_REQUIRED`와 실제 지급 상태를 따로 표시한다. 아직 등록된 작업이 없으면 `syncState`가 null일 수 있다. `202` 이후 상세를 다시 확인하고 `REVIEW_REQUIRED`이면 운영자가 원인 확인 후 조회를 재예약할 수 있다. 대기·완료 상태는 새 조회 예약이 불가하다.

`116_settlement_sync.sql`을 적용한다. `APP_SETTLEMENT_SYNC_ENABLED`는 기본 false다. true일 때 새 지급 준비 트랜잭션에 작업을 저장하고 기존 진행 건도 배치 등록한다. 작업은 짧은 DB 실행권을 획득한 뒤 잠금 밖에서 조회한다. 재시도는 1분부터 늘어나며 상한은 1시간과 소량의 지터다. 16회 이후 운영자 확인 필요로 표시한다. 배송비 제외 상품 금액 5% 판매자 수수료와 구매자 수수료 0원은 기존 주문 금액 스냅샷을 사용한다.

개발 환경에서 먼저 키·계약·수취 대상 설정을 확인한다. 토스 테스트 환경의 판매자 상태 제한 때문에 모의 지급 성공과 실제 지급 성공은 구분한다. 실제 지급 검증은 구체적인 대상·금액을 정한 뒤 수행한다. [토스 지급대행 가이드](https://docs.tosspayments.com/guides/v2/payouts)

Java 21과 PostgreSQL 14 및 H2에서 정산 관련 30개 테스트가 통과했다. PostgreSQL HTTP 테스트는 최초 지급 요청 통신 오류 이후 수동 예약이 202를 반환하고 작업자가 같은 정산 결과를 조회 한 번으로 반영하는지 확인했다. 실제 지급대행 조회는 설정된 개발 키와 대상 주문으로 배포 후 확인한다.
