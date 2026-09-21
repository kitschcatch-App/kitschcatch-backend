# 사용자 프로필 검증 기록

2026-09-22에 이슈 #31의 누락된 PostgreSQL 검증을 실행하고 S3 연동 환경을 확인했다. 운영 DB나 운영 S3 객체는 변경하지 않았다.

## 격리 PostgreSQL 검증

PostgreSQL 14.18을 임시 데이터 디렉터리와 루프백 포트 `55431`에서 실행했다. 전용 데이터베이스 `issue31_profile_verification` 안에 테스트마다 UUID 스키마를 만들고 종료 시 제거했다. 종료 후 남은 테스트 스키마는 0개였다.

`UserProfileMigrationTest`는 기존 `users` 구조와 중복 카카오 닉네임을 넣고 저장소의 `031_user_profile.sql` 원문을 실행한다. `UserProfilePostgresHttpTest`는 애플리케이션의 다른 테이블을 구성한 뒤 Hibernate가 생성한 프로필 컬럼·유일 제약을 제거하고 같은 SQL 원문을 적용한다. 닉네임 경합은 수동 SQL의 부분 유일 인덱스를 사용한다.

| 검증 | 결과 |
| --- | --- |
| 기존 중복 닉네임·사용자 정보 보존, 신규 프로필 값 null 유지, SQL 재실행. | 통과. |
| 미등록 사용자의 닉네임 키·이미지 설정 및 등록 시각만 설정하는 변경 차단. | CHECK 위반 `23514` 확인. |
| 등록 사용자의 닉네임 키 중복 차단. | 유일 제약 위반 `23505` 확인. |
| 다른 테이블에 같은 이름의 CHECK 제약이 있는 경우. | 수정 전 누락 재현, 수정 후 제약 적용 확인. |
| 기존 데이터가 CHECK 제약을 위반할 때 SQL 전체 롤백. | 신규 컬럼·인덱스 생성 취소와 기존 데이터 보존 확인. |
| 서로 다른 사용자 2명의 동일 닉네임 등록 경합. | 실제 중복 조회를 둘 다 통과시킨 후 저장을 경합시켰다. 5회 모두 HTTP 201 한 건·409 `USER_004` 한 건, 실패 사용자의 원래 상태 보존 확인. |
| 같은 사용자의 동시 최초 등록. | HTTP 201 한 건·409 `USER_002` 한 건 확인. |
| 닉네임만 수정하는 요청과 이미지만 수정하는 요청의 동시 실행. | 두 변경과 최초 등록 시각 보존 확인. |
| S3 권한 오류를 주입한 최초 등록·수정. | HTTP 500 및 DB 부분 변경 없음 확인. 실제 AWS 권한 검증과 별개인 장애 주입 테스트다. |
| S3 응답을 대기시키는 동안 별도 DB 연결의 `FOR UPDATE NOWAIT`. | 수정 전 행 잠금 오류 재현, 수정 후 즉시 잠금 획득 확인. |
| 닉네임 중복과 무관한 DB CHECK 위반. | 수정 전 잘못된 HTTP 409 재현, 수정 후 HTTP 500 `COMMON_999` 및 롤백 확인. |

마이그레이션 3건과 HTTP 검증 10건이 통과했다. HTTP 테스트는 실제 서버·JWT 필터·PostgreSQL을 사용하며 `ProfileImageStorage`만 테스트 대역으로 바꾼다.

## 재현 방법

PostgreSQL 실행 도구와 Java 21이 있는 환경에서 저장소 루트 기준으로 실행한다. 아래 명령은 기존 서버와 분리된 임시 클러스터를 만들며, 지정 포트가 이미 사용 중이면 다른 포트를 사용한다.

