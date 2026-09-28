# 프로필 이미지 업로드 API

이슈 #33의 구현 계약과 2026-09-27 검증 결과다. [Notion 명세](https://app.notion.com/p/URL-3deee6172f568077ad81eefead3a59dc)의 경로·필드·정책을 반영했다. 실제 AWS S3 검증은 사용자 요청에 따라 후속 작업으로 남겼다.

## 요청

```http
POST /api/users/me/profile/image/presigned-url
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "fileName": "profile.jpg",
  "contentType": "image/jpeg",
  "fileSize": 102400
}
```

DB에 존재하는 인증 사용자라면 프로필 등록 전후 모두 발급할 수 있다. 발급은 프로필을 등록하거나 변경하지 않는다.

| 필드 | 규칙 |
| --- | --- |
| `fileName`. | 필수 문자열, 최대 255자, 경로 구분자·제어문자 금지, MIME과 확장자 일치. 원본 이름은 객체 키에 저장하지 않는다. |
| `contentType`. | 필수 문자열, 최대 100자, 앞뒤 공백·대소문자 정규화. `image/jpeg`, `image/png`, `image/webp`만 허용. |
| `fileSize`. | 필수 JSON 정수, 1~5,000,000바이트. 최대값은 서버 설정에 따른다. 문자열 숫자·실수·불리언으로 대체할 수 없다. |

`userId`·버킷·키·TTL 등 알려지지 않은 요청 필드는 거절한다. `.jpeg` 입력도 허용하며 신규 JPEG 객체 키의 확장자는 `.jpg`다. GIF는 신규 발급·연결 대상이 아니다.

## 응답

HTTP 200으로 반환한다. 아래 URL은 형식 설명용이며 유효한 서명이 아니다.

```json
{
  "success": true,
  "data": {
    "uploadUrl": "https://s3.example.com/presigned-upload-url",
    "imageKey": "profiles/123/550e8400-e29b-41d4-a716-446655440000.jpg",
    "imageUrl": "https://cdn.example.com/profiles/123/550e8400-e29b-41d4-a716-446655440000.jpg",
    "expiresAt": "2026-09-27T03:10:00Z",
    "expiresIn": 600,
    "uploadHeaders": {
      "content-type": "image/jpeg",
      "if-none-match": "*"
    }
  }
}
```

- `uploadUrl`: 파일 바이트를 PUT하는 URL. 저장하거나 로그에 남길 프로필 조회 URL이 아니다.
- `imageKey`: 업로드 성공 후 기존 프로필 API의 `profileImageKey`로 전달한다.
- `imageUrl`: 업로드 후 조회할 URL. 공개 또는 CDN 접근 설정은 별도로 필요하다.
- `expiresAt`: SDK 서명의 만료 시각. 임시 AWS 자격 증명이 먼저 만료되면 이보다 일찍 사용할 수 없게 된다.
- `expiresIn`: 발급 시점 기준 유효 초. 기본 600초다.
- `uploadHeaders`: SDK 서명 결과의 필수 헤더. HTTP 클라이언트가 생성하는 Host·Content-Length는 제외한다.

## 클라이언트 순서

1. 파일의 이름·MIME·실제 바이트 수로 URL을 발급한다.
2. `uploadUrl`에 `uploadHeaders`를 그대로 넣고 파일 원본 바이트를 PUT한다. FormData로 감싸거나 백엔드 JWT를 보내지 않는다.
3. PUT 성공 후 `imageKey`로 `POST /api/users/me/profile`을 호출하거나 `PATCH /api/users/me`에서 이미지를 교체한다.
4. 새 이미지로 교체할 때마다 새 키를 발급한다. 같은 객체를 덮어쓰지 않는다.

웹 클라이언트 예시는 다음과 같다. `issued`는 발급 API 응답의 `data`, `file`은 발급 요청에 사용한 동일한 File이다. 실제 브라우저·S3 CORS 검증은 후속 작업이다.

```javascript
// 발급받은 조건 그대로 프로필 이미지 파일을 업로드한다.
const upload = await fetch(issued.uploadUrl, {
  method: "PUT",
  headers: issued.uploadHeaders,
  body: file,
});
if (!upload.ok) throw new Error("프로필 이미지 업로드 실패");
// 이후 프로필 API에 { profileImageKey: issued.imageKey }를 전달한다.
```

Content-Length는 `fileSize`와 같은 바이트 수여야 한다. 크기 변경·변환·압축이 필요하면 변환 완료 파일을 기준으로 다시 발급한다. 조건 헤더 `If-None-Match: *`도 서명에 포함된다. 이는 기존 객체 덮어쓰기 방지 조건이며 삭제 후 재생성까지 막는 일회용 토큰은 아니다.

기본 이미지로 돌아갈 때는 PATCH에 `profileImageKey: null`을 전달한다. 이미지 필드를 생략하면 기존 이미지를 유지한다. 이 두 경우에는 S3 HEAD를 호출하지 않는다.

## 프로필 연결 검증과 오류

키 소유권 검사 후 S3 HEAD 한 번으로 객체 존재, 실제 Content-Length, MIME과 확장자 일치를 확인한다. 이 검사는 객체 메타데이터를 대상으로 하며 이미지 디코딩이나 파일 내용 판별은 하지 않는다. 검증은 쓰기 트랜잭션 전에 수행하고 실패하면 닉네임·이미지·등록 시각을 변경하지 않는다.

기존 `profiles/{userId}/image.png` 같은 키도 동일 정책을 통과하면 연결할 수 있다. 경로 이동·중첩 경로·다른 도메인 키·임의 URL은 거절한다. 이미 저장된 GIF는 조회할 수 있고, 이미지 필드를 생략한 닉네임 수정도 가능하지만 GIF 키를 새로 연결할 수는 없다.

