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

`src/main/resources/db/manual/056_transaction_reviews.sql`을 #45의 주문 스냅샷 및 lifecycle SQL 뒤에 수동 적용한다. 운영에는 적용하지 않았다. 후기 테이블의 주문·작성자 유일 제약, 사용자와 주문 외래 키, 1~5점·비어 있지 않은 본문 CHECK, 양방향 상대 평가 CHECK, 주문/구매자/판매자 복합 외래 키를 구성한다. 후기가 있는 주문이나 사용자는 물리 삭제할 수 없으며 #57의 기록 보존 정책과 이후 통합해야 한다.

사용자별 정렬 인덱스는 `(recipient_id, created_at DESC, id DESC)`다. 주문·작성자 유일 인덱스는 주문별 조회에도 사용된다. 신뢰 집계는 기존 주문 구매자·판매자 인덱스와 후기 상대방 인덱스를 사용하며 하나의 SQL에서 건수와 합계를 읽는다.

작성은 기존 #45와 같은 상품 → 주문 잠금 순서로 상태 확인과 저장까지 한 트랜잭션에서 수행한다. 외부 PG·S3 호출은 하지 않는다. 현재 판매글의 삭제·소유자 변경은 주문 스냅샷 권한에 영향을 주지 않는다.

## 검증.

실행 결과와 독립 리뷰 기록은 검증 완료 뒤 아래에 추가한다. PostgreSQL 전용 테스트는 `ISSUE56_TEST_DB_URL`, `ISSUE56_TEST_DB_USERNAME`, `ISSUE56_TEST_DB_PASSWORD`를 사용하고 실행마다 UUID 스키마를 생성·삭제한다. 환경 변수가 없으면 PostgreSQL 테스트는 건너뛰므로 H2 통과와 구분해야 한다.

실제 Toss·S3 연동은 미완료로 유지한다. 테스트 PG는 루프백 HTTP 대역이며 실제 사용자 메시지, 운영 DB 적용, 배포, 송금은 하지 않았다.
