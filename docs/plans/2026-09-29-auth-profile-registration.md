# 로그인·토큰 재발급 응답의 프로필 등록 여부 구현 계획

- 이슈: [#37 로그인·토큰 재발급 응답에 프로필 등록 여부 추가](https://github.com/kitschcatch-App/kitschcatch-backend/issues/37).
- 브랜치: `feat/37`, 기준 `develop`의 `d70e4d4`.
- 작성일: 2026-09-29.
- 선행 작업: [사용자 프로필 계획](2026-09-20-user-profile.md), [닉네임 중복 확인 계획과 결과](2026-09-28-nickname-availability.md).
- 명세 참고: [Notion API 명세서](https://app.notion.com/p/API-341ee6172f5680f0be7dfd415a5bfab6).
- 상태: 2026-09-29 코드·로컬 검증과 Notion 명세 동기화 완료. 인증 응답 계약과 결과는 [인증 응답의 프로필 등록 여부](../auth-profile-registration.md)에 정리했다.

## 1. 목표와 범위

카카오 로그인과 토큰 재발급 성공 응답의 `data.user`에 boolean `profileRegistered`를 추가한다. 클라이언트가 인증 직후 이 값으로 프로필 등록 화면과 일반 화면을 선택할 수 있도록 한다.

판정 기준은 기존 `User.isProfileRegistered()`다. `profileRegisteredAt`이 null이면 `false`, 값이 있으면 `true`다. 카카오 기본 닉네임이나 프로필 이미지가 존재하는지만으로 등록을 판단하지 않는다. 이 필드는 프로필 등록 여부를 뜻하며, 약관 동의 등 별도 가입 절차 전체의 완료 여부를 표현하지 않는다.

이번 범위는 두 인증 응답의 필드 추가, 저장된 사용자 상태의 반영, 회귀 테스트, Swagger·Notion·클라이언트 사용 문서다. 기존 사용자 프로필 정책과 `GET /api/users/me`의 응답은 유지한다. 새 DB 컬럼·마이그레이션·JWT 클레임·상태 캐시는 필요하지 않다. 실제 AWS S3 연동 검증은 #33의 후속 작업으로 남긴다.

## 2. 현재 구현과 응답 계약

Java 경로는 `src/main/java/com/kitschcatch/backend` 기준이다.

| 현재 코드 | 확인한 동작과 적용 방향 |
| --- | --- |
| `domain/auth/dto/AuthUserResponse.java`. | 현재 `id`, `email`, `nickname`을 반환한다. 여기에 primitive `boolean profileRegistered`를 추가한다. |
| `domain/auth/dto/AuthTokenResponse.java`. | 로그인과 재발급이 사용하는 공통 토큰 응답 안에 `user`가 있다. 기존 토큰 필드와 구조를 유지한다. |
| `domain/auth/service/AuthService.java`. | 두 API 모두 `issueTokenResponse(User)`로 응답을 생성한다. 이 한 경로에서 등록 여부를 매핑한다. |
| `domain/user/entity/User.java`. | `isProfileRegistered()`가 `profileRegisteredAt != null`을 반환한다. 최초 등록 시각은 프로필 수정으로 지워지지 않는다. |
| `domain/auth/repository/RefreshTokenRepository.java`. | 재발급 시 저장 토큰과 연결 사용자를 `join fetch`로 조회한다. 발급 당시 토큰에 상태를 넣지 않고 해당 요청에서 읽은 사용자 상태를 사용한다. |
| `domain/user/dto/UserMeResponse.java`. | `profileRegisteredAt`을 반환한다. 같은 사용자 상태에 대한 인증 응답의 boolean과 일치해야 한다. |

### 적용 API

| API | 변경 내용 |
| --- | --- |
| `POST /api/auth/kakao/mobile-login`. | 성공 시 `data.user.profileRegistered`를 반환한다. 기존 ID 토큰·nonce 검증과 사용자 생성 흐름을 유지한다. |
| `POST /api/auth/token/refresh`. | 성공 시 같은 필드를 반환한다. 기존 refresh token 검증·폐기·회전 흐름을 유지한다. |

두 API 모두 기존 HTTP 200과 `ApiResponse` 구조를 유지한다. 아래는 프로필 미등록 사용자의 `data.user` 부분 예시다.

```json
{
  "id": 1,
  "email": "user@example.com",
  "nickname": "카카오기본닉네임",
  "profileRegistered": false
}
```

등록 사용자는 `profileRegistered: true`를 반환한다. 필드는 문자열이나 nullable 값으로 표현하지 않으며, `false`일 때도 JSON에서 생략하지 않는다. 기존 성공 응답의 null 오류 필드 생략 규칙을 유지한다.

`profileRegistered`라는 필드명과 의미는 이슈 #37의 계약을 따른다. 구현 중 Notion 고도화 API 명세서의 카카오 모바일 로그인·토큰 재발급 항목을 브라우저에서 확인했다. 두 항목의 기존 설명은 토큰 발급만 다루고, 상세 페이지 본문에는 응답 필드나 예시가 없었다. 승인 후 [카카오 모바일 로그인](https://app.notion.com/p/3a8ee6172f5680b78580e573491fece5)과 [토큰 재발급](https://app.notion.com/p/3a8ee6172f56807d8920e58a500487ab)의 설명과 성공 응답 예시를 갱신하고, 페이지를 다시 열어 저장된 결과를 확인했다.

### 상태별 기대 결과

| 사용자 상태 | 로그인 | 토큰 재발급 |
| --- | --- | --- |
| 최초 카카오 로그인으로 생성된 사용자. | `false`. | 등록 전에는 `false`. |
| 카카오 기본 닉네임이 있지만 프로필을 등록하지 않은 기존 사용자. | `false`. | `false`. |
| 이미 프로필을 등록한 사용자. | `true`. | `true`. |
| 로그인 후 프로필 등록이 완료된 사용자. | 다음 로그인에서 `true`. | 등록 전에 발급한 유효한 refresh token으로도 `true`. |
| 등록 후 닉네임·이미지를 수정하거나 이미지 연결을 해제한 사용자. | `true`. | `true`. |

상태 전이 검증은 프로필 저장 트랜잭션이 완료된 뒤 별도 인증 요청을 보내 수행한다. 동시에 진행 중인 등록과 인증 요청 사이에 새 직렬화 보장이나 사용자 행 잠금을 추가하지 않는다. 응답은 해당 요청에서 읽은 사용자 상태를 나타내며, 이미 발급된 응답의 값을 소급해서 바꾸지 않는다.

## 3. 구현 순서와 변경 파일

1. `AuthUserResponse`에 `boolean profileRegistered`와 의미가 명확한 Swagger 설명을 추가한다. 문자열 `false`나 필드 누락이 발생하지 않도록 실제 직렬화 결과를 확인한다.
2. `AuthService.issueTokenResponse(User)`에서 `user.isProfileRegistered()`를 전달한다. 로그인·재발급별로 판단 로직을 중복 작성하지 않는다.
3. `AuthController`의 로그인·재발급 설명에 프로필 등록 여부가 함께 반환된다는 내용을 반영한다. 요청 DTO, 인증 정책, 토큰 수명과 오류 코드는 유지한다.
4. `AuthControllerTest` 등 `new AuthUserResponse(...)` 호출부를 전부 확인해 생성자 변경을 반영한다. 기존 인증 테스트의 컴파일과 검증 의미를 보존한다.
5. 서비스 테스트에 등록 여부와 저장 후 상태 전이 검증을 추가하고, 별도 실제 HTTP 테스트에서 로그인·등록·재발급 흐름을 연결한다.
6. `docs/auth-profile-registration.md`에 API 계약과 클라이언트 사용 예시를 작성하고, 이 계획에 실제 검증 결과와 명세 동기화 상태를 기록한다.

사용자 저장, 등록 시각 변경, S3 호출은 응답 매핑에서 수행하지 않는다. 새 조회나 영속성 컨텍스트 초기화가 필요한지 추측으로 변경하지 않고, 기존 조회 경로에 대한 별도 요청 테스트로 확인한다.

## 4. 검증 계획

### 서비스와 기존 인증 회귀

`AuthServiceTest`의 실제 저장소 구성을 재사용해 신규·미등록 기존·등록 기존 사용자에 대한 로그인과 재발급 응답을 검증한다. 등록 사용자에는 이미지가 없는 경우도 포함해 이미지 존재 여부와 독립적으로 `true`가 반환되는지 확인한다.

프로필 상태 변경은 저장하고 커밋한 뒤 다음 서비스 호출에서 확인한다. 하나의 영속성 컨텍스트 안에서 엔티티 필드만 변경한 결과로 DB 상태 재조회를 검증했다고 판단하지 않는다. `AuthServiceDuplicateUserTest`의 사용자 생성 충돌 후 기존 사용자 재사용 경로도 새 필드를 포함해 검증한다.

refresh token 회전, 사용한 토큰의 재사용 거절, 로그아웃 후 재발급 거절, nonce 재사용 거절, 이메일 누락과 잘못된 인증 입력에 대한 기존 회귀 테스트를 함께 실행한다. 이번 응답 필드 추가를 위해 새 오류 코드를 만들지 않는다.

### 실제 HTTP와 상태 전이

`AuthProfileRegistrationHttpTest`를 추가해 기존 `UserProfileHttpTest`처럼 `RANDOM_PORT` 서버와 `RestClient`를 사용한다. H2 테스트 DB, 실제 인증 서비스·토큰 저장소·JWT 발급 및 인증 필터를 연결한다. 외부 카카오 ID 토큰 검증과 이미지 저장소는 테스트 대역으로 처리한다. 실제 카카오·AWS 연동 검증 결과로 기록하지 않는다.

1. nonce 발급 후 신규 로그인으로 두 토큰과 `profileRegistered: false`를 받는다. 응답의 사용자 ID·이메일·닉네임도 확인한다.
2. 프로필 등록 전 토큰 재발급으로 `false`와 새 토큰을 받고, 이전 refresh token의 재사용이 거절되는지 확인한다.
3. 발급된 access token으로 프로필을 등록한다. 등록 완료 후, 등록 전에 발급받은 현재 유효한 refresh token을 사용해 `true`를 받는다.
4. `GET /api/users/me`의 등록 시각과 인증 응답을 비교하고, 재로그인에도 `true`가 반환되는지 확인한다.
5. 닉네임 변경·이미지 교체·명시적 null에 의한 이미지 연결 해제를 수행한 뒤 등록 시각과 인증 응답의 `true`가 유지되는지 확인한다. 교체 검증은 소유권·메타데이터 조건을 충족하는 저장소 대역을 사용한다.
6. 검증 실패로 프로필 등록이 거절된 사용자는 계속 `false`이고, 기존 등록 사용자의 수정 실패는 `true`를 유지하는지 확인한다.
7. JSON의 boolean 타입, `false` 필드 존재, 공통 성공·실패 구조와 기존 토큰 필드를 확인한다. HTTP 테스트에서 인증 서비스를 mock으로 대체하지 않는다.

실제 `/v3/api-docs`에서 두 인증 API가 새 필드가 포함된 사용자 스키마를 참조하고, `profileRegistered`가 boolean으로 설명되는지도 확인한다.

### 실행과 결과 기록

구현 중에는 변경한 인증 서비스·컨트롤러·HTTP 테스트와 관련 프로필 테스트를 실행한다. 최종 검증은 아래 명령을 사용한다.

```sh
./gradlew test build --rerun-tasks --console=plain
```

기존 PostgreSQL 테스트는 환경 변수에 따라 건너뛰어질 수 있다. 최종 전체 검증에서는 [격리 DB 재현 절차](../user-profile-verification.md#재현-방법)에 따라 전용 임시 DB와 `ISSUE31_TEST_DB_URL`, `ISSUE31_TEST_DB_USERNAME`, `ISSUE31_TEST_DB_PASSWORD`를 설정해 기존 회귀 테스트도 실행한다. 이슈 #37을 위한 새 SQL이나 새 DB 제약 테스트는 추가하지 않는다.

JUnit XML에서 실제 실행·실패·오류·건너뜀 수를 집계하고, 빌드 결과와 임시 DB·스키마 정리 결과를 기록한다. 환경 문제로 실행하지 못한 검증은 이유와 함께 미완료로 남긴다. 이전 이슈의 테스트 결과나 로컬 실행 결과를 CI·배포 결과로 대신하지 않는다.

## 5. 커밋 분할과 문서 반영

최근 이슈 작업과 동일한 `37 type: 한국어 설명` 형식으로 커밋한다. 각 구현 커밋은 필요한 호출부 변경과 기본 검증을 포함해 컴파일 가능한 상태로 만든다.

| 커밋 | 메시지 | 포함 범위 |
| --- | --- | --- |
| `3dfc613`. | `37 docs: 인증 응답 프로필 등록 여부 구현 계획 작성`. | 초기 계획 문서. |
| `791ad17`. | `37 feat: 로그인과 토큰 재발급에 프로필 등록 여부 반환`. | 응답 DTO, 공통 매핑, Swagger 설명, 생성자 호출부, 신규·기존 사용자 기본 서비스·컨트롤러 검증. |
| `33e3107`. | `37 test: 프로필 등록 후 재발급 상태와 중복 사용자 경로 검증`. | 저장·커밋된 등록 상태 반영, 중복 사용자 재사용 경로와 토큰 회전 검증. |
| `a3e91b6`. | `37 test: 실제 HTTP로 프로필 등록 전후 인증 흐름 검증`. | 실제 HTTP·JWT·DB 연결, 등록·재발급·재로그인·프로필 수정·실패 흐름, JSON 타입과 OpenAPI 계약. |
| `e8ad0d0`. | `37 docs: 인증 응답 계약과 검증 결과 정리`. | 클라이언트 사용 문서, 최종 검증 기록. |
| 6. | `37 docs: Notion 인증 명세 동기화 결과 기록`. | 승인 후 갱신한 Notion 두 항목과 저장 확인 결과. |

실제 변경량에 따라 같은 목적의 테스트는 해당 구현 커밋에 함께 넣되, 기능 구현·추가 상태 전이 검증·HTTP 통합 검증·문서 정리를 한 커밋으로 합치지 않는다.

클라이언트 문서에는 `profileRegistered`로 초기 화면을 선택하는 예시와 프로필 등록 성공 후 로컬 상태를 갱신하는 흐름을 설명한다. 프로필 이미지 등 전체 정보가 필요하면 기존 내 정보 조회 API를 사용한다. Notion 로그인·재발급 상세 페이지의 설명과 응답 예시를 갱신하고, 저장된 내용까지 다시 확인해 결과를 기록한다.

## 6. 진행 상태와 완료 기준

- [x] 이슈 #37과 최신 `develop`의 현재 구현 확인.
- [x] `feat/37` 브랜치 생성과 구현·검증·커밋 분할 계획 작성.
- [x] Notion 인증 항목의 기존 설명 확인 및 새 계약과 구분해 기록.
- [x] 공통 인증 응답에 `profileRegistered` 추가.
- [x] 신규·기존 사용자와 저장 후 등록 상태 전이 검증.
- [x] 실제 HTTP·JWT·토큰 회전·OpenAPI 검증.
- [x] 전체 테스트·빌드와 기존 격리 PostgreSQL 회귀 검증 결과 기록.
- [x] Swagger 설명과 실제 OpenAPI 스키마 검증.
- [x] 로컬 클라이언트 문서 반영.
- [x] Notion 명세의 설명과 응답 예시 반영.

2026-09-29 임시 격리 PostgreSQL에서 수동 SQL과 기존 프로필 경합 테스트를 포함해 `./gradlew test build --rerun-tasks --console=plain`을 실행했다. JUnit XML 286개가 통과했고 실패·오류·건너뜀은 각각 0개다. 빌드에 성공했고 `issue31_` 테스트 스키마 잔여 0개를 확인한 뒤 임시 서버를 중지했다. Swagger와 로컬 클라이언트 문서, Notion 두 항목을 반영했다. 실제 카카오·AWS S3·CI·배포 검증과는 구분한다.

완료 기준은 두 인증 API가 저장된 프로필 등록 상태를 같은 boolean 계약으로 반환하고, 프로필 등록 전 발급한 유효한 refresh token으로도 등록 완료 후 `true`가 확인되는 것이다. 코드·로컬 검증 기준으로 충족했고 Notion 명세의 저장 결과도 확인했다.
