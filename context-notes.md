# 이슈 #4 컨텍스트 노트

- 사용자가 커밋과 푸시는 직접 하겠다고 명시했으므로 이번 작업에서는 커밋과 푸시를 하지 않는다.
- 기존 미커밋 변경으로 `.gitignore` 수정이 있으며, 이번 작업에서 되돌리거나 덮어쓰지 않는다.
- 성공 응답은 컨트롤러가 명시적으로 `ApiResponse.success(data)`를 반환한다.
- 실패 응답은 `GlobalExceptionHandler`가 `ApiResponse.error(...)` 형태로 반환한다.
- Validation 실패 응답에는 필드별 오류 목록을 포함한다.
- Spring Boot 4 테스트 슬라이스 대신 `MockMvcBuilders.standaloneSetup`을 사용해 공통 응답/예외 처리만 독립적으로 검증한다.
- 공통 에러 코드는 `COMMON_001`, `COMMON_002`, `COMMON_999`로 시작하고 도메인별 코드는 이후 기능 작업에서 추가한다.

## Postman 테스트 파일 작업 컨텍스트 노트

- 현재 작업트리는 깨끗한 상태에서 시작했다.
- 실제 HTTP 매핑이 있는 컨트롤러는 인증, 판매 게시글, 주문, 결제 도메인이다.
- `ChatController`와 `UserController`는 현재 빈 클래스이므로 Postman 컬렉션에 포함하지 않는다.
- 인증 API 외 모든 API는 `Authorization: Bearer {{accessToken}}` 인증을 요구한다.
- 카카오 모바일 로그인은 실제 카카오 SDK ID 토큰과 nonce가 필요하므로 Postman 환경 변수로 값을 주입하게 한다.
- 판매 게시글 생성은 S3 presigned URL 발급 후 실제 업로드된 `imageKey`가 필요하다. 컬렉션에는 presigned URL 발급 요청과 S3 업로드 보조 요청을 함께 둔다.
- 결제 승인과 취소는 토스페이먼츠 연동 값이 필요하므로 `paymentKey`를 환경 변수로 직접 채우게 한다.
- Postman 컬렉션과 환경 파일은 `jq`로 JSON 파싱을 확인했고, 컬렉션에서 참조하는 환경 변수가 환경 파일에 모두 있는지 Node.js 스크립트로 확인했다.
