# 거래 후기와 사용자 신뢰 정보 API (#56)

기준 브랜치는 `feat/45`, 시작 SHA는 `85ff03c669ea360cf9b71f05749ea6a08a76accc`다.

## 계약과 가정.

- Notion 원문 `https://app.notion.com/p/341ee6172f5680679c13c4fa1e53f23a`는 연결된 계정에서 404 `object_not_found`를 반환했다. 저장소에는 기존 trust-info 구현이나 필드 명세가 없다. 아래 계약은 #56과 확인 가능한 주문 계약에 근거한 로컬 명세이며 외부 Notion은 수정하지 않았다.
- 모든 경로는 기존 보안 정책대로 JWT가 필요하다. 사용자별 받은 후기와 신뢰 집계는 로그인한 누구나 읽을 수 있다. 주문별 후기는 거래 당사자만 읽는다.
- 작성자는 구매자와 주문 당시 판매자다. 구매자는 주문의 `user_id`, 판매자는 불변 스냅샷 `seller_id`를 사용한다. 현재 판매글 소유자나 현재 닉네임으로 참여자를 바꾸지 않는다.
- 대상 사용자는 서버가 상대방으로 결정한다. `PURCHASE_CONFIRMED` 상태이며 활성 환불이 없는 주문만 허용한다. 결제 SUCCESS만으로는 작성할 수 없다.
- 평점은 소수 부분 없는 1~5점, 본문은 입력 기준 1~1000자이고 앞뒤 Java whitespace를 제거해 저장한다. 공백만 있으면 400이다. 수정·삭제 API는 이번 범위에 없다.
- 주문·작성자 조합으로 한 번 작성한다. 양쪽 작성은 최대 두 건이며 같은 내용 재시도도 409 `REVIEW_002`다. 주문 잠금과 DB 유일 제약으로 동시 중복을 차단한다.

| 경로 | 응답과 오류. |
| --- | --- |
| POST `/api/orders/{orderId}/reviews` | `{rating, content}` → 201 후기. 미확정·환불은 409 `REVIEW_001`, 중복은 409 `REVIEW_002`. 타인은 403 `ORDER_004`. |
| GET `/api/orders/{orderId}/reviews` | 당사자에게 작성 시각·ID 오름차순 후기 배열. 미작성은 빈 배열. |
| GET `/api/users/{userId}/reviews?page=0&size=20` | 받은 후기 페이지. 시각·ID 내림차순. page 0~10000, size 1~100. |
| GET `/api/users/{userId}/trust-info` | `userId, completedTransactionCount, reviewCount, averageRating`. |

`orderId`는 기존 ORD- 공개 주문 번호다. 후기 DTO는 `reviewId, authorId, recipientId, authorRole, rating, content, createdAt`만 반환한다. 연락처·이메일·인증 제공자·PG 키·배송지·송장·내부 주문 ID·공개 주문 번호는 후기 응답에 포함하지 않는다. 작성자가 직접 쓴 본문은 그대로 공개된다.

구매 확정 건수는 구매자 또는 스냅샷 판매자로 참여한 `PURCHASE_CONFIRMED` 주문 수다. 후기 수와 평균은 본인이 받은 후기만 기준으로 한다. 후기가 없어도 확정 거래는 건수에 포함된다. 평균은 정수 합계/건수를 소수 둘째 자리 HALF_UP으로 계산하며 빈 집계는 건수 0, 평균 null이다. 신뢰 점수나 가입 기간·팔로워 수 등 임의 지표를 만들지 않는다.

## SQL과 동시성.

`src/main/resources/db/manual/056_transaction_reviews.sql`을 #45의 주문 스냅샷 및 lifecycle SQL 뒤에 수동 적용한다. `ddl-auto=update`를 사용하는 애플리케이션을 시작하기 전에 수동 SQL을 먼저 적용해야 한다. `CREATE TABLE IF NOT EXISTS`는 이미 다른 정의로 생성된 후기 테이블의 누락 제약을 자동 보완하지 않으므로, 그러한 환경에서는 테이블 정의를 별도 점검해야 한다. 운영에는 적용하지 않았다. 후기 테이블의 주문·작성자 유일 제약, 사용자와 주문 외래 키, 1~5점·비어 있지 않은 본문 CHECK, 양방향 상대 평가 CHECK, 주문/구매자/판매자 복합 외래 키를 구성한다. 후기가 있는 주문이나 사용자는 물리 삭제할 수 없으며 #57의 기록 보존 정책과 이후 통합해야 한다.

사용자별 정렬 인덱스는 `(recipient_id, created_at DESC, id DESC)`다. 주문·작성자 유일 인덱스는 주문별 조회에도 사용된다. 신뢰 집계는 기존 주문 구매자·판매자 인덱스와 후기 상대방 인덱스를 사용하며 하나의 SQL에서 건수와 합계를 읽는다.

작성은 기존 #45와 같은 상품 → 주문 잠금 순서로 상태 확인과 저장까지 한 트랜잭션에서 수행한다. 외부 PG·S3 호출은 하지 않는다. 현재 판매글의 삭제·소유자 변경은 주문 스냅샷 권한에 영향을 주지 않는다.

## 검증.

