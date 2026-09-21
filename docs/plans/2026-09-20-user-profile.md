# 내 정보 조회·프로필 등록·수정 설계 계획

- 작성일: 2026-09-20.
- 이슈: [#31 내 정보 조회, 프로필 등록 및 수정 API 구현](https://github.com/kitschcatch-App/kitschcatch-backend/issues/31).
- 작업 브랜치: `feat/31`.
- 기준 코드: 최신화한 `develop`의 `d623c00efbab60c47fe0fa305067248ec71eac06`.
- 상태: 1차 구현 완료. API·DB·테스트를 반영했으며, 실제 Notion 명세 대조·업로드 URL 발급 연동·PostgreSQL 운영 검증은 후속 확인 대상이다.
- 명세 확인 상태: GitHub 이슈 본문과 현재 코드를 확인했다. 연결된 Notion에서 `키치캐치`, `kitschcatch`, `프로필`로 검색했으나 해당 프로젝트 명세를 찾지 못했다. 아래 필드·검증 정책은 구현 가능한 제안이며, Notion 확정 명세로 간주하지 않는다.

## 1. 목표와 범위

카카오 로그인으로 생성된 사용자가 자신의 정보를 조회하고, 최초 프로필을 등록한 뒤 필요한 필드만 수정할 수 있게 한다. 대상 사용자는 access token의 인증 주체로 결정한다.

이번 이슈의 구현 범위는 다음과 같다.

- `GET /api/users/me`: 내 정보 조회.
- `POST /api/users/me/profile`: 최초 프로필 등록.
- `PATCH /api/users/me`: 등록된 프로필 부분 수정.
- 프로필 필드와 등록 상태 저장, 입력 검증, 중복 등록·동시 요청 처리.
- 저장 시 닉네임 중복 검증과 프로필 이미지 키 검증 연동.
- API·인증·DB 테스트와 Swagger·Notion 명세 동기화.

닉네임 사용 가능 여부를 조회하는 별도 API, 프로필 이미지 업로드 URL 발급 API, 로그인 응답의 가입 완료 여부 추가는 이슈에 명시된 연계 작업으로 둔다. 이 문서에서는 이들과 공유해야 할 규칙을 정의한다. 회원 탈퇴, 이메일 변경, 다른 사용자의 공개 프로필 조회, 기존 상품·채팅 API에 대한 가입 완료 접근 제한은 추가하지 않는다.

## 2. 현재 코드와 설계 근거

아래 경로는 저장소 루트 기준이다.

| 코드 | 확인한 현재 동작 | 설계에 반영할 내용 |
| --- | --- | --- |
| `src/main/java/com/kitschcatch/backend/domain/user/entity/User.java` | `id`, 필수 `nickname(50)`, 유일 `email(255)`, 인증 공급자·공급자 ID, 생성 시각을 저장한다. | 필수 닉네임 존재만으로 프로필 등록 여부를 판단할 수 없다. 기존 인증 필드를 유지하고 프로필 상태를 추가한다. |
| `src/main/java/com/kitschcatch/backend/domain/user/controller/UserController.java`, `src/main/java/com/kitschcatch/backend/domain/user/service/UserService.java` | 내용이 없는 클래스다. | 기존 `domain/user` 아래에서 세 API를 구현한다. |
| `src/main/java/com/kitschcatch/backend/domain/user/repository/UserRepository.java` | 공급자·공급자 ID 조회만 추가되어 있다. | 본인 행 잠금 조회와 닉네임 점유 조회가 필요하다. |
| `src/main/java/com/kitschcatch/backend/domain/auth/service/KakaoUserService.java` | 신규 로그인 때 카카오 닉네임 또는 `kakao-<subject>`로 사용자를 생성한다. | 카카오 기본 닉네임과 사용자가 등록한 닉네임의 점유 정책을 분리한다. |
| `src/main/java/com/kitschcatch/backend/domain/auth/service/AuthService.java` | 기존 사용자는 재사용하며 로그인·재발급 응답에 DB의 `id`, `email`, `nickname`을 넣는다. | 사용자 프로필을 다시 카카오 값으로 덮어쓰지 않는 동작을 유지한다. |
| `src/main/java/com/kitschcatch/backend/global/security/AuthenticatedUser.java`, `src/main/java/com/kitschcatch/backend/global/security/SecurityConfig.java` | principal은 `userId`를 담으며 `/api/users/**`는 인증 예외가 아니다. | `@AuthenticationPrincipal`을 사용하고 사용자 API를 `permitAll`에 추가하지 않는다. |
| `src/main/java/com/kitschcatch/backend/global/response/ApiResponse.java`, `src/main/java/com/kitschcatch/backend/global/response/ResponseStatusSetterAdvice.java` | 공통 본문과 200·201·실패 HTTP 상태를 지원한다. | 기존 응답 형태를 사용한다. |
| `src/main/java/com/kitschcatch/backend/domain/post/service/PostImageStorage.java`, `src/main/java/com/kitschcatch/backend/domain/post/storage/S3PostImageStorage.java` | 사용자별 이미지 키, 업로드 존재 확인, URL 생성을 분리한다. | 역할 구분을 참고하되 프로필용 키 공간과 검증을 별도로 둔다. |
| `src/main/java/com/kitschcatch/backend/domain/order/entity/PurchaseOrder.java` | 주문 생성 당시 판매자 닉네임을 복사한다. | 프로필 수정으로 과거 주문 스냅샷을 변경하지 않는다. |

`UpdatePostRequest`처럼 모든 값을 nullable record 필드로 받으면 생략과 명시적 null을 구분하기 어렵다. 프로필 PATCH에는 필드 전달 여부를 별도로 보존하는 DTO가 필요하다.

## 3. 요청·응답 계약 제안

### 3.1 필드

Notion 원문 확인 전에는 기존 사용자 정보와 이슈에서 언급한 닉네임·이미지만 제안한다. 실명, 생년월일, 성별, 전화번호, 자기소개 등의 필수 여부를 추정해 추가하지 않는다.

| 필드 | 등록 요청 | 수정 요청 | 조회·저장 성공 응답 | 정책 제안 |
| --- | --- | --- | --- | --- |
| `id` | 받지 않는다. | 받지 않는다. | 필수. | 인증 사용자 ID다. |
| `email` | 받지 않는다. | 받지 않는다. | 필수. | 기존 로그인 계정의 이메일이며 이 API로 변경하지 않는다. |
| `nickname` | 필수. | 전달할 때만 변경한다. | 필수. | 미등록 사용자는 기존 카카오 기본 닉네임, 등록 후에는 사용자 지정 닉네임이다. |
| `profileImageKey` | 선택. | 생략·null·값을 구분한다. | 선택 이미지의 키 또는 null. | 서버가 발급한 본인 프로필용 object key만 허용한다. |
| `profileImageUrl` | 받지 않는다. | 받지 않는다. | URL 또는 null. | 저장된 키로 서버가 생성한다. |
| `profileRegisteredAt` | 받지 않는다. | 받지 않는다. | 등록 시각 또는 null. | 프로필 등록 상태를 나타낸다. 전체 회원가입 완료 여부와는 구분한다. |

`profileRegisteredAt`은 최초 등록 때 서버가 생성하며 수정해도 유지한다. 내부에는 `Instant`, DB에는 `timestamptz`, API에는 UTC ISO-8601 문자열을 사용하는 안으로 둔다. 기존 `User.createdAt`의 타입과 과거 값은 이번 작업에서 변환하지 않는다.

`UserMeResponse`의 선택 필드는 값이 없으면 명시적 null로 반환하도록 DTO 직렬화를 정의한다. 공통 `ApiResponse`의 성공 시 `error` 생략 규칙은 유지한다. Notion에 응답 필드나 null 생략 규칙이 있으면 구현 전에 대조해 확정한다.

### 3.2 엔드포인트와 상태 처리

세 API 모두 `Authorization: Bearer <accessToken>`을 요구한다. 서비스에는 `AuthenticatedUser.userId()`만 전달하고 요청의 사용자 ID로 조회·수정 대상을 선택하지 않는다.

| 요청 | 상태 | 결과 |
| --- | --- | --- |
| GET `/api/users/me` | 미등록 또는 등록 완료. | 200과 `ApiResponse<UserMeResponse>`. 미등록이면 등록 시각·이미지는 null이고 계정 기본 정보를 제공한다. |
| POST `/api/users/me/profile` | 미등록이며 입력·연계 검증 통과. | 201과 저장된 내 정보. 프로필과 등록 시각을 한 트랜잭션에서 저장한다. |
| POST `/api/users/me/profile` | 이미 등록됨. | 409. 같은 본문을 재전송해도 기존 프로필을 덮어쓰지 않는다. |
| PATCH `/api/users/me` | 등록 완료이며 입력·연계 검증 통과. | 200과 변경 후 내 정보. 전달된 필드만 변경한다. |
| PATCH `/api/users/me` | 미등록. | 409. 최초 등록 API를 사용하도록 안내한다. |
| 모든 요청 | 인증 누락·만료·서명 오류. | 기존 401 `AUTH_004`. |
| 모든 요청 | JWT는 유효하지만 DB에 사용자 없음. | 기존 404 `USER_001`. |

POST 응답이 유실되면 GET으로 등록 상태와 저장값을 확인한다. 등록 재시도로 409가 반환되는 것과 최초 저장 실패를 구분할 수 있게 클라이언트 계약에 기록한다.

등록 요청에서 `nickname` 생략·null·빈 값은 거절한다. `profileImageKey` 생략 또는 null은 이미지 없이 등록하는 의미이며, 빈 문자열은 거절한다. 다음은 이미지가 있는 등록 요청 예시다.

```json
{
  "nickname": "키치수집가",
  "profileImageKey": "profiles/42/123e4567-e89b-12d3-a456-426614174000.png"
}
```

등록 성공 응답 예시다.

```json
{
  "success": true,
  "data": {
    "id": 42,
    "email": "collector@example.com",
    "nickname": "키치수집가",
    "profileImageKey": "profiles/42/123e4567-e89b-12d3-a456-426614174000.png",
    "profileImageUrl": "https://cdn.example.com/profiles/42/123e4567-e89b-12d3-a456-426614174000.png",
    "profileRegisteredAt": "2026-09-20T03:00:00Z"
  }
}
```

### 3.3 PATCH의 생략·null·빈 값

| 입력 | `nickname` | `profileImageKey` |
| --- | --- | --- |
| 필드 생략. | 기존 값 유지. | 기존 값 유지. |
| 명시적 null. | 400. 필수 닉네임을 지울 수 없다. | 기존 이미지 연결 해제. |
| 빈 문자열·공백만 전달. | 400. | 400. 삭제는 null을 사용한다. |
| 유효한 문자열. | 검증 후 변경. | 본인 소유·업로드 검증 후 교체. |

- `{}`는 400으로 거절한다. 현재와 같은 값을 명시적으로 전달한 유효한 요청은 200으로 처리한다.
- 요청 본문 자체의 null·누락, 배열, 잘못된 JSON, 문자열 필드에 숫자·불리언·객체 전달은 400으로 처리한다. 문자열 자동 변환으로 통과시키지 않는다.
- `id`, `email`, `authProvider`, `providerUserId`, `profileRegisteredAt` 및 알 수 없는 필드는 사용자 요청 DTO에서 거절한다. 전역 JSON 설정을 바꿔 다른 API 계약에 영향을 주지 않는다.
- `UpdateUserProfileRequest`는 각 필드의 값과 전달 여부를 보존한다. DTO 전용 역직렬화에서 키 존재 여부와 JSON 타입을 검사한 뒤 내부 변경 명령으로 전달한다. 일반 `Optional<String>`만으로 세 상태를 표현하지 않는다.
- 현재 코드가 사용하는 Jackson 구성에 맞춰 구현하고 실제 MVC ObjectMapper를 거친 HTTP 테스트로 생략·null·타입 오류를 검증한다. DTO 내부 전달 여부 필드는 공개 요청 스키마에 노출하지 않는다.
- 여러 필드 중 하나라도 검증에 실패하면 전체 요청을 거절한다. 닉네임만 먼저 저장하는 부분 성공은 허용하지 않는다.

## 4. 검증과 연계 기능

### 4.1 닉네임

명세 확인 전의 기본 제안은 필수 문자열, 공백만인 값 금지, 현재 DB에 맞춘 최대 50자다. 표시값과 비교값은 앞뒤 공백 제거 및 Unicode NFC 정규화를 공통으로 수행한 뒤 검사한다. 대소문자는 구분하는 안으로 두며, 내부 공백·특수문자 허용 범위와 최소 길이는 Notion 확인 항목으로 남긴다. 공백 제거로 빈 값이 되면 거절한다. 길이 계산 방식도 요청 검증·저장·중복 확인 API에서 같아야 한다.

닉네임 중복 확인 API의 성공은 저장 예약이 아니다. 등록·수정 트랜잭션에서 다른 등록 사용자의 닉네임 점유를 검사하고 DB 유일 제약으로 최종 보장한다. 자기 자신의 현재 닉네임은 중복으로 거절하지 않는다.

카카오에서 받은 기본 닉네임에는 중복이 있을 수 있으므로 기존 `users.nickname` 전체에 즉시 유일 제약을 걸지 않는다. 제안은 nullable `nickname_key`를 추가하여 등록 사용자만 정규화된 닉네임을 점유하는 것이다. 미등록 사용자의 기본 닉네임은 점유하지 않는다. 이 구분은 별도 닉네임 중복 확인 작업에서도 동일하게 사용해야 한다.

정규화 규칙은 `NicknamePolicy` 한 곳에 두고 등록·수정·중복 확인에서 공유한다. 닉네임 변경 시 표시값과 비교 키를 함께 갱신한다. 유일 제약 충돌은 지정된 제약 이름을 확인해 닉네임 중복 409로 변환하며, 다른 DB 무결성 오류까지 같은 오류로 숨기지 않는다.

### 4.2 프로필 이미지

`ProfileImageStorage`는 키 소유권·형식 검증, 업로드 존재 확인, 조회 URL 생성의 경계를 제공한다. 현재 S3 클라이언트와 설정을 활용하는 어댑터를 두되, 판매글·채팅 이미지 키를 프로필에 재사용하지 않는다.

- 키 공간 제안은 `profiles/{userId}/{UUID}.{extension}`이며 별도 업로드 URL 발급 작업과 동일한 규칙을 공유한다.
- 임의 HTTP URL, 타인의 키, 다른 도메인의 키, 경로 이동 문자열, 허용 형식 밖의 키를 거절한다. 사용자 ID는 principal에서 가져온 값으로 비교한다.
- 키 길이는 기존 이미지 DTO와 같은 최대 512자를 제안한다. 확장자·MIME·크기 제한은 업로드 명세와 맞추고 저장 시 확인 가능한 객체 메타데이터도 검증한다.
- 업로드되지 않은 키는 400으로 거절한다. S3 장애나 권한 오류를 미업로드로 오인하지 않으며, 실패 시 사용자 DB 변경을 롤백한다.
- 이미지가 없거나 PATCH에서 이미지 필드를 생략한 요청은 S3를 호출하지 않는다. null로 삭제할 때도 객체 존재 확인이 필요하지 않다.
- S3 조회는 사용자 행 잠금 전에 끝낸다. 검증된 키를 저장하기 직전에 사용자 존재·등록 상태와 닉네임 제약은 DB에서 다시 확인한다.
- DB에는 object key만 저장하고 공개·CDN URL은 응답 시 생성한다. presigned 업로드 URL은 저장하지 않는다.
- 이미지 교체·연결 해제 시 기존 S3 객체를 즉시 삭제하지 않는다. DB 롤백과 다른 참조를 고려한 미참조 객체 정리는 별도 작업이다.

업로드 URL 발급 API는 연계 작업이지만, #31에서 임의 이미지 키를 검증 없이 저장하는 임시 구현은 두지 않는다. 저장소 어댑터 검증과 실제 업로드→프로필 저장 연동은 별도 검증 결과로 기록한다. 객체 확인과 DB 저장은 하나의 원자적 작업이 아니므로, 검증된 객체를 다른 경로가 즉시 삭제하지 않는 저장소 운영 규칙도 연계 작업에 전달한다.

## 5. 도메인과 DB 변경

### 5.1 User 확장

별도 프로필 테이블 없이 기존 `users`에 추가한다. 현재 범위는 사용자당 닉네임과 이미지 한 개이며 별도 생명주기의 엔티티가 필요하지 않다.

| 추가 컬럼 | 타입 제안 | 용도 |
| --- | --- | --- |
| `profile_image_key` | nullable `varchar(512)`. | 선택한 이미지 object key. |
| `profile_registered_at` | nullable `timestamptz`. | null은 미등록, 값이 있으면 등록 완료. |
| `nickname_key` | nullable `varchar(50)`, 유일 제약. | 등록 사용자가 점유한 정규화 닉네임. |

기존 `nickname`의 NOT NULL, 이메일 유일 제약, `(auth_provider, provider_user_id)` 유일 제약을 유지한다. `profile_registered_at`과 `nickname_key`는 둘 다 null이거나 둘 다 값이 있도록 CHECK 제약을 둔다. 미등록 상태의 이미지 키는 null로 유지한다.

`User.registerProfile(...)`은 미등록 상태에서만 필드와 등록 시각을 함께 설정한다. `User.updateProfile(...)`은 등록 상태에서 전달된 필드만 갱신한다. 무제한 public setter와 엔티티 직접 요청 바인딩은 사용하지 않는다. `Clock`을 주입해 등록 시각을 테스트할 수 있게 한다.

현재 카카오 사용자 생성은 신규 컬럼을 모두 null로 둔다. 기존 로그인·refresh 흐름을 유지하며, 프로필을 등록한 뒤 다시 로그인해도 설정한 닉네임·이미지가 보존되는지 검증한다. 이후 가입 완료 여부를 반환하는 작업은 이 등록 상태를 근거로 사용하되 추가 가입 조건이 있다면 별도 정의한다.

### 5.2 쓰기 트랜잭션과 경합

`UserService`는 입력·이미지 검증과 응답 변환을 조율한다. DB 쓰기는 별도 `UserProfileTransactionService`의 공개 메서드에서 처리해 트랜잭션 경계를 명확히 한다.

1. HTTP DTO에서 형식·필수값·전달 여부를 검증하고 닉네임을 정규화한다.
2. 본인 사용자와 요청의 등록·수정 가능 상태를 사전 조회하고, 새 이미지가 있으면 저장소에서 검증한다.
3. 쓰기 트랜잭션에서 `UserRepository.findByIdForUpdate`로 해당 사용자만 `PESSIMISTIC_WRITE` 잠금 조회한다.
4. 최신 등록 상태를 재검증하고 닉네임 점유를 조회한다. 프로필 필드를 한 번에 변경한 뒤 flush한다.
5. 트랜잭션이 성공한 결과를 응답 DTO로 변환한다. 지정된 닉네임 유일 제약 위반만 트랜잭션 바깥에서 409로 변환한다. 실패한 트랜잭션 안에서 재조회·저장을 계속하지 않는다.

같은 사용자의 동시 POST는 하나만 등록하고 나머지는 409로 종료한다. 동시 PATCH는 잠금 획득 후 읽은 최신 값에 전달된 필드만 적용하므로 서로 다른 필드의 변경을 잃지 않는다. 같은 필드에 대한 유효한 두 수정은 잠금 순서상 마지막 값이 남는 정책이다. 다른 사용자가 같은 닉네임을 선택하는 경합은 DB 유일 제약이 막는다.

외부 S3 호출 동안 DB 잠금을 보유하지 않는다. 현재 범위에서 상품·주문·결제 행을 함께 잠글 필요도 없다. 프로필 변경이 기존 주문의 `sellerNickname` 스냅샷을 갱신하지 않도록 한다.

### 5.3 기존 데이터 적용

기존 수동 SQL 방식에 맞춰 구현 시 `src/main/resources/db/manual/031_user_profile.sql`을 추가한다. #27·#29 SQL은 수정하지 않는다.

- 신규 컬럼을 nullable로 추가하고 기존 사용자는 미등록 상태로 둔다. 카카오 닉네임이 있다는 이유로 가입·프로필 등록 완료 처리하지 않는다.
- 기존 닉네임은 그대로 두고 `nickname_key`를 자동 채우지 않는다. 기존 중복 닉네임이 새 유일 제약 적용을 막지 않게 한다.
- 서비스 외부에서 이미 프로필을 등록한 이력이 있다면 그 근거와 매핑을 확인한 뒤 별도 backfill을 준비한다. 실제 이력 없이 등록 시각을 만들어 넣지 않는다.
- 격리 PostgreSQL에서 기존 사용자·중복 카카오 닉네임 fixture로 SQL 적용과 CHECK·유일 제약을 검증한다. H2 테스트 통과를 실제 마이그레이션 검증으로 대체하지 않는다.
- 스키마를 먼저 적용하고 새 서버를 배포한다. 등록 데이터가 생긴 뒤 구버전 서버로 되돌릴 때도 신규 컬럼·등록 정보를 즉시 삭제하지 않는다.

## 6. 오류 계약

공통 입력 검증 실패는 기존 400 `COMMON_001`과 필드 오류 목록을 사용한다. JSON 구조·타입 오류, 빈 PATCH, 알 수 없거나 변경 불가능한 필드는 400 `COMMON_002`로 처리한다. DTO 전용 파싱 오류도 기존 전역 핸들러가 처리하는 예외로 변환한다.

| 오류 이름 제안 | HTTP 상태 | 의미 |
| --- | --- | --- |
| `USER_PROFILE_ALREADY_REGISTERED` | 409. | 이미 등록된 프로필에 POST 요청. |
| `USER_PROFILE_NOT_REGISTERED` | 409. | 미등록 상태에서 PATCH 요청. |
| `USER_NICKNAME_DUPLICATED` | 409. | 다른 등록 사용자가 같은 닉네임을 점유. |
| `USER_PROFILE_IMAGE_INVALID` | 400. | 잘못된 키·타인 키·허용하지 않는 이미지 형식. |
| `USER_PROFILE_IMAGE_NOT_UPLOADED` | 400. | 형식은 맞지만 저장 객체가 없음. |

현재 `USER_001`은 사용자 없음에 사용 중이다. 신규 코드 번호는 구현 시 연계 작업과 충돌하지 않게 배정하고 Swagger·Notion에 같은 값을 기록한다. S3 등 인프라 장애는 기존 서버 오류 체계를 따르며 내부 버킷·인증 정보나 예외 원문을 사용자 응답에 노출하지 않는다.

## 7. 구현 순서와 파일 구성

아래 Java 경로는 `src/main/java/com/kitschcatch/backend` 아래이며 같은 행에서 반복되는 패키지는 생략했다. SQL 경로는 `src/main/resources` 아래다. 모두 구현 시 추가·변경할 대상으로, 아직 생성된 파일 목록이 아니다.

| 단계 | 작업 | 주요 파일·확인 결과 |
| --- | --- | --- |
| 1. 명세 대조. | Notion 원문과 필드·검증·응답 예시를 대조하고 아래 미확정 사항을 정리한다. | 이 문서의 계약 표와 Notion 출처 링크. |
| 2. 모델·스키마. | User 등록 상태·이미지·닉네임 점유 키, 행 잠금 조회, 수동 SQL을 추가한다. | `domain/user/entity/User.java`, `repository/UserRepository.java`, `db/manual/031_user_profile.sql`. 기존 사용자·DB 제약 검증. |
| 3. 정책·저장소. | 닉네임 정규화·중복 정책, 프로필 이미지 키·객체 검증을 구현한다. | `domain/user/service/NicknamePolicy.java`, `storage/ProfileImageStorage.java`, `storage/S3ProfileImageStorage.java`. 필요한 `S3Properties`·설정·기존 생성자 호출 보완. |
| 4. 서비스. | 조회와 등록·수정 트랜잭션을 구현한다. | `domain/user/service/UserService.java`, `UserProfileTransactionService.java`. 상태 전이·원자성·동시성 검증. |
| 5. HTTP 계약. | DTO, PATCH 전용 파싱, 컨트롤러, 오류 매핑을 연결한다. | `domain/user/dto/UserMeResponse.java`, `RegisterUserProfileRequest.java`, `UpdateUserProfileRequest.java`, `controller/UserController.java`, `global/exception/ErrorCode.java`. 실제 JWT·JSON 요청 검증. |
| 6. 회귀·문서. | 인증·상품·채팅·주문 회귀를 확인하고 API 명세를 동기화한다. | Swagger 경로·필드·null 의미·오류·예시, Notion 명세, 검증 실행 결과. |

새 소스 파일은 프로젝트 지침에 맞는 한 줄 한국어 역할 주석을 둔다. 각 단계는 의미 있는 구현·검증 단위로 커밋하고, 별도 업로드 발급 API나 로그인 응답 확장을 이슈 #31에 임의로 합치지 않는다.

## 8. 검증 계획과 완료 기준

| 검증 영역 | 주요 시나리오 | 기대 결과 |
| --- | --- | --- |
| 내 정보 조회. | 미등록·등록 사용자, DB에 없는 사용자. | 각각 계약에 맞는 200 또는 404. 다른 사용자 데이터 비노출. |
| 최초 등록. | 필수값만 등록, 이미지 포함 등록, 반복 등록, 검증 실패. | 201·등록 시각 생성, 반복 409, 실패 시 등록 시각·닉네임·이미지 모두 보존. |
| 부분 수정. | 닉네임만, 이미지만, 이미지 null, 필드 생략, 같은 값, `{}`. | 생략값 보존, null은 이미지 연결 해제, 닉네임 null·빈 PATCH 거절. |
| 요청 파싱·검증. | 빈 본문·null·배열·잘못된 JSON, 숫자·불리언, 초과 길이·공백, 읽기 전용·알 수 없는 필드. | 400. 요청 DTO 내부 플래그가 API 입력 필드로 노출되지 않음. |
| 인증·권한. | JWT 누락·만료·서명 오류, 사용자 A의 요청에 사용자 B ID 주입. | 401 또는 잘못된 입력 400. B의 정보 조회·변경 없음. |
| 닉네임. | 자기 닉네임 유지, 다른 등록 사용자의 닉네임, 동일한 정규화 결과, 중복 카카오 기본 닉네임. | 일관된 점유·중복 규칙. 저장 시 최종 검증. |
| 이미지. | 본인 키, 타인·판매글·채팅 키, 미업로드, S3 장애, 이미지 생략·삭제. | 잘못된 키는 저장되지 않음. 장애 시 부분 저장 없음. 불필요한 S3 호출 없음. |
| 동시성. | 동일 사용자 동시 등록, 다른 사용자의 동일 닉네임 등록, 서로 다른 필드 동시 수정. | 등록 1회, 닉네임 점유 1명, 생략한 필드 변경 유실 없음. PostgreSQL에서 확인. |
| 인증 회귀. | 기존 사용자 로그인, 신규 카카오 로그인, 등록 후 재로그인·refresh. | 중복 사용자 없음, 미등록 기본 상태 유지, 사용자 지정 프로필 보존. |
| 기존 도메인 회귀. | 닉네임 변경 뒤 게시글·채팅 조회와 기존 주문 조회. | 기존 응답 동작 유지, 주문 생성 당시 판매자 닉네임 스냅샷 유지. |
| DB 적용. | 기존 사용자 포함 SQL 적용, 제약 위반 fixture. | 기존 로그인 데이터 보존, 제약 위반 저장 차단, 실패한 적용의 롤백 확인. |
| 명세. | Swagger 생성 결과와 Notion 원문 비교. | 필수·선택, nullable, 상태 코드, 예시, PATCH 의미가 일치. |

도메인·서비스 테스트는 상태와 DB 저장 결과를 확인한다. 컨트롤러 단위 테스트에 더해 실제 SecurityFilterChain과 애플리케이션 JSON 구성을 사용하는 HTTP 통합 테스트를 둔다. 동시성 테스트는 서로 다른 트랜잭션을 동시에 실행하고 최종 DB 상태를 검사한다.

구현 완료 시 기본 확인 명령은 `./gradlew test`와 `./gradlew build`다. 격리 PostgreSQL의 마이그레이션·경합 테스트, 실제 S3 업로드 연동, Swagger·Notion 비교는 별도 결과로 기록한다. 현재 문서만 작성한 단계에서는 이 테스트들이 통과한 것으로 표시하지 않는다.

완료 기준은 이슈의 세 API가 확정 명세대로 동작하고, 본인만 접근하며, 미등록·중복 등록·PATCH null·잘못된 입력·동시 요청을 일관되게 처리하는 것이다. 별도 연계 API가 미완성이라면 저장 경계의 검증과 남은 실제 연동 검증을 구분해 기록한다.

## 9. 구현 전에 확정할 사항

| 확인 항목 | 현재 제안 | 영향을 받는 부분 |
| --- | --- | --- |
| Notion 원문과 전체 필드. | 기존 정보 + 닉네임·선택 이미지. 원문 링크 미확인. | 요청·응답 DTO, 추가 필수 필드, DB 컬럼. |
| 닉네임 길이·문자·정규화. | 공백만 금지, 최대 50자, 앞뒤 공백 제거·NFC, 대소문자 구분. | 검증, 비교 키, 중복 확인 API와 기존 데이터 정책. |
| 미등록 닉네임 점유. | 카카오 기본 닉네임은 점유하지 않고 등록 사용자만 점유. | nullable 유일 키와 신규 사용자 생성 호환성. |
| 이미지 선택 여부·검증 규격. | 선택 입력, null로 연결 해제, 프로필 전용 키와 업로드 객체 검증. | 저장소·업로드 URL 발급 작업 간 계약. |
| 응답 필드·최초 등록 상태 코드. | 201, 내 정보 공통 응답, 선택값 명시적 null, `profileRegisteredAt` 제공. | 클라이언트와 Swagger·Notion 예시. |
| 가입 완료 여부 연계. | 프로필 등록 상태만 저장·반환하고 로그인 응답 확장은 별도 작업. | 이후 가입 완료 판정과 API 간 필드 일관성. |

위 항목은 설계의 가정이며 확인되지 않은 제품 요구사항을 대신하지 않는다. Notion 원문 확보 후 차이를 이 문서에 반영하고 구현 계약을 확정한다.

## 10. 이번 문서 작성에서 확인한 내용

- [x] GitHub 이슈 #31 본문과 댓글 유무 확인. 댓글은 없다.
- [x] `git fetch origin` 후 `develop`을 `origin/develop`까지 fast-forward.
- [x] 기준 커밋 `d623c00`에서 `feat/31` 생성.
- [x] 사용자·카카오 인증·보안·응답·이미지 저장소·주문 스냅샷 코드 확인.
- [x] 기존 추적되지 않은 `.DS_Store` 보존.
- [ ] Notion API 명세 원문 대조.
- [ ] API 구현, 테스트 실행, DB 적용, 실제 이미지 업로드 연동 확인.

마지막 두 항목은 후속 구현에서 수행할 작업이며, 이번 설계 문서 작성 결과에 포함된 검증이 아니다.

## 11. 구현 진행 기록

- [x] `User`에 프로필 이미지 키·등록 시각·등록 닉네임 키를 추가하고 JPA 매핑을 확인했다.
- [x] 본인 사용자 행 잠금 조회와 등록 닉네임 중복 조회를 추가했다.
- [x] `GET /api/users/me`, `POST /api/users/me/profile`, `PATCH /api/users/me`와 공통 응답을 구현했다.
- [x] PATCH 요청에서 필드 생략·명시적 null·알 수 없는 필드를 구분하도록 구현했다.
- [x] 프로필 이미지 전용 S3 키 소유권·객체 존재·URL 변환 경계를 추가했다.
- [x] 수동 스키마 변경안 `src/main/resources/db/manual/031_user_profile.sql`을 추가했다.
- [x] 서비스·컨트롤러·실제 인증 필터 경로 테스트를 추가했다.
- [x] `./gradlew test` 통과를 확인했다.
- [ ] 실제 Notion API 명세와 필드·닉네임 정책을 대조한다.
- [ ] 실제 S3 업로드 URL 발급 API와 연동한다.
- [ ] 격리 PostgreSQL에서 수동 SQL과 동시 닉네임 등록을 검증한다.
