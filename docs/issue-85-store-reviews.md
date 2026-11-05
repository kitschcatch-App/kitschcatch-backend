# 매장 리뷰 기본 API

매장 상세 화면에서 작성·조회·수정·삭제와 별점 집계를 제공한다. 모든 요청은 활성 사용자 JWT가 필요하다. 주문 상대방 평가는 기존 거래 후기 API에서 별도로 처리한다.

| 요청 | 성공 응답 |
| --- | --- |
| `POST /api/stores/{storeId}/reviews` | `201`, 새 리뷰. 본문은 `{ "rating": 5, "content": "후기" }`. |
| `GET /api/stores/{storeId}/reviews?page=0&size=20` | `200`, `content, page, size, totalElements, totalPages`. |
| `GET /api/stores/{storeId}/reviews/me` | `200`, `{ "review": ... }`. 없으면 `review: null`. |
| `GET /api/store-reviews/{reviewId}` | `200`, 공개 리뷰. |
| `PATCH /api/store-reviews/{reviewId}` | `200`, 수정한 리뷰. `version`과 변경할 `rating` 또는 `content`를 보낸다. |
| `DELETE /api/store-reviews/{reviewId}` | `200`, 공통 성공 응답. 같은 작성자의 재요청도 성공한다. |
| `GET /api/stores/{storeId}/reviews/summary` | `200`, `reviewCount, averageRating, ratingCounts, visibleReviewCount`. |

리뷰에는 `reviewId, storeId, author, rating, content, createdAt, updatedAt, mine, hidden, version, images`가 있다. `author`는 공개 ID, 닉네임, 현재 프로필 이미지의 GET 서명 URL 또는 null을 제공한다. `images`는 사진 기능이 추가되기 전까지 빈 배열이다. 작성·수정 시각은 UTC ISO 8601이다. 목록은 작성 시각과 ID 내림차순으로 조회하며 `page`는 0~10000, `size`는 1~100이다.

별점은 정수 1~5다. 본문은 앞뒤 공백을 제거한 뒤 1~1,000자로 저장한다. PATCH의 별점·본문 중 생략 또는 null인 필드는 유지한다. 변경 필드가 하나 이상 필요하다. 버전이 오래됐다면 리뷰를 다시 조회해 최신 내용을 확인한다.

사용자와 매장 조합마다 삭제되지 않은 리뷰는 1개다. 운영상 숨긴 리뷰는 작성자가 삭제한 뒤에도 숨김 상태가 해제될 때까지 재작성을 막는다. 작성자는 삭제 전 내 리뷰에서 `hidden`을 확인할 수 있다. 일반 리뷰를 삭제한 뒤 새 리뷰를 작성할 수 있으며, 같은 사용자·매장의 최근 24시간 작성 횟수는 삭제된 행을 포함해 5회까지 허용한다. 작성·수정·삭제는 활성 사용자 행을 잠가 같은 작성자의 요청과 탈퇴를 직렬화한다. 수정에는 버전 검사를 함께 사용한다.

목록·단건·집계는 삭제·숨김 또는 탈퇴한 작성자의 리뷰를 제외한다. 집계는 1~5점별 개수를 항상 반환하고, 평균은 소수 둘째 자리까지 HALF_UP 규칙으로 반올림한다. 리뷰가 없으면 평균은 null이고 개수는 0이다. `visibleReviewCount`는 현재 `reviewCount`와 같다. 매장 상세의 `reviewSummary`는 같은 집계에서 `reviewCount`와 `averageRating`만 제공한다.

| 상태 | 코드와 의미 |
| --- | --- |
| 400 | `COMMON_001` 유효하지 않은 입력, `COMMON_002` 경로 ID나 JSON 형식 오류. |
| 401 | `AUTH_004` 인증 실패. |
| 403 | `STORE_REVIEW_003` 다른 작성자의 공개 리뷰 변경 시도. |
| 404 | `STORE_001` 매장 없음, `STORE_REVIEW_001` 조회할 수 없는 리뷰. |
| 409 | `STORE_REVIEW_002` 중복 작성, `STORE_REVIEW_004` 수정 버전 충돌. |
| 429 | `STORE_REVIEW_005` 해당 매장 작성 제한 초과. |

## DB 반영 및 검증

`stores`와 `users`가 있는 대상 PostgreSQL 스키마에 `src/main/resources/db/manual/085_store_reviews.sql`을 애플리케이션 시작 전에 적용한다. 추가 테이블, 외래 키, 평점·본문 제약, 재작성 조건을 적용한 부분 유일 인덱스를 생성한다. 운영상 숨김 필드는 조회 조건에 포함되며 숨김 조작은 신고 운영 범위에서 다룬다. 삭제 또는 탈퇴 후 공개 조회가 차단된 리뷰의 물리 정리·증거 보존 기간은 신고 정책에서 확정한다.

`StoreReviewPostgresHttpTest`는 `ISSUE85_TEST_DB_URL`, `ISSUE85_TEST_DB_USERNAME`, `ISSUE85_TEST_DB_PASSWORD`로 격리 스키마를 만들고, 수동 SQL과 실제 HTTP·JWT를 사용한다. CI의 PostgreSQL 작업에도 이 환경 변수를 설정한다. 프로필 사진 서명 검증에는 테스트 자격 증명을 사용한다.

Java 21과 로컬 PostgreSQL 14로 전체 Gradle 빌드를 검증했다. 테스트 1,338건이 통과했고 실패·오류·건너뜀은 각각 0건이다. 신규 PostgreSQL 리뷰 테스트 8건에는 동시 작성·수정·탈퇴, 숨김 리뷰 재작성 제한, 별점 집계와 수동 SQL 제약 검증이 포함된다.
