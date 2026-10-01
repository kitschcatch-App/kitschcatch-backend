# 관심 상품 API 계약과 검증 기록 (#51).

## 기준과 명세.

- 이슈: https://github.com/kitschcatch-App/kitschcatch-backend/issues/51.
- 최초 기준: `origin/develop`, `5f6d18fb2d4d8fc1e2f435919a548c64d33c9627`.
- Notion API 명세 `341ee6172f5680679c13c4fa1e53f23a`는 2026-10-01 연결된 Notion 조회에서 404로 접근되지 않았다. 이슈와 기존 관심 매장 계약을 기준으로 아래 선택을 확정했다. 외부 명세는 수정하지 않았다.
- 관심 매장, 상품 검색, 공개 프로필 집계, 실시간 알림, 회원 탈퇴 구현은 이 변경 범위에 포함되지 않는다.

## HTTP 계약.

세 API 모두 JWT access token이 필요하며, 사용자 ID는 `AuthenticatedUser`에서만 가져온다. 쿼리나 본문의 사용자 ID로 타인의 관계를 변경하거나 조회할 수 없다. 프로필 등록 전 사용자도 사용 가능하다.

| 요청 | 성공 데이터 | 반복 요청과 상태 처리 |
| --- | --- | --- |
| `POST /api/posts/{postId}/favorites` | `{postId, favorited: true, favoriteCount}` | 최초/반복 모두 200. 중복 관계를 생성하거나 최초 등록 시각을 갱신하지 않는다. |
| `DELETE /api/posts/{postId}/favorites` | `{postId, favorited: false, favoriteCount}` | 최초/반복 모두 200. 본인 관계만 삭제한다. 소프트 삭제 상품도 해제 가능하다. |
| `GET /api/users/me/favorite-posts?page=0&size=20` | `{content, page, size, totalElements, totalPages}` | 빈 목록과 마지막 페이지 이후도 200. |

성공 데이터는 기존 `ApiResponse`의 `data`에 담는다. `favoriteCount`는 잠금 안에서 변경 후 조회한 해당 상품의 전체 사용자 관심 관계 수이며 응답 수신 후 다른 요청으로 바뀔 수 있다.

목록 원소는 `id`, `sellerId`, `sellerNickname`, `title`, `price`, `productCategory`, `productCondition`, `productStatus`, `favorited`, `favoritedAt`, `thumbnailUrl`을 포함한다. 카테고리는 기존 `ProductCategory` JSON 표현을 사용한다. `favorited`는 항상 true이다.

- 정렬: 관심 관계 `createdAt DESC, id DESC`. 상품 생성 시각보다 관심 등록 시각을 우선한다. 해제 후 재등록하면 새 관계와 시각이 생긴다.
- 페이지: 0부터 10000, 크기: 1부터 100, 기본값: page=0, size=20. 잘못된 형식/범위는 400 `COMMON_001`이다.
- 상품 ID는 양의 long이다. 형식/범위 오류는 400 `COMMON_002`이다.
- 토큰 누락·형식 오류·refresh token은 401 `AUTH_004`이다. 토큰의 사용자가 DB에 없으면 404 `USER_001`이다.
- 상품이 없으면 변경 요청은 404 `POST_001`이다. 소프트 삭제 상품은 POST가 404이며 목록과 페이지 집계에서 제외한다. 남은 관계는 유지하여 DELETE로 정리할 수 있다. 물리 삭제는 FK cascade로 관계를 정리한다.
- 예약/판매 완료 상품도 등록·조회·해제가 가능하며 현재 `productStatus`를 표시한다. 관심 관계 변경은 판매 상태를 바꾸지 않는다. 별도 금지 요구가 없어 판매자의 본인 상품 등록도 허용한다.
- 대표 이미지는 `sortOrder ASC, id ASC` 첫 이미지이다. 이미지가 없으면 `thumbnailUrl=null`이다. 기존 `PostImageStorage.imageUrl`로 URL만 조합하며 S3 객체 조회/업로드는 실행하지 않는다. 운영 URL 설정은 기존 상품 API와 같은 `app.s3` 설정을 따른다.
- 공유 `PostResponse`와 기존 상품 목록/상세 응답은 변경하지 않았다. 관심 상품 API 전용 DTO로 범위를 제한했다.

## 저장과 동시성.

- 전용 `post_favorites`에 `(user_id, post_id)` 유일 제약을 둔다. 사용자/상품 FK는 물리 삭제 시 cascade이다.
- 변경 트랜잭션은 사용자 행을 먼저 잠그고 상품 행을 잠근다. 관계가 없는 최초 요청도 사용자 행으로 직렬화되며 같은 상품의 서로 다른 사용자 변경은 상품 잠금으로 직렬화된다. 상품 변경/소프트 삭제와도 같은 상품 잠금을 사용한다.
- 목록은 관계·상품·판매자를 fetch join하고 대표 이미지 키를 페이지 전체에 일괄 조회한다. 컬렉션 fetch join 없이 DB 페이지를 유지한다.
- 수동 SQL: `src/main/resources/db/manual/051_post_favorites.sql`. 기존 `users`와 `posts`가 있는 환경에 별도 적용한다. 자동 migration 도입이나 운영 DB 적용은 이 변경에서 수행하지 않았다.
- SQL은 트랜잭션, 유일/FK/NOT NULL 제약, 사용자별 최신순 인덱스, 상품별 집계/FK 인덱스를 포함하며 같은 스키마에 재실행해도 관계와 등록 시각을 유지한다. 기존에 다른 정의의 테이블이 존재하는 경우를 자동 보정하는 SQL은 아니다.

