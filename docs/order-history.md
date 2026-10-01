# 거래 상세와 내 구매·판매 내역

이슈 [#43](https://github.com/kitschcatch-App/kitschcatch-backend/issues/43), 브랜치 `feat/43`. develop `1a3bab6`에서 시작했다. 기존 주문 생성·결제 흐름에 거래 당사자의 조회 API 3개를 추가했다.

## API와 인증

| 기능 | 메서드·URL |
| --- | --- |
| 거래 상세 | `GET /api/orders/{orderId}` |
| 내 구매 내역 | `GET /api/users/me/purchase-orders` |
| 내 판매 내역 | `GET /api/users/me/sale-orders` |

모든 요청에 `Authorization: Bearer {accessToken}`이 필요하다. JWT 사용자 ID로 권한을 결정하고, DB에 없는 사용자는 401이다. 다른 `userId`를 쿼리에 보내도 조회 대상을 바꿀 수 없다. 프로필 등록 여부는 제한하지 않는다.

`orderId`는 주문 생성 응답의 공개 주문 번호다. 내부 숫자 PK, 결제 ID, 재시도용 PG 주문 번호를 넣지 않는다. `ORD-` 다음에 대문자 영문·숫자가 와야 하며 이후 대문자 영문·숫자·하이픈을 허용한다. 총 5~50자, 정규식 `ORD-[A-Z0-9][A-Z0-9-]{0,45}`다. `ORD-ABC123`과 현재 생성하는 UUID 기반 번호를 모두 지원한다.

## 거래 상세

구매자 또는 주문 당시 저장된 판매자만 접근한다. 현재 판매글 소유자로 권한을 다시 계산하지 않는다. 응답의 `status`는 주문 상태이며 `payment.status`와 별개다.

```json
{
  "success": true,
  "data": {
    "orderId": "ORD-ABC123",
    "status": "PAID",
    "amount": 12000,
    "post": {
      "id": 10,
      "title": "주문 당시 상품",
      "thumbnailUrl": "https://cdn.example.com/posts/2/original.png"
    },
    "buyer": { "id": 3, "nickname": "주문 당시 구매자" },
    "seller": { "id": 2, "nickname": "주문 당시 판매자" },
    "payment": {
      "paymentId": "PAY-ABC123",
      "method": "CARD",
      "status": "SUCCESS",
      "processingOperation": "NONE",
      "recoveryState": "NONE",
      "approvedAt": "2026-09-30T12:03:00",
      "canceledAt": null,
      "lastVerifiedAt": "2026-09-30T12:03:00"
    },
    "orderedAt": "2026-09-30T12:00:00",
    "reservationExpiresAt": "2026-09-30T12:15:00"
  }
}
```

- `amount`, 상품 ID·제목, 판매자 ID·닉네임, 구매자 닉네임과 대표 이미지 키는 주문 스냅샷이다. 현재 상품 가격·닉네임을 읽어 덮어쓰지 않는다.
- 대표 이미지는 주문 시 이미지의 `sortOrder`, `id`가 가장 작은 항목이다. 이미지가 없으면 `thumbnailUrl=null`이다.
- 결제 행이 없는 과거 주문은 `payment=null`이다. 결제 상태를 임의로 READY나 성공으로 채우지 않는다.
- `processingOperation`은 NONE/CONFIRM/CANCEL, `recoveryState`는 NONE/PENDING/REVIEW_REQUIRED다. 처리 중이거나 확인이 필요한 결제를 성공으로 표시하지 않는다.
- 시각은 기존 모델의 offset 없는 ISO 8601 `LocalDateTime` 형식이다. 해당 시각이 아직 없으면 null이다. `reservationExpiresAt`이 존재한다는 사실만으로 현재 예약이 유효하다는 뜻은 아니다.
- PG payment key, PG 거래 ID, 멱등 키, 내부 실패 사유, 계정 이메일은 노출하지 않는다. 결제 ID를 알아도 기존 결제 변경 API의 구매자 권한은 그대로 적용된다.

## 구매·판매 목록

두 목록 모두 선택 `status`, `page`, `size`를 받는다.

| 입력 | 계약 |
| --- | --- |
| `status` | 주문 상태 PENDING/PAID/CANCELED/REFUNDED/PURCHASE_CONFIRMED. 생략 시 전체. 빈 문자열·공백·소문자·결제 상태 SUCCESS 등은 오류. |
| `page` | 0~10000, 기본 0. |
| `size` | 1~100, 기본 20. |

페이지 숫자 파라미터는 생략하거나 빈 값이면 기본값을 사용한다. `createdAt DESC, id DESC`로 정렬해 동일 시각에도 순서가 일정하다. 목록 요청 사이에 새 주문이나 상태 변경이 생기면 페이지 내용이 달라질 수 있으며 모든 페이지의 시점을 고정하지 않는다.

구매 목록 예시다.

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "orderId": "ORD-ABC123",
        "postId": 10,
        "postTitle": "주문 당시 상품",
        "amount": 12000,
        "orderStatus": "PAID",
        "paymentStatus": "SUCCESS",
        "orderedAt": "2026-09-30T12:00:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

판매 목록은 각 항목에 `buyer: {id, nickname}`을 추가한다. 내 구매 목록은 주문의 구매자 ID, 내 판매 목록은 스냅샷의 판매자 ID로 제한한다. 결제 행이 없어도 항목을 유지하며 `paymentStatus=null`이다. 빈 목록은 200과 `content=[]`, `totalElements=0`, `totalPages=0`; 마지막 페이지를 넘으면 내용만 비고 집계는 유지한다.

`REFUNDED`는 기존 주문 enum을 조회·필터하는 값이다. 이번 작업에서 환불 업무를 구현한 것은 아니다. 주문 PENDING과 결제 PROCESSING처럼 두 상태가 다른 것은 가능한 조합이며 하나로 합치지 않는다.

## 오류

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | COMMON_001 | 상태·페이지 형식 또는 범위 오류. |
| 400 | COMMON_002 | 공개 주문 번호 형식 오류. |
| 401 | AUTH_004 | 인증 누락·오류, refresh token 사용, DB에 없는 사용자. |
| 403 | ORDER_004 | 존재하는 주문의 구매자·판매자가 아닌 사용자. |
| 404 | ORDER_001 | 형식이 올바른 주문 번호에 해당하는 주문이 없음. |
| 500 | S3_001 | 대표 이미지가 있는데 URL 조합에 필요한 CDN 또는 버킷 설정이 없음. |
| 500 | COMMON_999 | 처리하지 못한 서버 오류. |

기존 `{success:false,error:{code,message}}` 형식을 사용한다. 복수 입력이 잘못된 경우 어떤 오류가 먼저 나오는지는 보장하지 않는다. HTTP 서버·보안 필터 자체가 거부하는 비정상 URL은 컨트롤러의 입력 검증에 도달하지 않을 수 있다.

## 구현과 데이터 보존

주문·결제를 left join하는 조회 저장소를 별도로 두었다. DTO 변환에서 현재 사용자·상품·이미지 컬렉션을 추가 로드하지 않는다. 상세와 목록은 `readOnly` 트랜잭션이며 예약 만료나 결제 복구를 실행하지 않는다. PG 재조회와 S3 객체 HEAD/GET 요청 없이 DB 상태와 설정으로 응답한다.

새 주문은 기존 스냅샷에 `buyer_nickname`, `post_thumbnail_key`를 추가로 저장한다. 판매글 수정·이미지 교체·소프트 삭제와 닉네임 수정 이후에도 주문 스냅샷이 남는다. 판매글의 물리 삭제를 허용하도록 FK를 변경하지 않았다.

대표 이미지 **객체 키**를 보존하고 URL은 기존 CDN/버킷 설정으로 조합한다. 원본 S3 객체가 외부에서 삭제되거나 덮어써지는 것까지 막지는 않는다. 추후 이미지 정리 정책을 만들 때 주문 스냅샷이 참조하는 키도 고려해야 한다.

## DB 적용

[043_order_history.sql](../src/main/resources/db/manual/043_order_history.sql)을 기존 주문·결제 스키마(027·029 적용 상태)에 적용한다.

1. 기존 버전의 애플리케이션 쓰기를 중단한다.
2. 대상 스키마에서 043 SQL을 실행한다. 사용자·주문·이미지 테이블 잠금을 잡고 두 컬럼과 조회 인덱스를 추가한다.
3. 새 애플리케이션과 전체 스키마 검증을 완료한 뒤 쓰기를 재개한다. 구버전은 필수 `buyer_nickname`을 저장하지 않으므로 SQL 적용 뒤 구버전의 주문 생성을 재개하면 안 된다.

기존 주문의 구매자 닉네임·대표 이미지 키는 **최초 적용 시점의 현재 값**으로 보완한다. 복원할 수 없는 과거 값이라고 명시하며 기존 상품명·판매자·금액 스냅샷은 바꾸지 않는다. 구매자 닉네임이 채워진 주문은 재실행 때 건드리지 않으므로 이미지가 null인 주문도 나중 이미지로 바뀌지 않는다. 누락 사용자 등으로 필수값을 보완할 수 없으면 전체 트랜잭션을 롤백한다.

인덱스는 구매자/판매자별 `(사용자 ID, created_at DESC, id DESC)`와 `(사용자 ID, order_status, created_at DESC, id DESC)` 네 개다. `CREATE INDEX IF NOT EXISTS`는 같은 이름의 다른 정의를 교정하지 않는다. 실제 운영 데이터 규모의 인덱스 생성 시간·잠금 시간은 이번 로컬 검증으로 측정하지 않았다.

## 검증 결과

2026-09-30, Java 21, H2와 격리 PostgreSQL 14에서 관련 테스트 **95개 통과** (실패·오류·건너뜀 0).

- H2 실제 HTTP 계약 45개, 수동 SQL 적용 후 PostgreSQL 동일 계약 45개.
- 실제 JWT 권한, 사용자 ID 주입 차단, 공개 주문 번호, 주문·결제 상태 전부, 잘못된 필터·페이지, 정렬 동률·최대 크기·빈 결과, 결제/이미지가 없는 주문을 검증했다.
- 주문 생성 → 승인 → 취소 → 판매글 수정·이미지 교체·소프트 삭제 후 스냅샷 유지까지 HTTP 경로로 검증했다. PG·S3 외부 응답은 대역을 사용했으며 실제 외부 결제·업로드 검증은 아니다.
- 20개 목록은 사용자 확인·목록·집계까지 SQL 3개 이하, 상세는 2개 이하. 추가 엔티티·컬렉션 fetch 0. 조회 후 주문·결제·상품 행이 변하지 않고 외부 클라이언트를 호출하지 않는 것을 확인했다.
- 수동 SQL 검증 5개: 기존 행 보완, 대표 이미지 우선순위, 재실행 시 null 포함 스냅샷 보존, 컬럼·인덱스 정의, 실패 시 DDL까지 롤백.

- `ISSUE27/31/39/41/43` PostgreSQL 설정을 모두 지정하고 `./gradlew clean test build --console=plain` 실행: **568개, 실패 0, 오류 0, 건너뜀 0**, 빌드 성공.
- 검증 후 임시 이슈 스키마 0개를 확인하고 격리 PostgreSQL 프로세스를 정상 종료했다.

환경 변수와 실행 방법은 [별도 문서](order-history-environment.md)에 정리했다. 운영 DB 적용·배포, 실제 PG/S3 호출, 주문 취소·배송·환불·구매 확정·정산 신규 기능은 포함하지 않았다.

## 명세 대조

Notion 원문: [거래 상세](https://app.notion.com/p/3c1ee6172f5680b3833fdeb1e6c1b18e), [구매 목록](https://app.notion.com/p/3c1ee6172f5680b0b46cd67440ae3917), [판매 목록](https://app.notion.com/p/3c1ee6172f5680e79679c49450e8977c).

원문 응답 필드 구조를 유지하고 상세에 생성·예약·승인·취소·확인 시각과 결제 처리·복구 상태를 추가했다. 권한 오류는 원문의 `ORDER_002`가 이미 예약 오류에 사용되고 있어 `ORDER_004`로 분리했다. Swagger와 저장소 문서를 구현 기준으로 갱신했으며 이번 작업에서 Notion 원문은 수정하지 않았다.

## 작업 기록

Obsidian `키치캐치/거래 내역 API - 작업 기록.md`와 `키치캐치/거래 내역 API - 환경 변수와 적용 설정.md`에 구현 결과와 환경 설정을 각각 저장했다. 로컬 검증과 GitHub 상태 검사, 운영 배포는 별도로 구분한다.

## 거래 후속 처리 확장

#45에서 상세 응답에 `confirmedAt`, `shipment`, `refund`를 추가했다. 해당 기록이 없으면 null이다. 목록의 주문 상태 필터에 `PURCHASE_CONFIRMED`가 추가됐다. [후속 처리 API](order-lifecycle.md)와 [045 SQL 적용 순서](order-lifecycle-environment.md)를 함께 확인한다.
