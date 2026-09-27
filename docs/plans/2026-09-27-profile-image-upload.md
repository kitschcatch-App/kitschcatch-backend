# 프로필 이미지 업로드 URL 발급 및 S3 연동 구현 계획

- 작성일: 2026-09-27.
- 이슈: [#33 프로필 이미지 업로드 URL 발급 및 S3 연동 검증](https://github.com/kitschcatch-App/kitschcatch-backend/issues/33).
- 선행 작업: [#31](https://github.com/kitschcatch-App/kitschcatch-backend/issues/31), [병합 PR #32](https://github.com/kitschcatch-App/kitschcatch-backend/pull/32).
- 작업 브랜치: `feat/33`.
- 기준 코드: 최신화한 `develop`의 `2cc8320`.
- 상태: 구현 전 계획 문서. API 구현·테스트·실제 S3 검증 결과가 아니다.
- 명세 상태: GitHub 이슈와 현재 코드를 확인했다. Notion 원문은 이번 문서 작성에서 확보하지 못했으므로 아래 API 경로·수치·필드는 제안이다. 구현 착수 시 원문과 클라이언트 계약을 대조한다.

## 1. 목표와 범위

인증된 사용자가 서버에서 업로드 URL을 발급받아 S3에 이미지를 직접 올리고, 반환된 이미지 키로 기존 프로필 API를 사용할 수 있게 한다. 최초 프로필 등록 전과 등록 후 모두 발급할 수 있다.

이번 작업에 포함할 내용은 다음과 같다.

- 프로필 이미지 한 장의 presigned PUT URL 발급 API.
- 인증 사용자에 속하는 객체 키 생성과 이미지 형식·크기·만료 정책.
- 업로드 후 실제 객체 메타데이터 검증과 기존 프로필 등록·수정 연동.
- 실제 JWT HTTP 요청과 S3 업로드를 잇는 통합 검증, 기존 프로필 동시성 회귀 검증.
- Swagger·Notion 계약 동기화와 재현 가능한 검증 기록.

닉네임 중복 확인 API, 로그인 응답 확장, 이미지 리사이즈·변환, 이미지 교체 시 기존 객체 즉시 삭제, 미참조 객체 자동 정리는 별도 작업이다. 이번 API는 프로필 DB 상태를 변경하지 않으며 새로운 테이블이나 마이그레이션을 추가할 필요가 없는 설계로 시작한다.

## 2. 현재 코드와 연결 지점

아래 Java 경로는 `src/main/java/com/kitschcatch/backend` 기준이다.

| 코드 | 현재 동작 | 구현 방향 |
| --- | --- | --- |
| `domain/user/controller/UserController.java` | 내 정보 조회·프로필 최초 등록·수정 API가 있다. | 동일 인증 경로 아래 단일 이미지 업로드 URL 발급을 추가한다. |
| `domain/user/service/ProfileImageStorage.java` | 키 소유권 확인·존재 확인·조회 URL 생성만 제공한다. | 업로드 URL 생성과 업로드 객체 메타데이터 조회 계약을 추가한다. |
| `domain/user/storage/S3ProfileImageStorage.java` | `profiles/{userId}/`와 확장자를 검사하고 S3 HEAD로 존재를 확인한다. | 발급 정책을 공유하고 HEAD 응답의 MIME·실제 크기를 검증할 수 있게 한다. |
| `domain/user/service/UserService.java` | 이미지 검증을 마친 뒤 쓰기 트랜잭션을 호출한다. | 존재 확인을 메타데이터 검증으로 확장하고 S3 호출 위치는 유지한다. |
| `domain/user/service/UserProfileTransactionService.java` | 사용자 행 잠금, 최신 상태 확인, 저장·flush를 담당한다. | S3 호출을 넣지 않고 등록·수정의 원자성과 경합 처리를 보존한다. |
| `domain/post/storage/S3StorageConfig.java`, `S3Properties.java` | AWS SDK 2.44.4의 S3Client·S3Presigner와 버킷·리전·공개 URL·TTL을 공유한다. | 기존 빈과 공통 설정을 재사용하고 프로필 전용 정책만 별도로 설정한다. |
| `domain/post/storage/S3PostImageStorage.java`, `domain/chat/storage/S3ChatImageStorage.java` | 도메인별 키와 presigned PUT URL을 발급한다. | 발급 구조를 참고하되 프로필 정책 변경을 판매글·채팅에 전파하지 않는다. |
| `global/security/SecurityConfig.java` | `/api/users/**`에는 JWT 인증이 필요하다. | 인증 예외 경로를 추가하지 않는다. |

기존 S3 프로필 검증은 실제 바이트 크기나 MIME을 확인하지 않는다. 또한 키 검증이 UUID를 강제하지 않으므로, 새 발급 규칙과 기존에 저장된 키의 호환성을 구분해야 한다.

## 3. API 계약 제안

### 3.1 요청

`POST /api/users/me/profile-image/presigned-url`을 제안한다. 인증 헤더는 기존과 같은 `Authorization: Bearer <accessToken>`이다. 본인 ID는 principal에서 가져오며, DB에 사용자가 있는지 확인한다. `profileRegisteredAt`이 null인 사용자의 요청도 허용한다.

```json
{
  "originalFileName": "profile.png",
  "contentType": "image/png",
  "contentLength": 102400
}
```

| 필드 | 제안 |
| --- | --- |
| `originalFileName` | 필수 문자열, 공백만 금지, 최대 255자. 확장자 일치 확인에만 사용하고 객체 키에 원본 이름을 넣지 않는다. |
| `contentType` | 필수 문자열, 최대 100자. 앞뒤 공백·대소문자를 정규화하고 정해진 MIME만 허용한다. |
| `contentLength` | 필수 정수, 실제 전송할 파일의 바이트 수. 1 이상·설정한 최대 크기 이하로 제한한다. |

`userId`, 버킷, 객체 키, 조회 URL, TTL을 요청에서 받지 않는다. 알 수 없는 필드와 잘못된 JSON 타입은 400으로 처리한다. 실제 애플리케이션 JSON 설정에서 숫자→문자열 등 묵시적 변환을 확인하고 이 DTO의 엄격한 파싱을 보장한다.

### 3.2 응답과 클라이언트 흐름

기존 판매글 발급 API와 같은 HTTP 200 및 `ApiResponse.success`를 사용한다. URL 발급은 사용자 프로필 생성이 아니므로 201로 응답하지 않는다.

```json
{
  "success": true,
  "data": {
    "uploadUrl": "https://example-bucket.s3.ap-northeast-2.amazonaws.com/profiles/42/550e8400-e29b-41d4-a716-446655440000.png?example-signature",
    "imageKey": "profiles/42/550e8400-e29b-41d4-a716-446655440000.png",
    "imageUrl": "https://cdn.example.com/profiles/42/550e8400-e29b-41d4-a716-446655440000.png",
    "expiresIn": 300,
    "uploadHeaders": {
      "Content-Type": "image/png",
      "If-None-Match": "*"
    }
  }
}
```

위 URL은 형식 설명용이며 유효한 서명이 아니다. `expiresIn`은 발급 시점 기준 초 단위다. 서명에 필요한 헤더는 SDK의 실제 서명 결과에 맞춰 반환하고, 클라이언트가 자동 처리하는 Host 등과 구분한다.

클라이언트는 응답의 URL·메서드·필수 헤더를 그대로 사용해 파일 바이트를 PUT한다. S3 업로드에는 백엔드 JWT를 보내지 않는다. PUT 성공 이후 `imageKey`를 기존 `POST /api/users/me/profile` 또는 `PATCH /api/users/me`의 `profileImageKey`로 전달한다. URL 발급이나 PUT 성공만으로 DB 프로필이 등록·변경되지는 않는다.

새로운 이미지로 교체할 때마다 새 URL·새 키를 발급한다. 이미지 필드 생략은 기존 값 보존, 명시적 null은 연결 해제라는 기존 PATCH 계약을 유지한다. 조회 URL은 PUT 성공 전에는 객체를 제공하지 않는다.

## 4. 이미지 정책과 S3 검증

### 4.1 발급 정책

정책은 `ProfileImagePolicy`와 프로필 전용 설정 한 곳에서 공유한다. 아래 값은 Notion 확인 전 기본 제안이다.

| 항목 | 제안과 이유 |
| --- | --- |
| MIME | 기존 저장소와 맞춰 `image/jpeg`, `image/png`, `image/webp`, `image/gif`를 허용한다. GIF 허용 여부는 원문과 대조한다. |
| 확장자 | MIME에 따라 `.jpg`, `.png`, `.webp`, `.gif`를 서버가 결정한다. 입력 `.jpeg`는 JPEG로 인정하며 MIME과 확장자가 충돌하면 거절한다. |
| 크기 | 최대 5 MiB, 즉 5,242,880바이트를 제안한다. 제품 확정값은 아니며 `app.profile-image.max-size-bytes`로 설정한다. |
| URL 만료 | 기존 `app.s3.upload-url-ttl`의 기본 5분을 재사용한다. 유효한 양수 범위를 설정 검증하며 도메인별 TTL이 필요해지면 프로필 설정으로 분리한다. |
| 객체 키 | 서버가 생성한 `profiles/{인증 userId}/{UUID}.{extension}`. 프로필 전용 prefix를 발급·검증에서 공유한다. |

파일명에 경로 구분자·제어문자가 포함되거나 확장자가 지원되지 않으면 입력을 거절한다. 정규화한 MIME과 서버가 고른 확장자를 일치시켜 원본 이름으로 저장 형식을 바꿀 수 없게 한다.

새 발급 키는 UUID로 생성한다. 반면 이미 저장된 `profiles/{userId}/image.png` 같은 키를 UUID가 아니라는 이유만으로 갑자기 사용할 수 없게 하지 않는다. 기존 키 사용 현황을 확인하고, 호환 검증에서도 정확한 사용자 prefix·단일 파일명·허용 확장자·최대 512자·경로 이동 금지를 적용한다. 타인 prefix, `posts/...`, `chats/...`, 임의 HTTP URL은 거절한다.

### 4.2 업로드와 연결 시점의 검증

1. 발급 시 선언된 MIME·파일 크기를 검사하고 Content-Type을 서명에 포함한다. 실제 전송값이 서명과 일치해야 한다. [AWS 업로드 문서](https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html).
2. `PutObjectRequest`에 선언 크기를 반영할 때 해당 SDK의 실제 서명 헤더와 대상 클라이언트의 Content-Length 처리를 확인한다. 선언 크기 검증만으로 실제 업로드 크기가 제한됐다고 간주하지 않는다.
3. 프로필 등록·수정에서는 HEAD 한 번으로 객체 존재, 실제 `contentLength`, 정규화한 `contentType`과 키 확장자 일치를 검사한다. 0바이트·최대 크기 초과·잘못된 메타데이터 객체는 DB에 연결하지 않는다. [AWS HeadObject 문서](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html).
4. Content-Type은 객체 메타데이터이며 실제 파일 내용 판별과 다르다. 이 계획의 기본 검증 범위는 MIME·확장자·실측 크기다. 이미지 디코딩까지 요구한다면 명세 확정 단계에서 검증 방식과 비용을 추가로 정한다.

HEAD 이후의 검증 결과를 URL 재사용으로 무효화하지 않도록 presigned PUT에 `If-None-Match: *`를 서명한다. AWS는 이 조건부 쓰기로 동일 키의 기존 객체 덮어쓰기를 거절한다. 실제 검증에서는 정상 업로드 후 재PUT 차단과 조건 헤더 누락·변조 시 거절을 확인한다. 삭제 후 재생성까지 막는 일회용 토큰으로 설명하지 않는다. [AWS 조건부 쓰기 문서](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html).

검증 실패한 객체를 프로필 서비스가 즉시 삭제하지는 않는다. 잘못된 객체가 S3에 존재하는 것과 DB 프로필에 연결되는 것을 구분한다. 이번 테스트가 만든 객체의 삭제는 검증 종료 단계에서 수행한다.

### 4.3 트랜잭션과 오류 계약

발급은 사용자 조회·입력 검증·서명 생성만 수행하며 사용자 행 잠금이나 DB 쓰기가 없다. 기존 등록·수정은 S3 검증 후에만 `UserProfileTransactionService`를 호출한다. 같은 사용자 동시 등록, 동시 부분 수정, 닉네임 경합 정책을 그대로 유지한다.

| 상황 | 처리 방향 |
| --- | --- |
| JWT 누락·만료·서명 오류. | 기존 401 `AUTH_004`. |
| 필수값 누락·길이·양수 등 DTO 검증 실패. | 기존 400 `COMMON_001`과 필드 오류. |
| 잘못된 JSON·지원하지 않는 입력 필드·타입. | 기존 400 `COMMON_002`. |
| DB에 사용자 없음. | 기존 404 `USER_001`. |
| 이미지 정책·소유권·메타데이터 위반. | 기존 400 `USER_005`. 크기 전용 오류 코드는 클라이언트 계약상 필요할 때만 별도 배정한다. |
| 객체가 없다는 HEAD 404 또는 NoSuchKey. | 기존 400 `USER_006`. |
| 버킷 미설정. | 기존 500 `S3_001`. |
| AWS 인증·권한·타임아웃·5xx. | 기존 서버 오류 처리. 객체 없음으로 변환하지 않으며 프로필 변경 없음. |

객체가 없어도 S3 권한에 따라 HEAD는 403을 반환할 수 있다. `s3:ListBucket` 권한 여부까지 확인해야 미업로드 404 시나리오를 신뢰할 수 있으며, 403을 일괄 USER_006으로 바꾸지 않는다. [AWS HeadObject 권한 설명](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html).

## 5. 구현 파일과 커밋 분할

다음은 구현 예정 파일이다. Java 경로는 `src/main/java/com/kitschcatch/backend` 기준이며, 테스트는 대응하는 `src/test/java`에 둔다. 새 Java 파일은 한 줄 한국어 역할 주석으로 시작한다.

| 작업 단위 | 예정 변경 | 커밋 메시지 제안 |
| --- | --- | --- |
| 현재 계획. | 이 문서만 추가한다. | `33 docs: 프로필 이미지 업로드 API 구현 계획 작성` |
| 공통 정책. | `domain/user/service/ProfileImagePolicy`, 프로필 설정 클래스, `application.yml`, 정책 경계값 테스트. | `33 feat: 프로필 이미지 형식과 크기 정책 추가` |
| 업로드 발급 저장소. | `ProfileImageStorage` 발급 메서드, `ProfileImageUploadUrl`, S3 어댑터의 UUID 키·서명·필수 헤더와 관련 테스트. | `33 feat: 프로필 이미지 S3 업로드 URL 발급 구현` |
| HTTP 발급 API. | 발급 요청·응답 DTO, `UserProfileImageService`, `UserController` 연결, 실제 JSON·JWT·미등록 사용자 테스트. | `33 feat: 프로필 이미지 업로드 URL 발급 API 연결` |
| 저장 검증. | 메타데이터 결과 타입·조회 계약, S3 HEAD 처리, `UserService`의 정책 검사, 기존 저장소 대역 갱신과 원자성 테스트. | `33 feat: 프로필 저장 시 이미지 메타데이터 검증` |
| 실제 연동 검증. | 명시적으로 활성화하는 S3 통합 테스트·fixture·정리 절차, PostgreSQL 행 잠금 회귀 검증. | `33 test: 실제 S3 업로드와 프로필 저장 연동 검증 추가` |
| 명세·결과. | Swagger, Notion 계약, 클라이언트 예시와 실측 검증 기록. | `33 docs: 프로필 이미지 API 명세와 검증 결과 정리` |

인터페이스를 변경하는 커밋에는 호출부와 기존 테스트 대역 수정도 포함해 각 커밋이 빌드 가능한 단위가 되게 한다. 프로필 정책을 위해 기존 판매글·채팅 서비스 전체를 공통 추상화로 재작성하지 않는다.

메타데이터 조회는 `Optional<ProfileImageMetadata>`처럼 없음과 존재를 구분하는 계약을 제안한다. 결과에는 검증에 필요한 MIME·바이트 크기를 담고 AWS SDK 응답을 서비스에 직접 노출하지 않는다. `exists`와 메타데이터 조회를 연이어 호출해 HEAD가 중복되지 않도록 호출부를 교체한다.

## 6. 검증 계획과 완료 기준

### 6.1 자동 검증

| 영역 | 필수 시나리오 |
| --- | --- |
| 정책. | 허용 MIME별 확장자, 대소문자·공백, MIME/확장자 불일치, 크기 0·최대값·최대값+1, 경로·타인·다른 도메인 키. |
| 발급. | 미등록·등록 사용자 모두 성공, URL마다 다른 UUID, 인증 사용자 prefix, TTL·필수 헤더, DB 프로필 불변. |
| 요청 계약. | 실제 JWT 필터, 누락·만료·잘못된 토큰, 삭제된 사용자, 잘못된 타입·미지 필드·초과 길이, HTTP 상태·공통 응답·Swagger 필드. |
| 저장소. | HEAD 정상·404·403·5xx·타임아웃, 잘못된 MIME·실측 크기, 조회 URL 생성. 테스트 대역 결과를 실제 AWS 결과로 표시하지 않는다. |
| 프로필 저장. | 이미지 포함 최초 등록·교체·생략·null 해제, 실패 시 닉네임·이미지·등록 시각 보존, 생략·null 해제 시 불필요한 S3 호출 없음. |
| PostgreSQL 회귀. | 기존 수동 SQL·닉네임 경합·같은 사용자 동시 등록·필드별 동시 수정, S3 지연 중 다른 연결의 행 잠금 획득. |
| 기존 기능. | 카카오 로그인·refresh, 판매글·채팅의 기존 URL 발급·이미지 검증. |

검증 기본 명령은 `./gradlew test`와 `./gradlew build`다. 기존 PostgreSQL 테스트는 `ISSUE31_TEST_DB_URL` 등 기존 전용 환경 변수와 격리 DB로 실행하며, [기존 재현 절차](../user-profile-verification.md)를 재사용한다. 필요한 경우 `--rerun-tasks`를 사용하고 실행·건너뜀·실패 건수를 분리해 기록한다.

### 6.2 실제 S3 검증

2026-09-22에는 `dev-kc` 자격 증명이 무효였고 테스트 버킷을 특정하지 못했다. 이것은 과거 확인 결과이며 지금도 같다고 가정하지 않는다. 구현 초기에 사용할 AWS 프로필·계정·버킷·리전과 앱 설정을 다시 확인한다. 이번 문서 작성에서는 AWS 요청을 새로 실행하지 않았다.

1. 테스트 전용 버킷과 사용자 fixture를 특정한다. 백엔드의 PutObject·GetObject, 없는 객체 구분에 필요한 권한, 테스트 정리용 DeleteObject 및 사용하는 암호화 설정의 추가 권한을 확인한다. 키 값이나 서명 URL을 Git·검증 로그에 기록하지 않는다.
2. 조회 URL이 실제 클라이언트에서 표시 가능한지 확인한다. 공개 URL 문자열 생성만으로 접근 가능하다고 보지 않고, CDN 또는 허용된 조회 경로를 검증한다. 웹 클라이언트라면 origin·PUT·서명 헤더에 필요한 S3 CORS도 확인한다.
3. 격리 PostgreSQL과 실제 애플리케이션을 실행한다. 전용 통합 테스트를 추가할 때 `ProfileImageStorage`를 mock으로 교체하지 않고 명시적인 실제 S3 실행 옵션으로 활성화한다. 일반 테스트에서 자동으로 AWS 객체를 만들지 않는다.
4. 실제 발급 API의 URL과 헤더로 작은 정상 이미지를 PUT한다. 다른 수단으로 만든 URL을 발급 API 성공 근거로 대체하지 않는다.
5. 반환된 키로 프로필 최초 등록·GET 조회·새 이미지 교체·null 연결 해제를 실행하고 DB 상태와 이미지 접근을 확인한다.
6. 발급했지만 올리지 않은 키, 타인 사용자 키, 만료 URL, 필수 헤더 변조, 기존 객체 재PUT을 확인한다. 정책에 맞지 않는 메타데이터 fixture는 테스트 준비용 자격 증명으로 만들어 저장 거절을 검증한다.
7. 동일 사용자·서로 다른 필드의 DB 회귀 결과도 확인한다. 프로필 변경 실패 시 기존 값이 보존되는지 검사한다.
8. 이번 실행에서 생성한 키 목록을 기록해 성공·실패 여부와 관계없이 정리한다. 테스트 버킷 전체 삭제나 기존 객체 일괄 삭제는 수행하지 않는다. 정리 실패는 별도 실패로 남긴다.

완료 조건은 발급 API·공유 정책·메타데이터 검증·회귀 테스트·실제 S3 흐름·명세 동기화가 모두 확인되는 것이다. AWS 접근이나 Notion 원문을 확보하지 못한 항목은 구체적인 차단 원인과 함께 미완료로 남기며, 단위 테스트나 H2 통과로 대체하지 않는다.

## 7. 구현 전에 확정할 항목

| 항목 | 현재 제안 또는 필요한 정보 |
| --- | --- |
| Notion 명세. | 프로젝트 API 명세 원문과 편집 대상 페이지. 경로·필드·상태 코드·클라이언트 지원 범위 대조. |
| 이미지 규격. | 최대 5 MiB 제안, 기존 4종 MIME 제안. GIF·실제 파일 내용 검사·S3 수신 단계의 크기 강제 필요 여부 확정. |
| AWS 실행 환경. | 유효한 로컬 프로필, 테스트 버킷·리전, 조회 경로, 필요한 권한 및 대상 클라이언트 설정. |
| 기존 키 호환. | 실제 저장된 키의 형식 확인. 새 UUID 발급 규칙과 기존 키의 허용 범위를 구분. |
| 서명과 클라이언트. | Content-Type·조건부 쓰기 헤더·파일 길이의 실제 SDK 서명과 클라이언트 전송 동작 확인. |

## 8. 이번 작업의 수행 기록

- [x] 이슈 #33과 기존 구현·설정·검증 기록을 확인했다.
- [x] 원격 최신 커밋을 가져와 `develop`을 확인하고 `feat/33`을 생성했다.
- [x] 최근 저장소 커밋의 `이슈번호 type: 한글 설명` 형식을 확인했다.
- [x] 구현·테스트·실제 연동 검증 범위와 커밋 분할을 문서화했다.
- [ ] API 구현, 테스트·빌드, AWS 환경 재확인, 실제 S3 검증, Notion 동기화는 후속 구현에서 수행한다.

이번 변경은 이 계획 문서 한 파일이다. 애플리케이션 코드를 변경하지 않았으므로 테스트·빌드를 실행한 것으로 기록하지 않는다.
