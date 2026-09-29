# 인증 응답의 프로필 등록 여부

카카오 모바일 로그인과 토큰 재발급 성공 응답의 `data.user.profileRegistered`는 프로필 등록 완료 여부를 나타내는 boolean이다. 신규 사용자 또는 아직 프로필을 등록하지 않은 사용자는 `false`, 최초 프로필 등록을 마친 사용자는 `true`다.

## API 계약

| 메서드와 경로 | 용도 |
| --- | --- |
| `POST /api/auth/kakao/mobile-login`. | 카카오 ID 토큰과 nonce를 검증하고 토큰 및 현재 사용자 정보를 반환한다. |
| `POST /api/auth/token/refresh`. | 유효한 refresh token을 회전하고 새 토큰 및 현재 사용자 정보를 반환한다. |

두 API는 동일한 `AuthTokenResponse`를 사용하며, 성공 시 HTTP 200과 아래 구조를 반환한다. 토큰 문자열과 만료 시간은 예시다.

```json
{
  "success": true,
  "data": {
    "tokenType": "Bearer",
    "accessToken": "<access-token>",
    "expiresIn": 1800,
    "refreshToken": "<refresh-token>",
    "refreshTokenExpiresIn": 1209600,
    "user": {
      "id": 1,
      "email": "user@example.com",
      "nickname": "카카오기본닉네임",
      "profileRegistered": false
    }
  }
}
```

`profileRegistered`는 `User.profileRegisteredAt`에 값이 있으면 `true`, null이면 `false`로 계산한다. 카카오 기본 닉네임과 프로필 이미지의 존재 여부는 판정 기준이 아니다. 전체 회원가입·약관 동의 완료 여부를 표현하는 필드도 아니다. 기존 `id`, `email`, `nickname`, 토큰 필드, 토큰 회전·오류 응답은 유지된다.

프로필 등록이 완료되면 등록 **전에 발급한 유효한 refresh token**으로 재발급해도 새 응답의 값은 `true`다. 토큰 자체에 등록 상태를 저장하지 않고 재발급 요청에서 조회한 사용자 상태를 사용한다. 프로필 수정 또는 이미지 연결 해제 후에도 최초 등록 시각이 유지되므로 `true`가 유지된다. 이미 반환한 이전 응답의 `false`는 자동으로 바뀌지 않는다.

## 클라이언트 사용 예시

로그인 또는 토큰 재발급이 성공하면 `data.user.profileRegistered`로 초기 화면을 선택한다. 프로필 등록 API가 성공한 직후에는 등록 완료 상태로 로컬 화면을 갱신한다. 새 인증 응답이 필요하면 현재 유효한 refresh token으로 재발급한다. 이미지 URL과 등록 시각 등 전체 프로필이 필요하면 `GET /api/users/me`를 호출한다.

```text
if (response.data.user.profileRegistered) {
    일반 화면으로 이동
} else {
    프로필 등록 화면으로 이동
}
```

## 검증 결과

2026-09-29 `feat/37`에서 다음을 확인했다.

- 실제 HTTP 서버와 JWT·H2로 신규 로그인 `false`, 등록 전 재발급 `false`, 프로필 등록 후 이전에 발급한 유효한 refresh token의 재발급 `true`, 재로그인 `true`를 확인했다.
- 닉네임 변경, 이미지 교체, 이미지 연결 해제 후에도 등록 시각과 `true`가 유지됐다. 실패한 등록은 `false`, 실패한 수정은 `true`를 유지했다. 이미지 저장소는 테스트 대역을 사용했다.
- 사용한 refresh token은 재사용할 수 없고, `false`도 응답 JSON에 boolean으로 포함된다. 실제 `/v3/api-docs`에서 두 인증 경로의 공통 응답과 `AuthUserResponse.profileRegistered`의 boolean 스키마를 확인했다.
- 임시 격리 PostgreSQL에 기존 수동 SQL을 적용하는 프로필 회귀 테스트를 포함해 `./gradlew test build --rerun-tasks --console=plain`을 실행했다. JUnit XML 합계는 286개 통과, 실패 0개, 오류 0개, 건너뜀 0개이며 빌드에 성공했다. `issue31_` 테스트 스키마 잔여는 0개였고 임시 서버를 중지했다.

Notion [카카오 모바일 로그인](https://app.notion.com/p/3a8ee6172f5680b78580e573491fece5)과 [토큰 재발급](https://app.notion.com/p/3a8ee6172f56807d8920e58a500487ab) 항목의 설명에 `profileRegistered`를 반영하고, 두 성공 응답의 HTTP 200 예시와 등록 상태별 동작을 추가했다. 두 페이지를 다시 열어 저장된 내용을 확인했다. 이 기록은 로컬 테스트와 명세 동기화 결과이며 실제 카카오·AWS S3·CI·배포 검증 결과는 아니다.
