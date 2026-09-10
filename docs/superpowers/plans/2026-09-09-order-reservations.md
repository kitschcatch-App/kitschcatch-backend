# 주문 예약과 중복 판매 방지 구현 계획

**목표.** 이슈 #27의 예약·결제 상태 연동, 거래 중 상품 변경 제한, 주문 당시 정보 보존을 `feat/27`에서 구현한다.

**명세.** https://github.com/kitschcatch-App/kitschcatch-backend/issues/27

**위험도.** HIGH. 상품·주문·결제의 동시성과 스키마 변경이 연결된 하나의 작업이므로 메인 에이전트가 구현하고 독립 Sol 에이전트가 검토한다. 사용자가 이슈 구현을 승인했으므로 별도 설계 승인 없이 진행한다.

## 결정 사항

- 상품 행을 공통 잠금 지점으로 사용한다. 모든 관련 쓰기는 `Post → PurchaseOrder → Payment` 순서로 잠근다. 잠금 전에는 ID 스칼라만 조회하고 연관 엔티티를 미리 읽지 않는다.
- `Post.activeOrderNumber`로 예약/판매의 소유 주문을 기록한다. 결제 완료 후에도 유지하고 해당 주문의 취소 또는 미결제 만료에서만 해제한다.
- 기본 예약 기간은 15분, 만료 점검은 60초 간격이다. `PENDING`이고 만료된 주문 중 `READY` 결제만 개별 트랜잭션에서 취소한다. 결제 처리 중이거나 결과가 불확실하면 해제하지 않는다.
- 주문당 결제 한 건을 유지한다. 기존 결제 생성 API는 해당 주문의 READY 결제를 재사용한다. 별도 재시도/웹훅 기능은 이번 범위가 아니다.
- 외부 PG 호출은 DB 트랜잭션 밖에서 수행한다. 외부 호출/후속 DB 저장 오류는 PROCESSING을 유지하여 안전한 재조회·복구 전까지 예약을 보호한다. 승인 요청 paymentKey도 먼저 보존한다.
- 상품 ID·상품명·거래 금액·판매자 ID·닉네임을 주문에 보존한다. 상품·프로필 변경이 주문 스냅샷을 변경하지 않는다.
- 상품 수정·삭제는 동일 Post 잠금 아래 소유 주문과 PENDING/PAID 주문 존재 여부를 검사한다. 수동 예약 등 주문과 무관한 기존 판매 상태 관리 동작은 유지한다.
- 기존 배포 데이터에는 수동 적용 PostgreSQL 변경 스크립트를 제공한다. 기존 중복 결제/활성 주문은 자동 삭제하지 않고 적용을 중단한다. 과거 데이터는 당시 정보를 복원할 수 없으므로 현재 값으로 보완한다. 기존 결제는 PG 결과 확인 후 전환하며 만료 시각을 임의로 지정하지 않는다.

## 구현 및 확인 순서

- [x] 현재 소스·이슈·작업 트리 확인 및 `feat/27` 생성.
- [x] 기존 `OrderPaymentServiceTest`, `PostServiceTest` 실행. 통과.
- [x] 실제 DB 통합 테스트를 먼저 추가하고 예약·중복 판매·상품 변경 차단·불확실 결제 보호 실패 확인.
- [x] `Post`, `PurchaseOrder`, `Payment` 및 저장소에 소유권·스냅샷·잠금·단일 결제 제약 구현.
- [x] `OrderService`, `PaymentTransactionService`, `PaymentService`, `PostService`에 상태 연동과 변경 제한 구현.
- [x] `OrderReservationService`, `OrderReservationScheduler`, 설정에 안전한 만료 처리 구현.
- [x] 만료 경계·과거 주문·스냅샷·중복 승인·수정 경합 테스트 추가 후 통과 확인.
- [x] 수동 DB 변경 스크립트와 동작/운영 제한 문서 작성.
- [x] H2 테스트와 격리된 임시 PostgreSQL에서 같은 통합 테스트 실행. 실제 HTTP 주문→결제→취소 흐름은 테스트 PG를 사용해 확인.
- [x] 전체 `./gradlew test`와 `./gradlew build` 실행, 코드 그래프 갱신 후 독립 Sol 검토.
- [x] 독립 검토 차단 사항 없음 확인. 구현·테스트·문서를 한 논리 변경으로 커밋한다.

## 검증 기록

- 기존 단위 테스트 실행 명령. `./gradlew test --tests '*OrderPaymentServiceTest' --tests '*PostServiceTest'`. 결과 통과.
- 작업 전 사용자 변경은 추적되지 않은 `.DS_Store`뿐이며 수정하지 않는다.
- PostgreSQL 실행 도구가 로컬에 설치되어 있으므로 외부 DB에 연결하지 않고 임시 DB로 검증한다.
- 최초 예약 통합 테스트 7개 중 6개가 기대 동작 불일치로 실패한 뒤 구현 후 통과했다. 테스트 준비 데이터의 삭제 flush 문제는 먼저 수정하고 실제 실패를 재확인했다.
- `./gradlew test`. 143개 테스트 통과, 실패/오류 없음. 실제 HTTP 서버 + JWT 인증 + DB를 사용하는 `OrderReservationHttpTest` 포함. PG와 S3만 테스트 대역을 사용한다.
- `ISSUE27_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55427/issue27_test ISSUE27_TEST_DB_DRIVER=org.postgresql.Driver ISSUE27_TEST_DB_USER=issue27 ./gradlew test --tests '*OrderReservationIntegrationTest' --rerun-tasks`. 임시 PostgreSQL에서 16개 통합 테스트 통과.
- PostgreSQL 검증에는 서로 다른 구매자 6명의 동시 주문, 결제 외부 호출 중 만료/중복 승인, 상품 수정 대기, 결제당 DB 유일 제약을 포함한다.
- `psql -v ON_ERROR_STOP=1`로 임시 `issue27_migration` DB에 기존 스키마/3개 주문 fixture → 실제 변경 스크립트 → 보존/예약/유일 제약 검증을 실행했다. `migration verified`, 종료 코드 0.
- 임시 증거 디렉터리. `/private/tmp/kitschcatch-issue27-pg.EDvJGy/h2-results`, `/private/tmp/kitschcatch-issue27-pg.EDvJGy/postgresql-results`, `migration-fixture.sql`, `migration-verify.sql`.
- 코드 그래프 전체 빌드 성공. 147개 파일, 파싱 오류 없음. 독립 검토 전 통계 조회도 확인했다.
- 최종 `./gradlew build` 종료 코드 0. 143개 테스트, 실패/오류/건너뜀 모두 0. 빌드 산출물 생성까지 확인했다.
- 중복 결제를 넣은 임시 `issue27_migration_guard` DB에서는 실제 변경 스크립트가 명시적인 사전 검사 오류로 중단됐다. 새 컬럼 0개, 기존 결제 4개 유지로 전체 변경 롤백을 확인했다.
- 독립 Sol HIGH 리뷰 승인. 전체 변경과 신규 파일, 실제 H2/PostgreSQL XML, 코드 그래프를 확인했으며 성공 조건을 막는 항목은 없었다. 불확실 PG 결과 복구와 스냅샷 조회 API는 문서에 명시한 후속 범위로 유지한다.
