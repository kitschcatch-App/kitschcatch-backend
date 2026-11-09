# 운영자 환불 요청 목록 (#115)

운영자 토큰으로 `GET /api/admin/refunds?status=REQUESTED&status=PROCESSING&page=0&size=20`을 호출한다. 기본 상태는 `REQUESTED`와 `PROCESSING`이다. `orderId`, `refundId`, `shipped`로 좁힐 수 있다. `from`은 포함, `to`는 제외하는 ISO 로컬 날짜·시간이며 기존 주문 응답의 `requestedAt`과 같은 서버 시간 기준을 사용한다. 앱은 표시 시간대를 적용하기 전에 서버 시간 기준과 일치시킨다. 서버 시간대와 실행 환경은 배포 때 기록한다.

`data`에는 `items`, `page`, `size`, `totalElements`가 있다. 요청 시각과 내부 주문 ID 오름차순이다. 페이지는 0부터, 크기는 1~100이다. 빈 결과는 `items: []`이다. 목록의 상품·거래 당사자 정보는 주문 시점 스냅샷이다. 반품 사진은 개수만 제공한다.

`GET /api/admin/refunds/{refundId}`는 기존 환불 상세 계약과 같고 사진 서명 URL을 반환한다. URL 만료 시 상세를 다시 조회한다. 처리 가능한 항목에서는 목록의 `orderId`로 기존 `POST /api/orders/{orderId}/refunds/approve` 또는 `/reject`를 호출한다. 요청에 상세의 `refundId`와 사유를 넣고, 승인에는 `returnReceived: true`를 보낸다. 실행 전 권한과 상태는 서버가 다시 검사한다. 409이면 목록과 상세를 새로 읽는다. 권한 오류는 403 `REFUND_004`, 없는 환불은 404 `REFUND_003`이다.

`115_admin_refunds.sql`로 목록 인덱스를 적용한다. 기존 환불 운영자 설정을 사용하며 부여·회수 후 실행 서버의 설정을 반영한다.

PostgreSQL 14 및 H2 환불 심사 테스트 15개가 통과했다. 운영자/구매자 권한, 주문 필터와 상세, 거절 후 기본 목록 제외, 종결 상태 필터를 실제 PostgreSQL HTTP로 확인했다. PG 호출은 테스트 대역이며 개발 서버에는 신규 API를 배포하지 않았다.
