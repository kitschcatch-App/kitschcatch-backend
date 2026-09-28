# 닉네임 중복 확인 API

- 관련 이슈: [#35 닉네임 중복 확인 API 구현](https://github.com/kitschcatch-App/kitschcatch-backend/issues/35).
- Notion 명세: [닉네임 중복 확인](https://app.notion.com/p/3deee6172f5680bc9368e3974414e83b).
- 구현 계획: [2026-09-28 닉네임 중복 확인 API 구현 계획](plans/2026-09-28-nickname-availability.md).
- 확인일: 2026-09-29.

## 요청과 응답

프로필 등록 전이나 등록 후 닉네임 수정 전에 access token으로 조회한다. `nickname`은 URL 인코딩된 필수 쿼리이며 사용자 ID는 받지 않는다.

```http
GET /api/users/nickname-availability?nickname=%ED%82%A4%EC%B9%98%EC%BA%90%EC%B2%98
Authorization: Bearer <accessToken>
```

사용 가능한 닉네임은 HTTP 200으로 반환한다.

```json
{
  "success": true,
  "data": {
    "nickname": "키치캐처",
    "available": true
  }
}
```

다른 등록 사용자가 점유한 닉네임도 HTTP 200이며 `data.available`이 `false`다. `data.nickname`은 실제 판정에 사용한 정규화 결과다. 성공 응답의 `error`, 오류 응답의 `data`처럼 null인 최상위 필드는 기존 `ApiResponse` 직렬화 규칙에 따라 생략된다.

## 판정 기준

- 기존 `NicknamePolicy`와 동일하게 Java `trim()`으로 앞뒤 공백을 제거한 후 Unicode NFC로 정규화한다.
- 정규화 후 비어 있거나 `String.length()` 기준 50자를 초과하면 거절한다. 대소문자는 구분하고 내부 공백은 유지한다.
- 다른 사용자의 `nicknameKey`가 정규화한 닉네임과 같으면 `available: false`다. JWT의 사용자 ID를 제외 기준으로 쓰므로 본인의 현재 닉네임은 `true`다.
- 미등록 사용자의 카카오 기본 닉네임은 `nicknameKey`가 null이라 점유한 닉네임으로 계산하지 않는다.
- 조회는 사용자 상태를 변경하거나 행 잠금·S3 호출을 하지 않는다.

프로필 등록·수정 요청의 원문 길이 제한은 제거했고, 닉네임 길이는 정규화 후 공통 정책에서 검증한다. 따라서 분해형 한글이나 앞뒤 공백 때문에 원문이 50자를 넘더라도 정규화 결과가 유효하면 조회와 저장에 동일하게 사용할 수 있다.

## 오류

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 필수 `nickname` 쿼리 누락. | 400. | `COMMON_002`. |
| 빈 값·공백만 있는 값·정규화 후 50자 초과. | 400. | `COMMON_001`. |
| access token 누락·무효·만료·refresh token 사용. | 401. | `AUTH_004`. |
| 토큰의 사용자 ID에 해당하는 DB 사용자가 없음. | 404. | `USER_001`. |
| 예상하지 못한 서버 오류. | 500. | `COMMON_999`. |

중복 조회는 오류가 아니다. 저장 시점에 다른 사용자가 먼저 등록하거나 수정하면 기존 프로필 저장 API가 409 `USER_004`를 반환한다. 이때 실패한 등록·수정은 이전 프로필 상태를 보존한다.

## 클라이언트 사용 순서

1. 입력한 닉네임으로 확인 API를 호출한다. 한글·공백·`+`는 쿼리 값으로 URL 인코딩한다.
2. 응답의 `data.nickname`과 현재 입력의 정규화 결과가 일치하는지 확인한다. 입력이 바뀌었거나 더 늦게 도착한 과거 요청 결과는 사용하지 않는다.
3. `available: true`일 때 등록·수정 버튼을 활성화할 수 있다. 사용 가능 응답은 예약이나 저장 성공 보장이 아니므로 저장 API의 409 `USER_004`도 처리한다.
4. 프로필 저장에 성공한 뒤 필요한 경우 닉네임 확인 결과를 다시 조회한다.

## 검증 기록

실제 JWT HTTP 요청으로 미등록·등록 사용자, 본인 닉네임, 타인 점유, 정규화·길이 경계, 대소문자, 쿼리 인코딩, 임의 `userId` 쿼리 무시, 인증·입력 오류와 OpenAPI 문서를 검증했다. 등록·수정의 정규화 후 길이 검증도 실제 HTTP로 확인했다.

격리 PostgreSQL에서 기존 `031_user_profile.sql`을 적용한 뒤 사전 확인 이후의 등록·수정 선점, 같은 닉네임의 동시 등록·수정, 409 처리와 실패 원자성을 검증했다. `ISSUE31_TEST_DB_URL` 등 [기존 재현 방법](user-profile-verification.md#재현-방법)의 환경 변수를 사용한다. 최종 전체 테스트 수와 임시 스키마 정리 결과는 [구현 계획의 완료 기록](plans/2026-09-28-nickname-availability.md#7-진행-상태)에 적는다.

테스트 DB는 로컬 격리 인스턴스다. 운영 DB·실제 AWS S3 연동 검증이나 CI·배포 성공을 의미하지 않는다.
