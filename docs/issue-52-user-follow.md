# 사용자 팔로우 API (#52)

기준: `develop` (`5f6d18fb2d4d8fc1e2f435919a548c64d33c9627`). Notion API 원문은 커넥터에서 404/object_not_found를 반환해 읽지 못했다. 아래 계약은 이슈 #52와 기존 관심 매장·사용자 API의 관례로 확정한 구현 계약이다. 외부 명세는 수정하지 않았다.

## HTTP 계약

모든 요청은 유효한 access JWT가 필요하다. 인증 누락·오류·refresh JWT는 `401 AUTH_004`다. 토큰 주체와 대상 사용자는 실제로 존재해야 하며 없으면 `404 USER_001`이다. 프로필 등록 완료는 요구하지 않는다.

| 메서드 | 경로 | 결과 |
| --- | --- | --- |
| POST | `/api/users/{userId}/follow` | 인증 사용자가 대상을 팔로우. `{userId, following:true}` |
| DELETE | `/api/users/{userId}/follow` | 인증 사용자의 대상 방향 관계만 해제. `{userId, following:false}` |
| GET | `/api/users/{userId}/followers` | 대상을 팔로우하는 사용자 페이지 |
| GET | `/api/users/{userId}/followings` | 대상이 팔로우하는 사용자 페이지 |

변경 요청 본문은 필요 없다. 변경 주체는 JWT에서만 얻는다. 반복 등록·해제는 모두 200이며, 반복 등록은 최초 관계 ID·시각을 보존한다. 해제 후 재등록은 새 관계가 된다. 반대 방향 관계는 별개다. 자기 자신의 등록·해제는 `400 COMMON_002`이며 자기 목록 조회는 가능하다. 숫자가 아닌 ID, long 초과, 0 이하 ID도 `400 COMMON_002`다.

목록은 다른 인증 사용자도 조회할 수 있다. 기본 `page=0,size=20`, page는 0~10000, size는 1~100이다. 범위·형식 오류는 `400 COMMON_001`이다. 정렬은 관계 `created_at DESC,id DESC`로 고정한다. 응답은 `{content,page,size,totalElements,totalPages}`이며 빈 목록·범위 밖 페이지도 200이다. 페이지 간 동시 변경이 있으면 offset 페이지의 항목 이동이나 개수 차이는 생길 수 있다.

`content` 원소는 `{id,nickname,profileImageUrl}`뿐이다. 이미지가 없으면 null이며 URL은 기존 `ProfileImageStorage.imageUrl` 정책을 따른다. 이메일·소셜 식별자·인증 제공자·프로필 object key·가입 시각·프로필 등록 시각은 공개하지 않는다. 사용자 소개·공개 프로필 전체 계약·집계·푸시·탈퇴는 별도 이슈 범위다.

## 저장 및 동시성

`user_follows.follower_id`가 팔로우 주체, `following_id`가 대상이다. 두 단방향 LAZY User 참조만 사용하고 User 컬렉션은 추가하지 않았다. 방향별 유일 제약, 자기 관계 CHECK, NOT NULL, 양쪽 FK(ON DELETE CASCADE), 각 방향의 `(사용자 ID, created_at DESC, id DESC)` 인덱스를 둔다. 삭제 cascade는 DB 관계 정리이며 회원 탈퇴 API를 구현하거나 기존 거래 기록을 삭제하는 작업은 아니다.

변경 트랜잭션은 양쪽 사용자 행을 ID 오름차순으로 잠근 뒤 관계 존재 확인·등록 또는 삭제한다. 같은 관계의 반복·혼합 변경과 A→B/B→A 요청 모두 같은 잠금 순서를 사용한다. 인기 사용자에 대한 서로 다른 팔로우 변경도 해당 사용자 행 때문에 직렬화되는 한계가 있다. 반환 `following`은 해당 트랜잭션이 처리한 결과이며 뒤이어 커밋하는 다른 요청의 상태를 보장하지 않는다.

목록은 생성자 projection으로 필요한 세 필드만 한 번에 선택한다. 요청자·대상 존재 확인, 페이지 쿼리, 필요할 때 count 쿼리로 최대 4개의 SQL을 사용하며 사용자별 추가 조회는 없다. 이미지 URL 생성은 외부 S3 요청을 보내지 않는다.

## 수동 SQL

`src/main/resources/db/manual/052_user_follows.sql`은 기존 users 테이블을 전제로 전체 DDL을 트랜잭션에서 실행한다. 재실행은 기존 관계를 보존한다. 기존에 잘못된 구조로 만든 테이블을 자동 보정하는 스크립트는 아니다. 정상 운영 적용 전에 스키마와 기존 데이터를 확인해야 한다. 운영 DB에는 적용하지 않았다.

## 검증 실행

```sh
ISSUE52_TEST_DB_URL=jdbc:postgresql://127.0.0.1:<격리포트>/<전용DB> \
ISSUE52_TEST_DB_USERNAME=<전용사용자> \
ISSUE52_TEST_DB_PASSWORD=<테스트비밀번호> \
./gradlew test --tests 'com.kitschcatch.backend.domain.follow.*' --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx512m -XX:MaxMetaspaceSize=384m' --no-daemon
```

PostgreSQL 테스트는 ISSUE52 환경 변수가 없으면 명시적으로 건너뛴다. 테스트마다 임의 `issue52_<UUID>` 스키마를 만들고 정리한다. HTTP 서버는 random port다. 다른 이슈의 PostgreSQL 환경 변수는 이 실행에 전달하지 않는다.

HTTP 계약은 H2 및 수동 SQL로 관계 테이블을 만든 PostgreSQL에서 동일하게 검증한다. 실제 JWT HTTP, 반복 요청, 8개 동시 등록·해제·혼합·반대 방향 요청, 관계 방향, 자기 관계, 없는 사용자, 입력 범위, 페이지·정렬, 공개 필드, 최대 100건 목록의 제한된 SQL 수, FK cascade, Swagger를 확인한다. 별도 PostgreSQL migration 테스트는 재실행·행 보존·인덱스·유일/FK/CHECK/필수값 SQLSTATE·DDL 실패 롤백을 확인한다.

실제 수행 결과와 독립 리뷰는 PR에 기록한다. 실제 S3·Toss 연동, 운영 DB 적용, 배포, 실제 사용자 메시지·푸시는 수행하지 않는다. 기존 실제 연동 미완료 상태는 유지한다.
