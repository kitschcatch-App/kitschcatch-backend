# 이슈 #4 컨텍스트 노트

- 사용자가 커밋과 푸시는 직접 하겠다고 명시했으므로 이번 작업에서는 커밋과 푸시를 하지 않는다.
- 기존 미커밋 변경으로 `.gitignore` 수정이 있으며, 이번 작업에서 되돌리거나 덮어쓰지 않는다.
- 성공 응답은 컨트롤러가 명시적으로 `ApiResponse.success(data)`를 반환한다.
- 실패 응답은 `GlobalExceptionHandler`가 `ApiResponse.error(...)` 형태로 반환한다.
- Validation 실패 응답에는 필드별 오류 목록을 포함한다.
- Spring Boot 4 테스트 슬라이스 대신 `MockMvcBuilders.standaloneSetup`을 사용해 공통 응답/예외 처리만 독립적으로 검증한다.
- 공통 에러 코드는 `COMMON_001`, `COMMON_002`, `COMMON_999`로 시작하고 도메인별 코드는 이후 기능 작업에서 추가한다.
