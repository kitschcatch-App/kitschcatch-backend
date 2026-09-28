# 닉네임 중복 확인 API 구현 계획

- 이슈: [#35 닉네임 중복 확인 API 구현](https://github.com/kitschcatch-App/kitschcatch-backend/issues/35).
- 브랜치: `feat/35`, 기준 `develop`의 `9fd70df`.
- 작성일: 2026-09-28.
- 명세: [Notion 닉네임 중복 확인](https://app.notion.com/p/3deee6172f5680bc9368e3974414e83b).
- 선행 작업: [사용자 프로필 계획](2026-09-20-user-profile.md), [프로필 이미지 업로드 계획과 결과](2026-09-27-profile-image-upload.md).
- 상태: 구현 전 작업 계획이다. 아래 구현·테스트·명세 동기화는 별도 완료 표시 전까지 예정 작업이다.

## 1. 목표와 범위

JWT로 인증된 사용자가 프로필 등록·수정 전에 닉네임 사용 가능 여부를 조회한다. 프로필 등록 전후 모두 사용할 수 있고, 본인의 현재 닉네임은 사용 가능으로 판단한다. 조회만으로 사용자 정보나 닉네임 점유 상태를 변경하지 않는다.

기존 `NicknamePolicy`와 `nicknameKey`를 재사용한다. 미등록 사용자의 카카오 기본 닉네임은 점유 대상으로 취급하지 않는다. 사전 확인은 닉네임 예약이 아니므로 실제 저장 시 중복 재검사와 DB 유일 제약을 유지한다.

이번 범위는 API, 등록·수정과의 입력 검증 일치, 실제 JWT HTTP 테스트, 격리 PostgreSQL 검증, Swagger·Notion·클라이언트 사용 문서까지다. 로그인·토큰 갱신 응답 변경, 새 닉네임 정책, DB 스키마 변경, 실제 AWS S3 연동 검증은 포함하지 않는다. 실제 S3 검증은 앞선 사용자 요청에 따라 후속 작업으로 남긴다.

## 2. 원문 확인과 API 계약

2026-09-28 Notion 상세 페이지를 직접 확인했다. 원문에 있는 내용과 이번 계획에서 보완할 내용을 구분한다.

| 항목 | Notion 원문 | 구현 계획 |
| --- | --- | --- |
| 요청. | `GET /api/users/nickname-availability?nickname={nickname}`. | 경로·메서드·쿼리 이름을 그대로 사용한다. |
| 인증. | `Authorization: Bearer {accessToken}`. | JWT principal의 사용자 ID를 사용한다. |
| 대상. | 회원가입 시 사용 가능 여부 확인. | 이슈 #35에 따라 프로필 등록·수정에 모두 사용한다. |
| 성공 데이터. | `data.nickname`만 예시가 있다. | 정규화한 `nickname`과 boolean `available`을 반환한다. |
| 사용 중인 닉네임. | 응답 규칙이 없다. | HTTP 200, `available: false`로 반환한다. |
| 오류. | 제목만 있고 목록은 비어 있다. | 아래 공통 오류 계약을 문서화한다. |
| null 직렬화. | 성공 예시에 `error: null`이 있다. | 기존 `ApiResponse` 규칙에 따라 null 필드를 생략한다. |

`available`과 오류 표는 원문에 이미 명시된 내용이 아니라 이번 설계 결정이다. 정상적인 중복 조회는 성공 응답으로 표현하고, 실제 저장 충돌은 기존 HTTP 409 `USER_004`로 구분한다. 구현 완료 후 Notion 설명·응답 예시·오류 목록을 함께 맞춘다.

### 요청과 응답

```http
GET /api/users/nickname-availability?nickname=%20%ED%82%A4%EC%B9%98%EC%BA%90%EC%B2%98%20
Authorization: Bearer <accessToken>
```

```json
{
  "success": true,
  "data": {
    "nickname": "키치캐처",
    "available": true
  }
}
```

다른 등록 사용자가 점유한 경우에도 같은 형식으로 `available`만 `false`가 된다. 요청 본문이나 별도 사용자 ID를 받지 않는다. 추가 쿼리로 `userId`를 보내더라도 본인 제외 기준에 사용하지 않는다.

| 상황 | HTTP | 응답 또는 오류 코드 |
| --- | --- | --- |
| 사용 가능한 닉네임·본인 닉네임. | 200. | `available: true`. |
| 다른 등록 사용자의 닉네임. | 200. | `available: false`. |
| `nickname` 쿼리 누락. | 400. | `COMMON_002`, 기존 필수 쿼리 누락 처리. |
| 빈 값·공백만 있는 값·정규화 후 길이 초과. | 400. | `COMMON_001`, 기존 `NicknamePolicy` 오류. |
| 인증 누락·만료·잘못된 토큰. | 401. | `AUTH_004`. |
| 유효한 토큰의 사용자 ID가 DB에 없음. | 404. | `USER_001`. |
| 예상하지 못한 DB·서버 오류. | 500. | `COMMON_999`. |

오류 응답은 기존 `ApiResponse.fail`을 사용한다. 이 조회에서 프로필 미등록·이미 등록 오류인 `USER_002`, `USER_003`은 발생시키지 않는다. 인증은 보안 필터에서 먼저 처리하고, 컨트롤러 바인딩 후 서비스에서는 닉네임 검증, 사용자 존재 확인, 중복 조회 순서로 처리한다.

## 3. 정규화와 기존 입력 검증 정리

`NicknamePolicy.normalize`의 현재 동작을 중복 확인·등록·수정에 공통 적용한다.

- null·빈 값·공백만 있는 값은 거절한다.
- Java `trim()` 후 Unicode NFC 정규화를 수행한다.
- 정규화 결과가 비어 있거나 `String.length()` 기준 50을 초과하면 거절한다.
- 기존 길이 기준은 UTF-16 코드 단위다. 이슈 #35에서 코드 포인트나 화면상 글자 수 기준으로 바꾸지 않는다.
- 대소문자를 구분하고 내부 공백을 유지한다. 소문자 변환, `strip()` 전환, 새 금칙어·허용 문자 정책을 추가하지 않는다.

현재 `RegisterUserProfileRequest`와 `UpdateUserProfileRequest`에는 원문에 적용되는 `@Size(max = 50)`가 있다. 공백을 포함한 51자 입력이 정규화 후 50자가 되거나, 분해형 한글이 NFC 적용 후 50자 이하가 되는 경우 서비스 정책에 도달하기 전에 HTTP 요청이 거절된다.

닉네임 필드의 원문 길이 제한을 제거하고 정규화 후 길이 검증을 `NicknamePolicy`에 맡긴다. 등록의 필수값 검증, 이미지 키의 `@Size(max = 512)`, PATCH 필드 생략·명시적 null 구분은 유지한다. 정규화 후 유효한 입력은 조회와 저장에서 동일하게 허용하고, 51자 이상인 정규화 결과는 모두 거절하는 HTTP 회귀 테스트를 추가한다.

## 4. 구현 구조와 데이터 보장

Java 경로는 `src/main/java/com/kitschcatch/backend` 기준이다.

| 대상 | 변경 내용 |
| --- | --- |
| `domain/user/dto/NicknameAvailabilityResponse.java`. | 정규화한 `nickname`, boolean `available`과 Swagger 스키마를 추가한다. |
| `domain/user/service/UserService.java`. | `@Transactional(readOnly = true)` 조회 메서드를 추가한다. 기존 `findUser`와 `NicknamePolicy`를 재사용한다. |
| `domain/user/controller/UserController.java`. | 필수 `nickname` 쿼리와 인증 principal을 서비스에 전달하고 `ApiResponse.success`를 반환한다. |
| `domain/user/dto/RegisterUserProfileRequest.java`, `UpdateUserProfileRequest.java`. | 닉네임의 정규화 전 길이 제한을 정리하고 정책 설명을 맞춘다. |
| `domain/user/repository/UserRepository.java`. | 기존 `existsByNicknameKeyAndIdNot`을 재사용한다. 새 쿼리·행 잠금은 추가하지 않는다. |
| `domain/user/service/UserProfileTransactionService.java`. | 저장 시 중복 검사·행 잠금·`saveAndFlush`를 유지한다. 변경 필요 여부는 회귀 검증으로 확인한다. |

조회 서비스는 사용자 존재를 일반 조회로 확인한 뒤 `!existsByNicknameKeyAndIdNot(normalizedNickname, userId)`를 반환한다. `nicknameKey`가 null인 미등록 사용자는 같은 기본 닉네임을 가지고 있어도 중복 대상에 들어가지 않는다. 본인 제외 기준은 인증된 사용자 ID다.

조회에서 `findByIdForUpdate`, 저장 메서드, 프로필 등록 상태 변경, S3 메서드를 호출하지 않는다. `UserMeResponse` 변환도 필요하지 않다. 별도 예약 테이블·캐시·락을 도입하지 않는다.

조회 이후 다른 사용자가 먼저 저장할 수 있다. 등록·수정 시 기존 재검사와 `uk_users_nickname_key` 유일 제약으로 최종 충돌을 차단한다. 해당 제약 위반만 `USER_004`로 변환하고, 다른 무결성 오류를 닉네임 중복으로 바꾸지 않는다. 실패 요청의 닉네임·이미지 키·등록 시각은 보존한다.

## 5. 검증 계획

### 정책·서비스·실제 HTTP

| 검증 대상 | 주요 사례와 기대 결과 |
| --- | --- |
| 입력 경계. | null·빈 값·공백, 정규화 후 1·50·51자, 앞뒤 공백, NFC·NFD 동등성, 대소문자·내부 공백 정책을 확인한다. |
| 조회 판단. | 사용 가능한 값, 타인 점유, 본인 닉네임, 미등록 사용자의 기본 닉네임을 구분한다. 등록 전후 사용자 모두 조회할 수 있다. |
| 요청과 응답. | 실제 서버와 JWT로 GET 요청을 보내 HTTP 상태, 정규화한 `nickname`, boolean `available`, null 필드 생략, 공통 오류 구조를 확인한다. |
| 인증과 사용자. | 토큰 누락·만료·잘못된 서명·잘못된 형식과 삭제되었거나 존재하지 않는 사용자 ID를 검증한다. |
| 쿼리 처리. | 쿼리 누락과 빈 값을 구분하고 한글·공백·리터럴 `+`를 URL 인코딩해 전달한다. 추가 `userId`가 본인 제외 기준을 바꾸지 않는지 확인한다. |
| 저장과의 일치. | 원문은 50자를 초과하지만 정규화 후 유효한 공백·NFD 입력을 GET·POST·PATCH에 보내 같은 정책이 적용되는지 확인한다. |
| 읽기 전용 동작. | 조회 전후 사용자 상태가 같고 S3 및 사용자 행 잠금 호출이 없는지 확인한다. |
| 기존 PATCH 계약. | 닉네임 생략은 유지, 명시적 null은 오류, 이미지 필드 생략·null 동작과 등록 시각 보존을 회귀 검증한다. |
| Swagger. | 실제 OpenAPI 문서의 경로, 필수 쿼리, bearer 인증, 응답 필드와 오류 설명을 확인한다. |

정책·서비스 단위 테스트와 `UserProfileHttpTest` 또는 별도 닉네임 HTTP 테스트를 추가한다. 컨트롤러 테스트 대역만으로 JWT 필터와 직렬화 검증을 완료 처리하지 않는다.

### 격리 PostgreSQL과 동시 저장

기존 `PostgresProfileTestDatabase`, `UserProfileMigrationTest`, `UserProfilePostgresHttpTest`를 재사용한다. 전용 임시 PostgreSQL과 테스트별 UUID 스키마에서 저장소의 `031_user_profile.sql` 원문을 적용한다. 신규 마이그레이션은 필요하지 않다.

1. A가 사용 가능 응답을 받은 후 B가 먼저 등록하면 A의 등록은 409 `USER_004`이고 A는 미등록 상태를 유지해야 한다.
2. 이미 등록한 A가 사용 가능 응답을 받은 후 B가 점유하면 A의 PATCH는 409 `USER_004`이고 기존 프로필 전체를 보존해야 한다.
3. 두 사용자 모두 사용 가능 응답을 받은 뒤 같은 닉네임으로 동시에 등록한다. 저장 내부의 중복 조회도 둘 다 통과하도록 동기화해 DB 유일 제약 경로를 실제로 실행한다. 201 한 건·409 한 건과 단일 점유를 확인한다.
4. 두 등록 사용자의 동일 닉네임 변경 경합도 200 한 건·409 한 건으로 끝나며 실패 사용자의 기존 정보가 보존되는지 확인한다.
5. 기존 수동 SQL 재실행·제약 적용·롤백, 같은 사용자 최초 등록 경합, 부분 수정, 닉네임과 무관한 DB 오류 처리 테스트를 함께 실행한다.

PostgreSQL 실행과 정리는 [기존 격리 DB 재현 절차](../user-profile-verification.md#재현-방법)를 따른다. 이번 검증용 데이터베이스·포트를 따로 선택하고, 기존 도우미와 연결하기 위해 `ISSUE31_TEST_DB_URL`, `ISSUE31_TEST_DB_USERNAME`, `ISSUE31_TEST_DB_PASSWORD` 이름을 유지한다. 이 환경 변수가 설정된 상태에서 최종 검증을 수행한다.

```sh
./gradlew test build --rerun-tasks --console=plain
```

환경 변수가 없으면 PostgreSQL 테스트가 건너뛰어지므로 XML 결과의 실행·실패·오류·건너뜀 수를 확인한다. 실제 실행한 테스트 수와 빌드 결과, 테스트 스키마 정리 및 임시 DB 종료 여부를 기록한다. 과거 이슈의 테스트 수를 이번 결과로 재사용하지 않는다. CI·배포·실제 AWS 검증은 로컬 테스트 결과와 구분한다.

## 6. 작업 순서와 커밋 분할

최근 이슈 작업과 같은 `35 type: 한국어 설명` 형식을 사용한다. 각 구현 커밋에는 해당 동작을 입증하는 테스트와 컴파일에 필요한 호출부 변경을 함께 넣는다.

| 순서 | 커밋 메시지 | 완료 범위 |
| --- | --- | --- |
| 1. | `35 docs: 닉네임 중복 확인 API 구현 계획 작성`. | 현재 작업 문서. |
| 2. | `35 fix: 프로필 닉네임 길이를 정규화 후 검증`. | 등록·수정 DTO 길이 검증 정리, 정책 경계와 저장 HTTP 회귀 테스트. |
| 3. | `35 feat: 인증 사용자 기준 닉네임 사용 가능 여부 조회`. | 응답 DTO·읽기 전용 서비스, 본인 제외·미등록 사용자·정규화·사용자 오류 테스트. |
| 4. | `35 feat: 닉네임 중복 확인 API와 응답 명세 추가`. | 컨트롤러·Swagger, 실제 JWT HTTP와 입력·오류·직렬화 테스트. |
| 5. | `35 test: 닉네임 사전 확인 후 PostgreSQL 저장 경합 검증`. | 선점·등록 경합·수정 경합과 실패 원자성, 기존 SQL 회귀 검증. |
| 6. | `35 docs: 닉네임 확인 API 사용법과 검증 결과 정리`. | 요청·응답 예시, 조회 후 저장 흐름, 재현 방법, 실제 검증 결과와 명세 동기화 상태. |

클라이언트 문서에는 입력 변경 시 이전 결과를 폐기하고, 조회 응답의 `nickname`과 현재 정규화된 입력이 일치하는지 확인하는 흐름을 설명한다. `available: true` 이후에도 저장 API의 409를 처리해야 한다. Notion에는 프로필 수정 지원, 본인 제외, 정규화, 오류 목록과 예약 보장이 없다는 설명을 함께 반영한다.

## 7. 진행 상태

- [x] 이슈 #35와 기준 브랜치·기존 구현 확인.
- [x] Notion 상세 요청·응답 원문 확인과 보완할 계약 구분.
- [x] 설계·검증·커밋 분할 계획 작성.
- [ ] 등록·수정과 닉네임 확인의 입력 검증 일치.
- [ ] 읽기 전용 서비스와 인증된 HTTP API 구현.
- [ ] 정책·서비스·실제 JWT HTTP·Swagger 검증.
- [ ] 격리 PostgreSQL 수동 SQL·선점·동시 저장·실패 원자성 검증.
- [ ] 전체 테스트·빌드 실행과 실제 결과 기록.
- [ ] Swagger·Notion·클라이언트 문서 동기화.

이번 문서 작성 단계에서는 애플리케이션 코드를 변경하거나 테스트·빌드를 실행하지 않았다. 문서 내용과 코드·명세의 일치 및 Git 공백 오류를 확인한 뒤 문서만 커밋한다.
