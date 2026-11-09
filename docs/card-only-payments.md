# 신용카드·체크카드 결제 연동

이슈 #105. 앱의 결제 수단은 카드 결제 한 가지이며, 신용카드와 체크카드를 지원한다. 간편결제에 등록된 카드로 결제한 경우도 간편결제로 분류한다.

## 모바일 전달 사항

1. 결제 수단 영역에는 **카드 결제 (신용·체크)**만 표시한다. 주문 생성(`POST /api/orders`)과 결제 생성(`POST /api/payments`)에 `"paymentMethod": "CARD"`를 보낸다.
2. 토스 결제창은 서버가 반환한 `pgOrderId`와 주문의 `pricing.totalAmount`로 연다. 토스 SDK의 결제창 옵션과 우리 API의 `paymentMethod`는 각각 설정한다. 재시도에는 재시도 준비 API가 발급한 새 `pgOrderId`, `attemptId`를 사용한다. 금액과 견적은 [기존 주문 계약](order-pricing.md)을 따른다.
3. 토스 인증 성공 후 기존대로 `paymentKey`와 결제 시도 ID를 사용해 `POST /api/payments/{paymentId}/confirm`을 호출한다. 앱에서 주문 완료 화면을 표시하는 기준은 백엔드의 `data.status == "SUCCESS"`다.

| 백엔드 결과 | 앱 처리 |
| --- | --- |
| `SUCCESS` | 결제 완료를 표시한다. |
| 승인 API의 202 또는 조회 응답의 `PROCESSING` | `GET /api/payments/{paymentId}`로 결과를 조회한다. `processingOperation=CANCEL`이면 취소 처리 중임을 표시한다. 이 상태에서는 새 결제나 재승인을 시작하지 않는다. |
| `CANCELED`와 `failureCode=PG_UNSUPPORTED_PAYMENT_METHOD` | 지원하지 않는 결제 수단으로 결제가 취소됐음을 안내한다. 해당 주문의 결제 재시도 대신 상품이 판매 중일 때 새 주문에서 카드 결제를 선택하게 한다. |

## 서버와 토스 설정

- 서버는 토스 응답의 `method`와 `card.cardType`을 확인한다. 직접 카드 결제(`카드`/`CARD`)이면서 신용(`신용`/`CREDIT`) 또는 체크(`체크`/`CHECK`)만 완료한다. 기프트 카드, 종류가 확인되지 않는 카드, 간편결제와 기타 수단은 완료하지 않는다. 카드 번호는 읽거나 저장하지 않는다. [토스 응답 코드](https://docs.tosspayments.com/codes/enum-codes).
- 비허용 결제가 이미 승인됐거나 가상계좌 입금 대기라면 취소 시도를 저장한다. 전액 취소(`CANCELED`, 잔액 0)를 확인한 뒤 주문을 취소하고 예약을 해제한다. 승인 응답 유실 시에도 토스 재조회 결과에 같은 규칙을 적용한다.
- 자동 재조회와 취소 복구를 실행할 환경에는 `APP_PAYMENTS_RECOVERY_ENABLED=true`가 필요하다. 기존 [결제 복구 스키마와 운영 절차](issue-54-payment-recovery.md)를 사용한다. 취소 결과가 불확실하거나 토스가 취소를 거절하면 토스 상점에서 실제 처리 상태를 확인한다.
- 토스 통합결제창은 `CARD` 요청에서도 간편결제를 표시할 수 있다. 테스트·운영 상점에서 카드만 보이도록 결제창 설정을 확인하고, 통합결제창의 카드 전용 노출 설정은 [토스페이먼츠 안내](https://docs.tosspayments.com/resources/glossary/card-payment)에 따라 요청한다.
- 배포 전 앱에서 신용카드와 체크카드로 각각 결제해 완료 화면과 청구 금액을 확인한다. 토스 결제창의 비카드 수단 노출 여부와 취소 처리 중 앱이 결제 조회를 이어가는지도 확인한다.
