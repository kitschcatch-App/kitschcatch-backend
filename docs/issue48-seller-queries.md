# #48 판매자 상품 목록과 상품별 문의 조회.

## 계약.

- 모든 API는 기존 JWT 인증을 요구한다.
- `GET /api/users/me/posts`는 인증 사용자 상품만 조회한다. 별도 `userId` 쿼리로 조회 대상을 바꾸지 못한다.
- `GET /api/users/{userId}/posts`는 지정 판매자의 상품을 조회한다. 없는 회원은 `404 USER_001`, 잘못된 ID는 400이다.
- 두 상품 API의 `status`는 `ON_SALE`, `RESERVED`, `SOLD_OUT` 중 하나이며 생략하면 전체이다. 삭제된 상품은 항상 제외한다.
- 두 상품 API는 생성 시각 DESC, ID DESC 정렬이다. `page`는 0~10000, `size`는 1~100이며 기본값은 0과 20이다. 범위를 벗어나거나 상태가 잘못되면 400이다. 별도 정렬 옵션은 없다.
- 상품 응답은 기존 `PostResponse`를 공통 매퍼로 재사용한다. `data`에는 `content`, `page`, `size`, `totalElements`, `totalPages`가 들어간다. 빈 목록과 마지막 페이지 밖의 요청도 200이며 실제 전체 건수를 유지한다.
- `GET /api/chat-rooms`는 기존 배열 응답을 유지한다. 선택 `role=BUYER|SELLER`, 양수 `postId`를 지원한다. 두 필터는 AND이며 역할을 생략하면 본인이 구매자 또는 판매자인 방을 조회한다.
- 참여자 조건과 해당 참여자의 삭제 표시를 먼저 적용한 뒤 상품 필터를 적용한다. 타인 상품 ID, 없는 상품 ID는 권한 범위를 넓히지 않고 빈 배열을 반환한다. 상품 존재·소유자 확인을 위한 별도 응답을 노출하지 않는다.
- 채팅 응답에 `postId`, `postTitle`을 추가한다. 마지막 메시지 시각 DESC NULLS LAST, 채팅방 ID DESC 정렬이다. 삭제 상품의 기존 채팅방은 종전처럼 남는다.
- 비어 있는 상태·역할·상품 필터와 잘못된 숫자는 400이다. 방 생성·메시지·WebSocket 프로토콜·상품 상태 변경은 수정하지 않는다.

## 확인 가능한 기준과 가정.

기준 브랜치는 `develop`, 시작 SHA는 `5f6d18fb2d4d8fc1e2f435919a548c64d33c9627`이다. #45의 미커밋 변경이나 다른 이슈 구현은 사용하지 않았다.

Notion 원문 `341ee6172f5680679c13c4fa1e53f23a`은 웹에서 접근 불가이고 연결된 Notion 커넥터도 `404 object_not_found`를 반환했다. 외부 명세와의 대조는 미검증이다. 기존 `/api/posts`가 삭제 상품만 제외하고 세 상태를 공개하는 계약을 기준으로 다른 판매자도 세 상태를 공개한다. 페이지 상한은 기존 거래 내역 조회와 일치시켰다. 외부 명세는 변경하지 않았다.

## SQL과 회귀 검증.

판매자 목록은 전용 읽기 저장소를 사용하며 상품 검색 #47의 기존 `PostRepository`를 변경하지 않는다. 판매자 연관관계는 entity graph로 함께 읽고, 이미지는 기존 `@BatchSize(20)`를 사용한다. 컬렉션 fetch join으로 페이지를 메모리에서 자르지 않는다. 채팅은 상품·구매자·판매자만 함께 읽으며 이미지를 읽지 않는다. 조회 기능이므로 스키마 변경과 수동 SQL은 없다.

`SellerQueriesHttpContract`를 H2와 전용 PostgreSQL에서 동일하게 실행한다. 실제 HTTP·JWT로 본인/다른 판매자·모든 판매 상태·삭제 제외·동일 시각 정렬·페이지 전체 건수·입력 오류·없는 회원·익명/위조/refresh/없는 회원 토큰·ID 주입·참여 역할·참여자별 삭제·삭제 상품의 기존 문의 보존을 확인한다. 25개 결과에서 SQL이 결과당 증가하지 않는지도 검증한다. S3·Toss 클라이언트는 대역이며 호출하지 않는 것을 확인한다.

전체 테스트에 포함된 다른 이슈의 PostgreSQL 전용 테스트는 각각의 환경 변수를 제공하지 않아 건너뛴다. #48 PostgreSQL은 전용 인스턴스 `127.0.0.1:55448`, DB `issue48_queries`를 사용한다. PostgreSQL 테스트의 `create-drop`은 테스트 DB에만 사용해야 한다. 운영 DB에 연결하면 안 된다.

```sh
ISSUE48_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55448/issue48_queries \
ISSUE48_TEST_DB_USERNAME=postgres \
./gradlew test bootJar --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=256m' \
  -Dorg.gradle.java.installations.auto-download=false
```

실제 S3·Toss 연동은 미완료이며 이번 작업의 검증 범위에 포함되지 않는다. 운영 DB 적용·배포·실제 메시지/푸시·병합·자동 병합은 수행하지 않는다. 대규모 운영 데이터의 실행 계획과 부하 검증도 미검증이다.
