# 거래 상태 변경 알림과 모바일 연동

[이슈 #69](https://github.com/kitschcatch-App/kitschcatch-backend/issues/69). 기존 알림함과 FCM 발송 작업에 배송·환불·구매 확정·정산 알림을 추가한다. 수신자는 주문에 기록된 구매자·판매자다.

| `type` | 수신자 | 알림이 저장되는 조건 | 앱 이동 위치 |
| --- | --- | --- | --- |
| `SHIPMENT_REGISTERED` | 구매자 | 첫 송장 등록. | 주문 상세 배송 영역. |
| `SHIPMENT_UPDATED` | 구매자 | 택배사 또는 송장 번호가 실제로 변경됨. | 주문 상세 배송 영역. |
| `REFUND_REQUESTED` | 판매자 | 새 전액 환불 요청이 저장됨. 배송 전·후 모두 해당. | 주문 상세 환불 영역. |
| `REFUND_APPROVED` | 구매자·판매자 | 운영자가 반품 수령을 확인하고 환불을 승인함. 환불은 아직 처리 중. | 주문 상세 환불 영역. |
| `REFUND_REJECTED` | 구매자·판매자 | 운영자가 거절 상태와 사유를 저장함. | 주문 상세 환불 영역. |
| `REFUND_WITHDRAWN` | 판매자 | 구매자가 접수된 요청을 철회함. | 주문 상세 환불 영역. |
| `REFUND_COMPLETED` | 구매자·판매자 | PG 전액 취소를 검증해 환불 완료와 주문 환불 상태를 저장함. | 주문 상세 환불 영역. |
| `PURCHASE_CONFIRMED` | 판매자 | 구매 확정과 정산 대기 기록을 최초로 저장함. | 판매 주문 상세 정산 영역. |
| `SETTLEMENT_COMPLETED` | 판매자 | 지급 식별자·수취인·금액·통화가 일치하는 지급 완료를 확인함. | 판매 주문 상세 정산 영역. |
| `SETTLEMENT_FAILED` | 판매자 | 일치하는 업체 지급 기록에서 확정 실패를 확인함. | 판매 주문 상세 정산 영역. |

배송 정보 등록은 배송사의 집화나 배달 완료를 의미하지 않는다. 배송 전 환불은 접수와 PG 완료만 알린다. 일반 결제 취소는 기존 `PAYMENT_CANCELED`, 환불에 따른 전액 취소는 `REFUND_COMPLETED` 한 종류를 생성한다. 부분 취소나 결과 확인 중에는 완료를 알리지 않는다. 지급 준비·처리 중·타임아웃에는 정산 푸시를 만들지 않는다. 기존 내부 정산 실행 또는 조회 복구에서 서버가 결과를 확인한 시점에 정산 알림이 생성된다. 앱의 정산 GET은 서버에 저장된 결과를 반환한다.

## 모바일에서 사용할 계약

기존 [알림 API](issue-50-notifications.md)의 Bearer 인증·페이지 응답과 읽음 API를 그대로 사용한다. 새 거래 알림의 `targetId`는 모두 공개 주문번호(`ORD-...`)다. 기존 `CHAT_MESSAGE`는 채팅방 ID다.

`GET /api/notifications?page=0&size=20` 응답의 `data.notifications` 항목 예시:

```json
{
  "id": 1234,
  "type": "REFUND_COMPLETED",
  "title": "환불 처리가 완료되었습니다.",
  "targetId": "ORD-EXAMPLE123",
  "createdAt": "2026-11-09T14:30:00",
  "readAt": null
}
```

FCM `data` 예시(문자열 값):

```json
{
  "notificationId": "1234",
  "type": "REFUND_COMPLETED",
  "targetId": "ORD-EXAMPLE123"
}
```

1. 알림을 열면 `PATCH /api/notifications/{notificationId}/read`를 호출한다. 전체 읽음은 `PATCH /api/notifications/read-all`이고 배지에는 목록의 `unreadCount`를 사용한다.
2. `GET /api/orders/{orderId}`로 최신 상태와 거래 참여 권한을 확인한다. 필요한 경우 하위 경로 `/shipment`, `/refunds`, `/settlement`를 조회한다. 먼저 발송된 푸시가 늦게 도착할 수 있으므로 푸시 순서로 주문 상태를 바꾸지 않는다.
3. 알 수 없는 `type`은 서버의 `title`을 사용해 목록에 표시하고 기본 이동을 알림함으로 처리한다. 로그인 만료 시 로그인 후 주문 상세를 다시 열고, 다른 계정이거나 접근 권한이 없으면 목록으로 돌아간다.
4. FCM 재시도와 알림함 새로고침이 겹쳐도 앱이 관리하는 표시·배지·클릭 처리는 동일 `notificationId`로 중복을 제거한다. FCM의 정확히 한 번 전달을 보장하지 않는다.
5. 로그인·계정 변경·토큰 갱신 시 `POST /api/users/me/device-tokens`로 등록하고 로그아웃 전 `DELETE /api/users/me/device-tokens`로 해제한다. 기기 등록이나 푸시 권한 없이도 알림함은 조회할 수 있다.

푸시와 알림함 문구에 배송지, 송장 번호, 환불 사유, 증거 사진 URL, 결제 키, 계좌 정보를 넣지 않는다. 상세 API는 기존 권한과 계약을 사용한다.

## 서버 저장과 검증

주문 변경과 알림함·기기별 발송 작업은 같은 트랜잭션에서 저장한다. 기존 워커가 커밋된 작업을 읽어 FCM으로 발송하고 실패 시 재시도한다. 키는 배송 등록에 주문번호, 배송 수정에 주문번호와 변경 번호, 환불·정산에 각 업무 ID와 알림 종류, 구매 확정에 주문번호를 사용한다. DB의 수신자별 이벤트 및 기기별 발송 유일 제약을 유지한다. 동일 입력의 재처리에는 새 알림이 없고, 실제 송장 변경 `A → B → A`는 두 변경을 각각 알린다. 종료된 계정에는 알림과 발송 작업을 추가하지 않는다.

격리 PostgreSQL 실행은 `ISSUE45_TEST_DB_URL` 및 기존 사용자·암호 설정을 사용한다. CI의 공통 빌드 스크립트가 해당 변수를 구성한다.

```sh
./gradlew test --tests '*TradeNotification*'
bash deploy/sydney/check-contract.sh
python3 -m unittest discover -s deploy/sydney -p 'test_*.py'
```

## 개발·운영 적용 순서

`APP_NOTIFICATIONS_TRADE_ENABLED` 기본값은 `false`다. 이 값은 새 거래 알림 생성만 제어하며, 알림함에서 새 종류를 읽는 기능과 기존 채팅·결제 알림은 유지된다. 푸시 자체는 기존 `APP_NOTIFICATIONS_PUSH_ENABLED` 설정을 따르므로 별도로 활성화한다.

1. 시드니 개발 배포라면 변경된 `deploy.py`를 먼저 서버 배포 도구에 반영한다. 기존 릴리스의 스키마 파일과 해시는 보존한다. 검사기는 이번에 확장한 CHECK 정의와 정확히 일치하고 알림 생성이 꺼져 있으며 구버전이 읽을 수 없는 행이 없는 경우에만 이전 CHECK 계약을 호환으로 본다.
2. `069_trade_notifications.sql`을 적용한다. 기존 `orders`와 `notifications`가 있어야 한다. 트랜잭션 안에서 기존 송장에 변경 번호를 초기화하고 알림 종류 CHECK를 확장한다. 기존 알림과 읽음 기록은 보존한다. 구버전 등록 송장도 허용하도록 새 컬럼은 NULL을 허용한다.
3. 새 알림 생성을 끈 채 새 JAR를 배포하고 준비 상태와 알림함을 확인한다. 이 릴리스를 복구 기준으로 보존한다. 시드니 자동 복구의 직전 릴리스도 새 타입을 읽을 수 있게 하려면 별도 실행 ID로 동일 SHA를 한 번 더 배포해 확인한다.
4. 모바일의 알 수 없는 타입 처리와 복구 기준을 확인한 뒤 새 알림 생성을 켜고 정상 거래 한 건의 수신자를 확인한다. 문제가 발생하면 생성을 끄고 새 타입을 읽는 릴리스로 복구한다. 새 알림이 저장되기 시작한 뒤에는 이전 enum만 아는 JAR나 이전 CHECK 정의로 되돌리지 않는다.

배포 및 실제 단말·PG 호출은 이 구현 PR의 검증 범위와 별개다.