## 실제 검증.

2026-10-01 전용 워크트리에서 Java 21, Gradle `--max-workers=1`, Gradle JVM `-Xmx384m -XX:MaxMetaspaceSize=256m`으로 수행했다. 공유 원본에서 편집·테스트·브랜치 변경을 하지 않았다.

```sh
./gradlew test --tests '*PostFavoriteHttpTest' --max-workers=1 --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=256m'

ISSUE51_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55451/postgres \
ISSUE51_TEST_DB_USERNAME=issue51 \
./gradlew test --tests '*PostFavoritePostgresHttpTest' --tests '*PostFavoriteMigrationTest' \
  --max-workers=1 --no-daemon -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=256m'

ISSUE51_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55451/postgres \
ISSUE51_TEST_DB_USERNAME=issue51 \
./gradlew test bootJar --max-workers=1 --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=256m'
```

마지막 전체 실행: 발견 539개, 실행/통과 465개, 건너뜀 74개, 실패/오류 0개. `bootJar` 성공. #51의 H2 HTTP 33개, PostgreSQL HTTP 33개, PostgreSQL SQL 7개는 모두 실행/통과했다. 앞선 단독 HTTP 실행에는 이후 추가한 서로 다른 사용자 동시 변경·판매자 본인 등록 2개가 포함되지 않았으며 최종 전체 실행에 포함됐다.

- 실제 랜덤 포트 HTTP + JWT: 인증, 본인 관계 격리, 외부 userId 무시, 반복/재등록, 빈 페이지·경계·총개수, 동일 시각 ID 정렬, 잘못된 ID/페이지와 존재하지 않는 사용자/상품, Swagger 계약을 검증했다.
- 동시 HTTP: 8개 같은 사용자 POST, 8개 DELETE, POST/DELETE 혼합 요청에서 중복·실패·타인 관계 변경이 없음을 확인했다. 8명의 서로 다른 사용자의 같은 상품 동시 등록/해제도 전체 관심 수와 본인 관계를 검증했다.
- 상태: 판매 완료·예약 상품 등록과 현재 상태 표시, 판매자의 본인 등록, 소프트 삭제 제외/등록 차단/반복 해제, 사용자/상품 물리 삭제 cascade를 검증했다.
- N+1: 서로 다른 판매자의 상품 100개와 상품마다 이미지 3개를 두고 SQL 4회(사용자 확인, 페이지, count, 대표 이미지), entity/collection 추가 fetch 0회, 이미지 정렬 동률 처리까지 확인했다.
- PostgreSQL 14: 전용 `/tmp/issue51-postgresql` 인스턴스와 55451 포트, 매 실행 UUID 스키마를 사용했다. HTTP 테스트의 Hibernate 관계 테이블을 실제 수동 SQL로 대체하고 두 번 실행했다. 별도 SQL 테스트로 데이터/시각 보존 재실행, 4개 인덱스, 유일/FK/필수값의 SQLSTATE, 양쪽 부모 삭제 cascade를 확인했다. 공유 DB는 사용하지 않았다.
- `git diff --check` 통과.

건너뜀 74개는 기존 #31 사용자 프로필, #39 매장, #41 관심 매장, #43 거래 조회의 PostgreSQL 조건부 테스트이며 해당 이슈 환경 변수는 주입하지 않았다. 해당 기능의 H2/단위 검증은 전체 실행에 포함됐다. 기존 주문 예약/결제 복구의 별도 PostgreSQL 워커 스크립트는 이슈 범위 밖이며 실행하지 않았다.

실제 S3·Toss 연동은 사용자 보류대로 미완료 상태를 유지한다. 운영 DB 적용, 배포, 실제 메시지/푸시와 실제 사용자 결과는 검증하지 않았다. CI 결과는 로컬 테스트/빌드와 별도이다.

## 독립 리뷰.

구현자와 별도의 GPT 6.1 Sol High 에이전트가 `5f6d18fb2d4d8fc1e2f435919a548c64d33c9627..c86a9ba0bc64dcf374ae804122ad4c85f05ad3e4`의 전체 추가 파일 15개와 기존 JWT 인증, 상품 삭제·변경, 주문·예약 잠금, 프로필 잠금, 관심 매장 계약을 읽기 전용으로 검토했다. 권한·입력·동시성·상태·DB/SQL·회귀 및 테스트 적합성에 대한 결과는 `findings=0`이며 리뷰 수정 사항은 없었다. 리뷰어는 기존 결과 XML을 확인했고 테스트를 재실행하지 않았다.

삭제·주문 상태 변경과 관심 요청을 동시에 실행하는 HTTP 시나리오는 수행하지 않았다. 해당 상호작용은 기존 상품 잠금 구조와 비교한 코드 검토로 확인했으며 실제 동시 실행 검증과 구분한다. 이후 커밋은 이 계약/검증/리뷰 문서만 추가한다.
