# 이슈 #4 컨텍스트 노트

- 사용자가 커밋과 푸시는 직접 하겠다고 명시했으므로 이번 작업에서는 커밋과 푸시를 하지 않는다.
- 기존 미커밋 변경으로 `.gitignore` 수정이 있으며, 이번 작업에서 되돌리거나 덮어쓰지 않는다.
- 성공 응답은 컨트롤러가 명시적으로 `ApiResponse.success(data)`를 반환한다.
- 실패 응답은 `GlobalExceptionHandler`가 `ApiResponse.error(...)` 형태로 반환한다.
- Validation 실패 응답에는 필드별 오류 목록을 포함한다.
- Spring Boot 4 테스트 슬라이스 대신 `MockMvcBuilders.standaloneSetup`을 사용해 공통 응답/예외 처리만 독립적으로 검증한다.
- 공통 에러 코드는 `COMMON_001`, `COMMON_002`, `COMMON_999`로 시작하고 도메인별 코드는 이후 기능 작업에서 추가한다.

# PR #14 리뷰 반영 컨텍스트 노트

- 사용자가 이후 리뷰 반영 작업을 세 커밋으로 나누라고 지시했다.
- hanjyeong 리뷰 중 액션 가능한 항목은 주문 상태 전이 검증, 결제 승인 paymentKey 검증, 판매자 본인 상품 주문 차단이다.
- 기존 서비스 계층 검증은 유지하되 엔티티 메서드에도 불변식 검증을 추가해 직접 호출 시 잘못된 상태 변경을 막는다.
- HTTP 요청의 `paymentKey`는 DTO `@NotBlank`로 검증되지만 도메인 직접 호출 경로도 있으므로 `Payment.startConfirm`과 `Payment.approve`에서 방어한다.
- 주문 상태 전이 실패는 새 `ORDER_INVALID_STATE` 에러 코드로 구분한다.
- 주문 생성 시 판매자 본인 상품이면 기존 주문 가능 여부와 무관하게 `ORDER_UNAVAILABLE`로 차단한다.
