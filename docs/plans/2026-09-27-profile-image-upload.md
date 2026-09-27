# 프로필 이미지 업로드 URL 발급 구현 계획과 결과

- 이슈: [#33 프로필 이미지 업로드 URL 발급 및 S3 연동 검증](https://github.com/kitschcatch-App/kitschcatch-backend/issues/33).
- 브랜치: `feat/33`, 기준 `develop`의 `2cc8320`.
- 명세: [Notion 프로필 이미지 업로드 URL 발급](https://app.notion.com/p/URL-3deee6172f568077ad81eefead3a59dc).
- 작성·구현 확인일: 2026-09-27.
- 최종 요청·응답·설정·검증 기록: [프로필 이미지 업로드 API](../profile-image-upload.md).

## 1. 범위와 확정 사항

인증된 사용자가 프로필 전용 presigned PUT URL을 발급받고, 업로드한 객체 키를 기존 프로필 등록·수정 API에 연결한다. 프로필 미등록 사용자도 발급할 수 있으며 발급 자체는 DB를 변경하지 않는다.

구현 중 실제 Notion 원문을 확인해 초기 제안과 다음 차이를 반영했다.

| 항목 | 초기 제안 | 확정 구현 |
| --- | --- | --- |
| 경로. | `/api/users/me/profile-image/presigned-url`. | `/api/users/me/profile/image/presigned-url`. |
| 요청. | `originalFileName`, `contentType`, `contentLength`. | `fileName`, `contentType`, `fileSize`. |
| 형식. | JPEG·PNG·WebP·GIF. | JPEG·PNG·WebP. |
| 최대 크기. | 5MiB(5,242,880바이트). | 5MB(5,000,000바이트). |
| 만료. | 공통 S3 설정 기본 5분. | 프로필 전용 설정 기본 10분. |
| 응답. | URL·키·조회 URL·초 단위 만료·헤더. | 기존 항목에 Notion의 `expiresAt` 포함. |
| 타인 이미지 키. | 400. | 403 `USER_007`. |

사용자가 실제 AWS S3 검증은 다음에 진행하도록 범위를 조정했다. 이번에는 구현과 로컬·PostgreSQL 검증까지 완료하며 실제 S3 PUT, CDN 접근, CORS, 만료·변조·재PUT 실증은 후속 작업으로 남긴다. 실제 S3 전용 테스트 실행 도구도 이번에 추가하지 않았다.

닉네임 중복 확인 API, 이미지 디코딩·리사이즈, 기존 객체 삭제, 미참조 객체 정리와 테이블 추가는 이번 범위에 포함하지 않는다.

## 2. 구현 구조

Java 경로는 `src/main/java/com/kitschcatch/backend` 기준이다.

| 구성 | 역할 |
| --- | --- |
| `domain/user/service/ProfileImagePolicy`, `ProfileImageProperties`. | 파일명·MIME·크기·사용자별 키 규칙과 프로필 전용 설정. |
| `domain/user/service/ProfileImageStorage`. | 발급 결과와 `Optional<ProfileImageMetadata>` 조회 계약. |
| `domain/user/storage/S3ProfileImageStorage`. | UUID 키 생성, PUT 서명, HEAD 한 번으로 메타데이터 조회, 조회 URL 인코딩. |
| `domain/user/dto/CreateProfileImageUploadUrlRequest`. | 필수값 검증과 DTO 한정 엄격한 JSON 파싱. |
| `domain/user/service/UserProfileImageService`. | 사용자 존재 확인 후 발급. 사용자 행 잠금·DB 쓰기 없음. |
| `domain/user/controller/UserController`. | JWT principal을 사용한 HTTP 200 발급 응답과 Swagger. |
| `domain/user/service/UserService`. | 키 소유권과 메타데이터 검증 후 기존 쓰기 트랜잭션 호출. |
| `domain/user/service/UserProfileTransactionService`. | 기존 행 잠금·닉네임 경합·부분 수정 계약 유지. |

## 3. 저장소와 트랜잭션 결정

- 키는 `profiles/{인증 userId}/{UUID}.{확장자}`로 발급한다. 원본 파일명은 키에 넣지 않는다.
- 기존 `profiles/{userId}/image.png` 같은 단일 파일명도 검증 후 사용한다. UUID만 강제하지 않는다.
- GIF 신규 업로드·연결은 허용하지 않는다. 이미 저장된 GIF를 조회하거나 이미지 필드를 생략한 닉네임 수정은 기존 키를 재검증하지 않는다.
- PUT 서명에 Content-Type·Content-Length·If-None-Match를 포함한다. SDK가 실제로 이 헤더를 서명했는지 로컬 테스트로 확인한다.
- 클라이언트는 반환된 `uploadHeaders`와 원본 파일 바이트를 사용한다. Host·Content-Length는 HTTP 클라이언트가 생성하므로 응답 헤더 목록에서 제외한다.
- `If-None-Match: *`는 기존 객체 덮어쓰기를 방지하는 조건이다. 실제 AWS 차단 동작은 아직 검증하지 않았으며 삭제 후 재생성을 막는 일회용 URL로 설명하지 않는다.
- 등록·수정은 S3 HEAD 한 번으로 존재·실제 크기·MIME·확장자 일치를 검사한다. 객체 메타데이터 검사이며 파일 내용 판별은 하지 않는다.
- HEAD 404·NoSuchKey만 미업로드로 처리한다. 권한 오류·타임아웃·5xx는 서버 오류로 전파한다.
- S3 호출은 쓰기 트랜잭션 밖에서 수행한다. 실패하면 닉네임·이미지·등록 시각을 변경하지 않는다.
- PATCH 이미지 필드 생략은 기존 값 유지, 명시적 null은 연결 해제다. 두 경우 모두 HEAD 호출을 하지 않는다.
- 판매글·채팅의 기존 S3 URL 만료 설정은 변경하지 않았다.

## 4. 실제 커밋 분할

| 커밋 | 변경 |
| --- | --- |
| `9d23d4e`. | `33 docs: 프로필 이미지 업로드 API 구현 계획 작성`. |
| `04daeca`. | `33 feat: 프로필 이미지 형식과 크기 정책 추가`. |
| `0fb74e9`. | `33 feat: 프로필 이미지 S3 업로드 URL 발급 구현`. |
| `90d78d3`. | `33 feat: 프로필 이미지 업로드 URL 발급 API 연결`. |
| `eda1b1e`. | `33 feat: 프로필 저장 시 이미지 메타데이터와 소유권 검증`. |
| `83e8f70`. | `33 test: 이미지 검증 실패 시 PostgreSQL 원자성 검증 추가`. |
| 문서 정리 커밋. | `33 docs: 프로필 이미지 API 명세와 검증 결과 정리`. |

인터페이스 변경 커밋에는 호출부와 기존 테스트 수정도 포함했다.

## 5. 완료와 후속 작업

- [x] Notion 원문 확인과 실제 경로·필드·정책 반영.
- [x] URL 발급, 공유 정책, 메타데이터 검증과 소유권 오류 구현.
- [x] 실제 JWT HTTP·JSON 타입·Swagger 계약 검증.
- [x] SDK 서명과 S3 오류 처리의 로컬 테스트.
- [x] 격리 PostgreSQL 수동 SQL·닉네임 경합·등록 경합·부분 수정·잠금·실패 원자성 검증.
- [x] 전체 테스트 270건 통과, 실패·오류·건너뜀 0건, 빌드 성공.
- [x] 테스트 스키마 잔여 0개 확인, 임시 PostgreSQL 종료.
- [x] Notion 정책·응답 예시 반영.
- [ ] Notion 업로드 방법·검증 설명·오류 목록의 잔여 동기화.
- [ ] 실제 AWS S3 연동 검증. 사용자 요청으로 후속 작업으로 이관.

2026-09-27 `dev-kc` STS 재확인은 `InvalidClientTokenId`로 실패했다. 이후 사용자가 실제 AWS 확인을 다음 작업으로 미뤘다. 로컬 SDK 서명이나 S3 테스트 대역 결과를 실제 AWS 성공으로 표시하지 않는다.