PostgreSQL 전용 테스트는 `ISSUE56_TEST_DB_URL`, `ISSUE56_TEST_DB_USERNAME`, `ISSUE56_TEST_DB_PASSWORD`를 사용하고 실행마다 UUID 스키마를 생성·삭제한다. 환경 변수가 없으면 PostgreSQL 테스트는 건너뛰므로 H2 통과와 구분해야 한다.

2026-10-01에 전용 PostgreSQL 인스턴스 `127.0.0.1:55556`, DB `issue56_reviews`에서 실행했다. #56의 HTTP는 RANDOM_PORT 서버와 실제 JWT·JPA·PostgreSQL을 사용한다. 결제 준비는 실제 HTTP Toss 어댑터가 루프백 HTTP 대역의 승인 응답을 읽고, 주문 생성 → 결제 승인 → 송장 등록 → 구매 확정 API를 거친다.

| 실제 수행한 검증. | 결과. |
| --- | --- |
| H2 실제 HTTP 계약. | 16건 통과. |
| 수동 SQL로 후기 테이블을 교체한 PostgreSQL 실제 HTTP 계약. | 16건 통과. |
| PostgreSQL SQL 재실행·이력 보존·유일/평점/본문/당사자/복합 FK·삭제 보호·실패 롤백. | 3건 통과. |
| 전체 `test build`. | 80개 테스트 클래스, 740건. 실패 0, 오류 0, 건너뜀 0. 빌드 성공. |
| `git diff --check`. | 통과. |

HTTP에서 결제 성공만으로 작성 불가, PENDING/PAID/CANCELED/REFUNDED, 활성 환불, 타인·인증 누락·무효/없는 사용자, 동일 작성자 경합(201 한 건/409 한 건), 양쪽 작성자 경합(201 두 건), 중복 재시도, 빈 집계/null 평균, 양방향 거래 건수, 받은 후기만의 정확한 1.67 평균, 소수 평점·범위·빈 본문·길이·페이지 입력, 판매글 소유자 변경·삭제 후 스냅샷 권한, 고정 DTO 필드와 N+1 부재를 확인했다.

전체 검증은 같은 전용 인스턴스에서 기존 #27/31/39/41/43/45와 #56 PostgreSQL 환경 변수를 모두 설정했다. #27은 전용 DB의 public 스키마를 사용하고 나머지는 UUID 스키마를 사용했다. 다른 채팅의 DB나 프로세스는 변경하지 않았다. Gradle `--max-workers=1`, Gradle heap 384MiB, 테스트 heap 256MiB, 테스트 context cache 2로 자원을 제한했다.

관련 검증 재현 명령은 아래와 같다. PostgreSQL DB와 접속 사용자는 실행자의 격리 환경에 맞게 지정한다.

```sh
export ISSUE56_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55556/issue56_reviews
export ISSUE56_TEST_DB_USERNAME=keemhoeyune
export ISSUE56_TEST_DB_PASSWORD=
./gradlew test --tests '*TransactionReview*Test' --max-workers=1 --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=256m'
```

전체 회귀는 같은 DB에 `ISSUE31/39/41/43/45_TEST_DB_URL`, 각 `_USERNAME`과 `_PASSWORD`를 설정하고 `ISSUE27_TEST_DB_URL`, `ISSUE27_TEST_DB_DRIVER=org.postgresql.Driver`, `ISSUE27_TEST_DB_USER`도 설정한 뒤 `./gradlew test build --max-workers=1`로 실행한다. 저장소 외부 Gradle init 설정으로 테스트 메모리만 제한했으며 저장소의 공통 빌드 설정은 변경하지 않았다.

실제 Toss·S3 연동은 미완료로 유지한다. 테스트 PG는 루프백 HTTP 대역이며 실제 사용자 메시지, 운영 DB 적용, 배포, 송금은 하지 않았다.

CI 실행, 운영 DB SQL 적용, 배포 후 상태, 실제 사용자 결과는 이번 로컬 검증으로 확인하지 않았다. Notion 원문의 필드 계약도 접근할 수 없어 미검증이다. 실제 결제 승인·정산·S3 업로드가 완료되었다고 기록하지 않는다.

## 독립 리뷰.

구현자와 다른 GPT 6.1 Sol High 에이전트가 기준 `85ff03c669ea360cf9b71f05749ea6a08a76accc`부터 구현 HEAD `cd2ae233a8070968d4552fdfa9543d3e0e0f2265`까지 전체 15개 파일 diff를 읽기 전용으로 검토했다. actionable findings는 0건이었다.

확인 범위는 기존 인증·주문 접근·구매 확정·환불 코드, 주문 스냅샷 권한, 양쪽 중복 방지, 입력·상태·활성 환불 검증, 상품 → 주문 잠금 순서, SQL 관계·유일성·CHECK·인덱스·재실행, 정확한 집계, 개인정보 제한, 지연 로딩/N+1, 신규 HTTP·SQL 테스트였다. 리뷰어는 PostgreSQL HTTP 16건과 SQL 3건의 실패·오류·건너뜀 0을 직접 확인했으며 전체 빌드 결과는 구현자가 별도 확인했다.

리뷰어가 권장한 수동 SQL 선행 적용 순서와 기존 다른 정의 테이블의 자동 보완 한계를 문서에 반영했다. 리뷰 이후 소스·SQL·테스트 변경은 없고 검증 결과 및 운영 한계 문서만 추가했다.
