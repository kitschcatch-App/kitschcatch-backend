# 배송 추적 API (#117)

구매자와 판매자는 `GET /api/orders/{orderId}/shipment/tracking`으로 현재 운송장 추적 정보를 조회한다. 미등록 운송장은 404 `SHIPMENT_001`, 거래 당사자가 아니면 403 `ORDER_004`다. 기존 운송장 등록 API는 앱의 등록 상태를 제공하고, 실제 배송 타임라인은 새 API를 사용한다.

```json
{
  "shipmentRevision": 2,
  "carrierCode": "CJ_LOGISTICS",
  "trackingNumber": "123456789012",
  "status": "IN_TRANSIT",
  "dataState": "AVAILABLE",
  "lastEventAt": "2026-11-09T05:00:00Z",
  "lastCheckedAt": "2026-11-09T05:02:00Z",
  "stale": false,
  "events": [{"status":"IN_TRANSIT","occurredAt":"2026-11-09T05:00:00Z"}]
}
```

| 상태 | 표시 예 |
| --- | --- |
| UNKNOWN | 배송 정보 확인 전. |
| WAITING_FOR_PICKUP | 집하 대기. |
| IN_TRANSIT | 배송 이동 중. |
| OUT_FOR_DELIVERY | 배송 출발. |
| AVAILABLE_FOR_PICKUP | 지정 장소 수령 가능. |
| DELIVERED | 배송 완료. |
| EXCEPTION | 배송 상태 확인 필요. |

`dataState=PENDING`이면 확인 대기, `UNAVAILABLE`이면 일시 이용 불가를 표시한다. `AVAILABLE`에서 `stale=true`면 마지막 확인 정보를 유지하고 갱신 실패를 알린다. 이벤트는 최신순 최대 50건이다. 운송장 수정 후 버전이 달라지면 이전 타임라인을 버린다. 배송 완료 화면에서도 기존 구매 확정 절차를 사용한다.

새 구매자 알림은 `SHIPMENT_OUT_FOR_DELIVERY`와 `SHIPMENT_DELIVERED`다. 기존 `referenceId`의 주문번호로 주문 상세에 이동한다. 상태 알림을 받으면 배송 추적 API를 재조회한다. 이전 앱은 모르는 알림 유형에 기본 문구를 표시하고 주문 상세로 이동한다.

`117_shipment_tracking.sql`을 적용한다. `APP_SHIPMENT_TRACKING_ENABLED`와 `APP_SHIPMENT_TRACKING_NOTIFY_ENABLED`는 기본 false다. 활성화에는 `APP_SHIPMENT_TRACKING_CLIENT_ID`, `APP_SHIPMENT_TRACKING_CLIENT_SECRET`, `APP_SHIPMENT_TRACKING_CALLBACK_BASE`를 지정한다. 콜백 URL에는 구독 비밀 식별자가 있으므로 해당 경로를 프록시·ALB 접근 로그에서 마스킹하거나 제외한 뒤 활성화한다. CJ대한통운과 한진을 연동한다. 콜백은 조회 작업만 예약하고 최신 배송 버전과 인증된 외부 조회 결과를 대조한다. [Delivery Tracker 웹훅 안내](https://tracker.delivery/docs/tracking-webhook-api)

시드니 배포 프로그램의 스키마 검사기를 먼저 갱신한다. 새 배송 알림 생성은 호환 서버를 롤백 기준으로 확보한 뒤 켠다. 이전 알림 리더로 복귀할 때는 환경과 실행 컨테이너의 알림 생성 플래그가 꺼져 있고 새 종류의 행이 없음을 검사한다. 새 행이 이미 있다면 호환되는 리더로 복귀한다.

개발 계정과 실제 운송장 또는 제공자 테스트 구독을 준비해 최초 등록, 콜백 수신, 운송장 교체, 배송 완료를 검증한다. FCM은 알림함 저장, FCM 수락, 실제 기기 표시와 주문 상세 이동을 각각 기록한다. 개발 HTTPS에서 알림 경로가 인증을 요구하는 것까지 확인했다. #113 활성화와 실제 FCM 기기 수신은 개발 배포와 테스트 빌드가 필요하다.

Java 21과 PostgreSQL 14 및 H2에서 배송 관련 33개 테스트와 배포 프로그램 18개 테스트를 통과했다. 운송장 교체, 늦은 콜백, 동일 이벤트 저장 방지, 배송 완료 알림의 구매자 한정, 주문 상태 보존을 검증했다. 2026-11-09 제공자의 공식 가상 운송장 GraphQL 요청은 HTTP 200과 본문 `FORBIDDEN`을 반환했다. 개발 키를 사용한 조회·구독과 실제 택배 이동은 후속 검증 항목이다. [가상 운송장 안내](https://tracker.delivery/docs/dummy-tracking-number)
