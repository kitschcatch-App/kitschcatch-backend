# 관심 매장 환경 변수와 적용 설정

관심 매장 기능이 새로 요구하는 **백엔드 환경 변수는 없다**. 기존 DB와 JWT 설정을 사용하며 네이버·S3·외부 API를 호출하지 않는다. 비밀 값은 저장소와 Obsidian에 기록하지 않는다.

## 실행 환경

| 환경 변수 | 설정할 내용 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 환경에 맞는 `local`, `dev`, `prod`. |
| `LOCAL_DB_URL`, `LOCAL_DB_USERNAME`, `LOCAL_DB_PASSWORD` | local PostgreSQL 접속 URL·계정·비밀번호. |
| `DEV_DB_URL`, `DEV_DB_USERNAME`, `DEV_DB_PASSWORD` | 개발 서버 DB 접속 값. |
| `PROD_DB_URL`, `PROD_DB_USERNAME`, `PROD_DB_PASSWORD` | 운영 DB 접속 값. |
| `APP_JWT_SECRET` | 기존 로그인에서 발급하는 access token과 같은 서명 키. 최소 32 UTF-8 바이트. 운영에서는 개발 기본값을 사용하지 않는다. |
| `KAKAO_NATIVE_APP_KEY` | 기존 앱 인증 구성을 기동하는 데 필요한 값. 관심 매장 API가 카카오를 호출하지는 않는다. |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | 수동 SQL 적용 후 운영 전체 스키마 검증에는 `validate`. 기존 local `update`가 수동 SQL 검증을 대체하지 않는다. |

환경 변수는 셸·IDE·배포 시스템에서 주입한다. 프로젝트에 `.env` 자동 로더는 없다. 앱의 기존 `API_BASE_URL`과 서비스 access token을 사용하며 관심 매장만을 위한 SDK 키·권한은 필요 없다. 지도 SDK 설정은 [기존 지도 환경 문서](store-map-environment.md)를 참조한다.

## SQL 적용 순서

1. 기존 `users` 스키마가 준비돼 있어야 한다.
2. 기존 `039_stores.sql` 적용 여부를 확인한다.
3. 같은 스키마에 `041_store_favorites.sql`을 적용한다.
4. 애플리케이션을 배포한다. 전국·주변·상세 조회도 새 테이블을 참조한다.

재실행은 기존 관계를 보존한다. 다른 정의의 테이블을 자동 수정하지는 않는다. 운영 SQL 적용과 배포는 이번 작업에서 실행하지 않았다. 실제 매장 데이터 없이도 API가 동작하지만 목록은 비어 있고 등록할 대상 매장은 없다.

## 격리 PostgreSQL 테스트

| 환경 변수 | 용도·기본값 |
| --- | --- |
| `ISSUE41_TEST_DB_URL` | `jdbc:postgresql://127.0.0.1:<port>/<test_database>`. 지정하면 관심 매장 PostgreSQL 테스트를 활성화한다. |
| `ISSUE41_TEST_DB_USERNAME` | 기본 `postgres`. |
| `ISSUE41_TEST_DB_PASSWORD` | 기본 빈 문자열. 격리 로컬 검증용이며 운영 값이 아니다. |
| `ISSUE39_TEST_DB_URL`, `ISSUE39_TEST_DB_USERNAME`, `ISSUE39_TEST_DB_PASSWORD` | 기존 매장 PostgreSQL 회귀 검증. |
| `ISSUE31_TEST_DB_URL`, `ISSUE31_TEST_DB_USERNAME`, `ISSUE31_TEST_DB_PASSWORD` | 프로필 PostgreSQL 회귀 검증. |
| `ISSUE27_TEST_DB_URL`, `ISSUE27_TEST_DB_DRIVER`, `ISSUE27_TEST_DB_USER` | 예약 PostgreSQL 회귀 검증. 드라이버 `org.postgresql.Driver`. |

관심 매장·매장·프로필 테스트는 UUID 임시 스키마를 만들고 삭제한다. 기존 예약 테스트는 기본 스키마에 DDL을 실행하므로 모든 테스트에 **폐기 가능한 전용 DB 인스턴스**를 사용한다. 운영 DB나 공유 개발 DB를 지정하지 않는다.

환경 변수를 주입한 뒤 실행한다.

```sh
./gradlew test --tests '*domain.store.*' --console=plain
./gradlew clean test build --console=plain
```

PostgreSQL용 환경 변수가 없으면 해당 테스트는 건너뛴다. 결과 집계에서 skipped 수를 확인해야 한다. 테스트 fixture는 가상 데이터이며 운영 seed로 적재하지 않는다.