```sh
set -e
profile_test_root=$(mktemp -d "${TMPDIR:-/tmp}/kitschcatch-31-postgres.XXXXXX")
profile_test_port=55431
initdb -D "$profile_test_root/data" -A trust --encoding=UTF8 --locale=C
pg_ctl -D "$profile_test_root/data" -l "$profile_test_root/server.log" \
  -o "-h 127.0.0.1 -p $profile_test_port -k $profile_test_root" -w start
trap 'pg_ctl -D "$profile_test_root/data" -m fast -w stop' EXIT
createdb -h 127.0.0.1 -p "$profile_test_port" issue31_profile_verification
export ISSUE31_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$profile_test_port/issue31_profile_verification"
export ISSUE31_TEST_DB_USERNAME="$(id -un)"
export ISSUE31_TEST_DB_PASSWORD=
./gradlew test --tests '*UserProfileMigrationTest' --tests '*UserProfilePostgresHttpTest' --rerun-tasks
```

`ISSUE31_TEST_DB_URL`이 없으면 PostgreSQL 전용 테스트는 건너뛴다. H2나 건너뛴 테스트를 PostgreSQL 통과 근거로 사용하면 안 된다. Gradle이 이전 실행 결과를 재사용하지 않도록 개별 재현 명령에는 `--rerun-tasks`를 포함했다.

최종 검증은 같은 PostgreSQL 환경 변수를 설정한 상태에서 `./gradlew test build --console=plain`으로 수행했다. 전체 193개 테스트가 통과했고 실패·오류·건너뜀은 모두 0건이다. 빌드도 성공했다.

## 수정한 문제

- 수동 SQL의 `pg_constraint` 조회에 `conrelid = 'users'::regclass`를 추가해 현재 스키마의 대상 테이블 제약만 확인한다.
- S3 검증은 쓰기 트랜잭션 전에 수행한다. `UserProfileTransactionService`가 잠금 뒤 최신 상태를 재검증하고 전달된 필드만 저장한다.
- 닉네임 유일 제약 이름을 `uk_users_nickname_key`로 고정하고 해당 제약 위반만 트랜잭션 종료 후 409로 변환한다. 다른 무결성 오류는 기존 서버 오류 처리에 전달한다.

## 실제 S3 연동 상태

실제 업로드 URL 발급 → S3 PUT → 프로필 저장 검증은 완료되지 않았다. 다음은 추측이 아니라 이번에 실행하거나 코드에서 확인한 결과다.

- 로컬 `dev-kc` 프로필의 STS 요청은 `InvalidClientTokenId`, S3 `ListBuckets` 요청은 `InvalidAccessKeyId`로 실패했다.
- 애플리케이션의 기본 자격 증명 체인이 사용하는 `default` 프로필은 STS와 버킷 목록 조회에 성공했다. 이름으로 프로젝트와 연결할 수 있는 버킷은 찾지 못했으며, 이 결과만으로 해당 계정에 프로젝트 버킷이 없다고 단정하지 않는다.
- 현재 실행 환경에는 `APP_S3_BUCKET`이 없고 저장소 `.env`도 없다. 테스트에 사용할 버킷을 특정하지 못했다.
- 현재 브랜치의 업로드 URL 발급 API는 판매글·채팅용이다. 각각 `posts/...`, `chats/...` 키를 사용하며 프로필 저장소가 허용하는 `profiles/{userId}/...`와 다르다. 프로필 전용 발급 API는 현재 구현되어 있지 않다.
- 이슈 #31도 프로필 이미지 업로드 URL 발급을 별도 연계 작업으로 명시한다. 임의로 판매글 키를 프로필 키로 허용하거나 별도 API가 구현됐다고 간주하지 않았다.

실제 연동 검증에는 사용할 로컬 AWS 프로필 이름과 테스트 버킷, 프로필용 업로드 URL 발급 API 구현 또는 별도 배포 주소가 필요하다. 확보되면 발급 URL로 테스트 이미지를 업로드하고 실제 S3 어댑터와 인증된 프로필 API로 등록·조회·수정·미업로드·타인 키 거절을 검증한 뒤, 이번 검증에서 생성한 객체만 정리한다. 현재 AWS 성공 검증으로 표시한 항목은 없다.
