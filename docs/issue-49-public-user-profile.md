# 공개 회원 프로필과 사용자 아이디·소개 확장

관련 이슈는 [#49](https://github.com/kitschcatch-App/kitschcatch-backend/issues/49)이며 기준은 `develop`의 `5f6d18fb2d4d8fc1e2f435919a548c64d33c9627`이다.

## 명세 확인과 가정

2026-10-01 [Notion 사용자 명세](https://app.notion.com/p/341ee6172f5680679c13c4fa1e53f23a)를 연결 도구로 조회했으나 `404 object_not_found`로 접근하지 못했다. 웹 조회도 접근에 실패했다. 따라서 아래 새 필드명·길이·정규화·인증 기준은 이슈 요구와 기존 사용자 API를 기준으로 정한 구현 계약이다. Notion 원문을 확인하거나 수정한 것으로 간주하지 않는다.

기존 사용자 API의 JWT 인증 정책을 유지한다. 여기서 공개 프로필은 로그인한 본인·타인에게 동일하게 제공되는 공개 필드를 뜻한다. 비로그인 조회는 401 `AUTH_004`다. 미등록 사용자나 존재하지 않는 사용자의 공개 조회는 둘 다 404 `USER_001`로 응답하며 소셜 기본 닉네임은 공개하지 않는다. 등록된 기존 사용자는 새 필드가 없어도 공개 조회할 수 있다.

## API 계약

| 메서드와 경로 | 동작 |
| --- | --- |
| `GET /api/users/{userId}` | 별도 공개 조회 컨트롤러에서 `id`, `nickname`, `username`, `bio`, `profileImageUrl`, `profileRegisteredAt`만 반환한다. |
| `GET /api/users/username-availability?username=Collector_1` | 정규화한 `username`과 `available`을 반환한다. JWT 본인 ID만 중복 제외 기준으로 사용한다. |
| `POST /api/users/me/profile` | 기존 `nickname`, `profileImageKey`에 선택적 `username`, `bio`를 추가한다. 최초 등록 성공은 201이다. |
| `PATCH /api/users/me` | 기존 필드 및 새 필드의 부분 수정을 지원한다. 성공은 200이다. |
| `GET /api/users/me` | 기존 계정·프로필 응답에 `username`, `bio`를 추가한다. |

모든 응답은 기존 `ApiResponse` 래퍼를 사용한다. 공개 응답에는 계정 이메일, 인증 제공자, 제공자 사용자 식별자, 이미지 object key, 생성 시각, 토큰, 내부 중복 키를 포함하지 않는다. 이미지 URL은 기존 `ProfileImageStorage.imageUrl` 정책을 사용하며 공개 조회는 객체 메타데이터를 읽거나 업로드하지 않는다.

```json
{
  "success": true,
  "data": {
    "id": 42,
    "nickname": "키치수집가",
    "username": "collector_1",
    "bio": "굿즈를 수집합니다.",
    "profileImageUrl": null,
    "profileRegisteredAt": "2026-10-01T11:00:00Z"
  }
}
```

## 입력과 기존 사용자 호환

- `username`은 닉네임과 별도 필드다. Java `strip()` 후 `Locale.ROOT` 소문자 변환 결과가 `[a-z0-9_]{3,30}`이어야 한다. 대소문자를 구분하지 않고 정규화된 한 컬럼을 저장·조회·유일성 판정에 사용한다. 점, 하이픈, 내부 공백, 한글은 허용하지 않는다.
- 등록에서 `username` 생략 또는 null은 미설정이다. 수정에서 생략은 보존, 명시적 null은 해제다. 빈 문자열·공백 문자열은 오류다. 해제된 아이디는 다른 사용자가 사용할 수 있다. 닉네임 중복 규칙은 기존 대소문자 구분·trim·NFC 정책을 유지한다.
- `bio`는 ISO 제어 문자와 Unicode 줄/문단 구분자(U+2028·U+2029)를 허용하지 않는다. `strip()` 후 NFC 정규화하고 Java `String.length()` 기준 160자 이하인지 검사한다. 이모지 같은 보조 문자는 UTF-16 두 자리로 계산한다. 생략 또는 null로 등록할 수 있고 수정에서 생략은 보존, null·정규화 후 빈 문자열은 삭제다.
- 기존 `nickname`·`profileImageKey`만 사용하는 등록·수정 요청은 계속 유효하다. 이메일·인증 필드는 수정할 수 없으며 알 수 없는 필드, 문자열이 아닌 JSON 값은 400 `COMMON_002`로 거절한다.
- 기존 닉네임·이미지 소유권·업로드 검사·최초 등록 시각·프로필 등록 상태를 유지한다. 새 필드만 수정해도 이미지와 닉네임 및 최초 등록 시각을 보존한다. 미등록 사용자는 먼저 기존 프로필 등록 API를 사용해야 한다.
- 기존 사용자에게 아이디를 자동 부여하거나 이메일·소셜 식별자로 아이디를 생성하지 않는다. 기존 데이터는 새 컬럼이 null인 상태로 보존한다.

| 오류 | HTTP / 코드 |
| --- | --- |
| 사용자 ID가 양의 Long이 아님, 쿼리 누락, 지원하지 않는 JSON 타입/필드, 빈 PATCH | 400 / `COMMON_002` |
| 아이디·소개 정규화 후 검증 실패 | 400 / `COMMON_001` |
| 다른 사용자의 아이디와 충돌 | 409 / `USER_008` |
| 등록 중복 / 미등록 사용자 수정 | 기존 409 / `USER_002`·`USER_003` |
| 다른 닉네임과 충돌 / 이미지 검증 실패 | 기존 사용자 오류 계약 유지 |

## 동시성 및 DB 적용

중복 확인 API는 읽기 전용이며 아이디를 예약하지 않는다. 본인의 현재 아이디는 `available: true`다. 다른 사용자가 먼저 저장하면 실제 등록·수정은 409 `USER_008`을 반환한다. 같은 사용자에 대한 부분 수정은 기존 사용자 행의 비관적 잠금 후 최신 값을 읽어 합치며, 서로 다른 사용자의 같은 아이디 선점은 `uk_users_username` DB 유일 제약이 최종 판정한다. 닉네임·아이디·소개·이미지·등록 시각은 하나의 트랜잭션에서 저장되므로 실패 시 모두 롤백한다. 제약 위반은 SQLState `23505`와 알려진 아이디 제약 이름을 함께 검사하고 관련 없는 DB 오류는 중복 오류로 바꾸지 않는다.

`src/main/resources/db/manual/049_public_user_profile.sql`은 `031_user_profile.sql` 이후 실행하는 수동 PostgreSQL SQL이다. null을 허용하는 `username VARCHAR(30)`, `bio VARCHAR(160)`을 추가하고 아이디 유일 인덱스 및 필드 형식·등록 상태 CHECK를 추가한다. 여러 null 아이디는 허용한다. DDL은 한 트랜잭션에 있고 재실행 가능하다. 이미 일부 컬럼이 있는 환경에서 중복·잘못된 기존 값이 발견되면 SQL 전체가 롤백하며 원래 데이터를 자동 재작성하지 않는다. 실행 도구는 실패 시 `ROLLBACK`으로 세션을 정리해야 한다.

운영 DB 적용은 하지 않았다. 기존 운영 방식의 `ddl-auto`나 공통 설정을 변경하지 않았으며 배포 시 이 SQL 적용 순서를 별도로 관리해야 한다. PostgreSQL CHECK는 한줄소개 DB 문자 수 상한을 보조하며 Java NFC·공백 정규화와 UTF-16 길이 검증은 API에서 수행한다.

## 검증 재현

PostgreSQL 전용 임시 인스턴스의 DB URL·사용자 정보를 `ISSUE49_TEST_DB_URL`, `ISSUE49_TEST_DB_USERNAME`, `ISSUE49_TEST_DB_PASSWORD`로 전달한다. 테스트는 `issue49_` UUID 스키마를 생성·정리하고 임의 포트의 HTTP 서버를 사용한다. 운영 또는 공유 DB URL을 쓰지 않는다.

```sh
./gradlew test --tests '*PublicUserProfile*Test' --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=256m'
./gradlew test build --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=256m'
```

기존 PostgreSQL 검증은 `ISSUE31`, `ISSUE39`, `ISSUE41`, `ISSUE43`의 `TEST_DB_URL`·`TEST_DB_USERNAME`을 같은 전용 인스턴스의 테스트 DB에 전달해 실행한다. `ISSUE27_TEST_DB_URL`, `ISSUE27_TEST_DB_DRIVER=org.postgresql.Driver`, `ISSUE27_TEST_DB_USER`도 해당 전용 DB에 전달한다. #27 테스트는 기본 스키마를 생성·삭제하므로 반드시 전용 DB를 사용한다. 환경 변수 없이는 해당 PostgreSQL 테스트가 건너뛰어지며 H2 통과와 PostgreSQL 검증은 별도 증거다.

## 검증 및 리뷰 기록

2026-10-01 구현 커밋 `41e7baf`와 검증 커밋 `a07b8a9`에서 `test build`를 완료했다. Gradle `--max-workers=1`, daemon·test heap 384MB, metaspace 256MB, Spring 테스트 컨텍스트 캐시 2개로 자원을 제한했다. `/tmp/issue49-gradle.init.gradle`은 이 로컬 실행에 사용한 임시 설정이며 저장소 설정은 변경하지 않았다.

- 전체 JUnit XML 합계: 597개, 실패 0개, 오류 0개, 건너뜀 0개. 빌드 성공.
- 새 검증: H2 HTTP 13개, PostgreSQL HTTP 13개, PostgreSQL SQL 3개. 기존 프로필·거래·매장 PostgreSQL 검증도 위 전용 인스턴스에서 활성화해 실행했다.
- 공개 응답 필드 목록과 본인·타인 동일 응답, 미등록 사용자 비노출, 인증·경로·입력 오류, OpenAPI, 기존 요청 호환과 null/생략, 정규화·길이 경계를 확인했다.
- 동시 등록과 동시 수정은 각각 H2·PostgreSQL에서 3회 반복했다. 두 사전 검사를 모두 통과해도 1건만 성공하며 나머지는 409 `USER_008`이다. 실패한 닉네임·아이디·소개·이미지·등록 시각은 보존됐다. 같은 사용자의 동시 부분 수정도 모든 변경을 보존했다.
- SQL 두 번 실행, 기존 사용자 데이터 보존, null 아이디 중복 허용, 유일·형식·등록 상태 제약, 다른 테이블의 동명 CHECK, 잘못된 기존 값·중복 기존 값에 대한 전체 DDL 롤백을 확인했다.
- 테스트 후 전용 PostgreSQL `pg_namespace`에서 `issue%` 테스트 스키마 잔여 0개를 확인했다.

독립 GPT 6.1 Sol High 리뷰어가 기준 `5f6d18fb2d4d8fc1e2f435919a548c64d33c9627`부터 `a07b8a942709c25fbf0326495dfa2df06172b8da`까지 전체 diff와 이 문서를 읽기 전용으로 검토했다. 권한·개인정보 비노출·입력·정규화·null/생략·기존 사용자 호환·행 잠금·아이디 선점·실패 원자성·SQL 재실행/롤백·테스트를 확인했고 findings는 0건이다. 수정할 발견 사항이 없어 리뷰 후 코드 수정이나 테스트 재실행은 필요하지 않았다. 리뷰어는 60개 JUnit XML에서 597 tests와 실패·오류·건너뜀 0개를 직접 집계했으며 테스트를 별도로 실행하거나 DB·외부 앱에 쓰지 않았다.

PR 생성 전 `git fetch origin develop` 결과 기준 SHA가 그대로이며 최신 기준이 자기 브랜치의 조상인 것을 확인했다. 선행 기능 의존성은 없고 `develop` 대상으로 PR을 생성한다. 실제 S3·Toss·카카오, 운영 DB, CI, 배포, 실제 메시지/푸시 및 사용자 환경은 이 로컬 검증의 대상이 아니다. S3는 테스트 대역으로만 검증했으며 기존 실제 연동 미완료 상태를 유지한다. #45 구현이나 다른 이슈 기능은 이 브랜치에 포함하지 않는다.