| HTTP·코드 | 상황 |
| --- | --- |
| 401 `AUTH_004`. | 토큰 누락·오류·만료 또는 refresh 토큰 사용. |
| 400 `COMMON_001`. | 발급 요청 필수값·길이·파일명·형식·크기 위반. |
| 400 `COMMON_002`. | 잘못된 JSON 타입·알 수 없는 필드·본문 파싱 실패. |
| 404 `USER_001`. | DB에 사용자가 존재하지 않음. |
| 403 `USER_007`. | 프로필 저장 시 유효한 형식의 타인 프로필 이미지 키. |
| 400 `USER_005`. | 프로필 저장 시 잘못된 키·MIME·확장자·실제 크기. |
| 400 `USER_006`. | HEAD 404 또는 NoSuchKey인 미업로드 객체. |
| 500 `S3_001`. | S3 버킷 미설정. |
| 500 `COMMON_999`. | AWS 인증·권한·통신 장애 등 서버 오류. |

S3 HEAD 403을 미업로드로 바꾸지 않는다. 없는 객체가 404로 구분되는지는 실제 버킷 권한도 확인해야 한다. 기존 등록 중복·미등록 수정·닉네임 중복의 USER_002~004 계약은 유지한다.

## 설정

| 환경 변수 | 기본값·용도 |
| --- | --- |
| `AWS_PROFILE`. | 로컬 실행 시 사용할 AWS 프로필. 코드에 자격 증명을 저장하지 않는다. |
| `APP_S3_REGION`. | `ap-northeast-2`. |
| `APP_S3_BUCKET`. | 기본 빈 값. 실제 발급·HEAD 사용 시 필요하다. |
| `APP_S3_PUBLIC_BASE_URL`. | 선택. 미설정 시 리전별 S3 객체 URL을 반환한다. |
| `APP_PROFILE_IMAGE_MAX_SIZE_BYTES`. | `5000000`. 양수여야 한다. |
| `APP_PROFILE_IMAGE_UPLOAD_URL_TTL`. | `10m`. 1초~7일의 정수 초여야 한다. |

판매글·채팅이 사용하는 `APP_S3_UPLOAD_URL_TTL` 기본 5분은 그대로다. 웹 사용 시 PUT과 반환 서명 헤더를 허용하는 S3 CORS 및 실제 조회 경로도 구성해야 한다.

## 실행한 검증

PostgreSQL 14.18을 임시 디렉터리의 루프백 포트 55433에서 실행했다. 기존 `ISSUE31_TEST_DB_*` 환경 변수를 재사용했고, `issue33_profile_verification` DB 안에서 테스트별 UUID 스키마를 만들었다.

```sh
ISSUE31_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55433/issue33_profile_verification \
ISSUE31_TEST_DB_USERNAME="$USER" \
ISSUE31_TEST_DB_PASSWORD= \
./gradlew test build --console=plain
```

위 명령 전 별도 DB가 실행 중이어야 한다. 임시 클러스터 생성·종료는 [기존 재현 절차](user-profile-verification.md#재현-방법)의 포트·DB 이름을 바꿔 사용한다. DB 환경 변수가 없으면 PostgreSQL 검증은 건너뛴다.

전체 **270건 통과, 실패·오류·건너뜀 0건**, 빌드 성공을 확인했다. PostgreSQL 검증은 수동 SQL 3건과 HTTP 14건으로 총 17건이다. 테스트 종료 후 남은 전용 스키마는 0개였고 임시 서버를 종료했다.

| 영역 | 확인 결과 |
| --- | --- |
| 정책 30건. | 허용 형식·정규화·최대값·초과·0바이트·키 경계값·잘못된 설정. |
| SDK 저장소 9건. | 가짜 자격 증명을 쓰는 실제 SDK 서명, 크기·MIME·조건 헤더·600초 TTL, 키 고유성·URL 인코딩, HEAD 404·403·5xx·통신 실패 분기. |
| 발급·연결 HTTP 34건. | 실제 서버·JWT·JSON·Swagger, 등록 전후 발급, DB 불변, 등록·조회·교체·생략·해제, 잘못된 메타데이터 원자성, 타인 키 403. S3Client만 테스트 대역이다. |
| PostgreSQL 17건. | 수동 SQL, 닉네임 경합 5회, 같은 사용자 동시 등록, 필드별 동시 수정, S3 지연 중 행 잠금, 권한·메타데이터 오류 원자성. ProfileImageStorage는 테스트 대역이다. |
| 전체 회귀. | 기존 인증·판매글·채팅·주문·결제 테스트를 포함해 통과. |

## 남은 실제 연동 검증

2026-09-27 `dev-kc` STS 재확인에서 `InvalidClientTokenId`가 반환됐다. 이후 사용자가 AWS S3는 다음에 진행하도록 지시해 실제 S3 객체 쓰기는 수행하지 않았다. 다음 항목은 완료로 간주하지 않는다.

- 유효한 `dev-kc`, 테스트 버킷·리전·권한, CDN 또는 조회 경로 확인.
- 실제 발급 API → S3 PUT → 프로필 등록·GET·교체·null 해제.
- 미업로드·타인 키·잘못된 메타데이터, 만료·조건 헤더 누락·변조·재PUT 차단.
- 브라우저 CORS와 실제 이미지 조회.
- 테스트가 만든 키만 기록하고 성공·실패 시 정리.

Notion에 정책·응답 예시·업로드 방법·메타데이터 검증·오류 목록을 모두 반영했다. 사용자 승인 후 남은 설명을 갱신하고 페이지를 새로고침해 저장된 내용을 확인했다. 실제 AWS S3 연동 검증은 위 후속 작업으로 남아 있다.
