# 배송지 주소록과 주문 배송지

이슈 #93. 국내 배송 기준 회원 주소록 CRUD와 기본 배송지, 주문 시점 수령 정보를 제공한다.

## API

모든 API는 서비스 access JWT가 필요하며 사용자 ID는 토큰에서 확인한다.

| 메서드 | 경로 | 성공 |
|---|---|---|
| GET | `/api/users/me/addresses` | 200, 기본 배송지 우선·수정 시각/ID 역순 배열. 빈 배열 허용. |
| GET | `/api/users/me/addresses/{id}` | 200, 본인 배송지. |
| POST | `/api/users/me/addresses` | 201, 저장된 배송지. |
| PATCH | `/api/users/me/addresses/{id}` | 200, 변경된 배송지. |
| DELETE | `/api/users/me/addresses/{id}` | 200, `{"success":true}`. 목록을 다시 조회한다. |

등록 예시:

```json
{"label":"집","recipientName":"홍길동","recipientPhone":"010-1234-5678","postalCode":"01234","addressLine1":"서울 예시로 10","addressLine2":"101호","deliveryMemo":"문 앞","isDefault":true}
```

`recipientName`(1~50자), `recipientPhone`(하이픈·공백 제거 후 숫자 9~12자리), `postalCode`(5자리 문자열), `addressLine1`(1~255자)은 등록 필수다. `label`(30자), `addressLine2`(255자), `deliveryMemo`(200자)는 선택이다. 앞뒤 공백을 제거한다. 실제 주소 존재 여부나 전화 소유권을 확인하는 API는 아니다.

응답 data에는 `id`, 위 8개 필드, `createdAt`, `updatedAt`이 포함된다. 선택값 null의 생략 여부는 기존 Jackson 설정을 따른다. 수령인의 주소·연락처를 공개 프로필에 노출하지 않는다.

PATCH의 생략은 보존, 선택 문자열의 null/공백은 삭제, 필수 문자열의 null/공백은 400이다. 빈 객체, 미지원 필드, 문자열/불리언 타입 오류도 400이다. `isDefault`는 null을 허용하지 않는다.

## 기본 배송지와 오류

기본 최대 10개이며 첫 등록은 isDefault=false여도 기본 지정한다. 다른 주소에 isDefault=true를 보내면 기존 기본을 해제한다. 현재 기본에 false를 보내는 요청은 409이다. 기본 삭제 시 남은 최근 수정 주소(ID 역순 동률 해소)를 지정하며 마지막 주소 삭제는 허용한다. 기본 지정 자체는 수정 시각을 변경하지 않으며 PATCH로 해당 주소를 수정하면 수정 시각을 갱신한다.

동일 주소 중복은 허용한다. 등록·수정·삭제·주문 주소 복사는 활성 사용자 행 잠금으로 직렬화한다. PostgreSQL 부분 유일 인덱스가 사용자당 기본 주소 최대 1개를 보장한다. 주소가 있을 때 기본을 반드시 유지하는 규칙과 최대 개수는 서비스에서 처리한다.

- 400 COMMON_001/002: 잘못된 형식·필수값·ID·미지원 필드.
- 401 AUTH_004: 인증 실패·탈퇴 사용자.
- 404 ADDRESS_001: 존재하지 않거나 타인 소유 배송지. 재삭제도 동일하다.
- 409 ADDRESS_002: 개수 제한.
- 409 ADDRESS_003: 기본 배송지 해제 불가.

## 주문과 탈퇴

`POST /api/orders`에 양수 `shippingAddressId`를 추가한다. 주소 ID를 보내면 본인 소유 주소만 허용한다. 서버는 수령인·전화·우편번호·기본/상세주소·요청사항을 orders에 복사한 뒤 INSERT한다. 주소록의 추후 수정·삭제는 해당 주문을 바꾸지 않는다. 배송지 이름과 기본 여부는 복사하지 않는다.

`GET /api/orders/{orderId}`의 `shippingAddress`는 주문 스냅샷이다. 구매자는 확인할 수 있고, 판매자는 결제 승인 시각이 저장된 이후 확인한다. 미결제 판매자에게는 주소를 반환하지 않는다. 당사자가 아닌 요청은 기존대로 403이다. 주소가 없는 기존 주문은 null/필드 생략으로 처리하며 과거 주소를 추정해 채우지 않는다. 주소록 수정으로 주문 주소를 변경하는 API는 제공하지 않는다.

탈퇴 시 사용자 잠금 안에서 주소록을 실제 삭제한다. 탈퇴와 주소 등록이 경합해도 주소록이 남지 않는다. 주문 스냅샷은 거래 기록으로 유지하며 운영의 개인정보 보관·삭제 정책 적용 대상이다.

## DB와 앱 적용 순서

1. 기존 users/orders 스키마에 `src/main/resources/db/manual/093_shipping_addresses.sql`을 코드 배포 전에 적용한다. 단일 트랜잭션이며 반복 적용 가능하다. 기존 주문 값은 변경하지 않는다.
2. 서버를 배포한다. `APP_ADDRESSES_MAX_COUNT` 기본 10, 양수만 허용한다.
3. 앱에 주소록과 주문서 선택을 연결한다. 주문 요청에 shippingAddressId를 보내도록 한다.
4. 앱 전환 후 `APP_ORDERS_SHIPPING_ADDRESS_REQUIRED=true`를 설정한다. 기본 false는 기존 앱의 주소 없는 주문 생성을 허용한다. 기존 주문 조회/결제에는 필수화를 소급하지 않는다.

코드 롤백 시 신규 DB 컬럼/테이블을 유지하면 기존 버전 동작에 영향을 주지 않는다. 주소록이나 주문 주소의 삭제를 동반하는 자동 역마이그레이션은 없다. 운영 DB 적용과 배포는 이 PR의 로컬 검증에 포함되지 않는다.

## 검증 방법

Java 21에서 `./gradlew test build --no-daemon --max-workers=1`을 실행한다. 배송지 HTTP 테스트는 실제 JWT, 랜덤 HTTP 포트, H2를 사용한다. PostgreSQL 검증은 폐기 가능한 전용 DB에만 다음을 실행한다. create-drop과 수동 SQL을 사용하므로 공유 DB를 지정하면 안 된다.

```sh
ADDRESS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55493/address93 ./gradlew test --tests '*ShippingAddress*HttpTest' --no-daemon --max-workers=1
```

DB 사용자는 실행 사용자 이름, 암호는 빈 값이다. 해당 전용 DB에 맞는 로컬 인증 설정을 사용한다. PostgreSQL에서 정상 CRUD·권한·기본값·타입·필수화·상한 경합·기본 변경 경합·주문/주소 삭제 경합·탈퇴/등록 경합·스냅샷 보존·SQL 반복·유일 제약을 검증한다. 판매자 주소 공개 테스트는 DB의 승인 시각을 설정하며 실제 PG 청구는 수행하지 않는다. 주소 검색 화면과 실제 배송 성공은 별도 앱/운영 검증이다.

## 실제 검증 결과

- Java 21 `./gradlew test build --no-daemon --max-workers=1`: 성공. JUnit XML 합계 1,179개, 성공 848개, 실패/오류 0개, 건너뜀 331개. 다른 이슈의 환경 조건 DB 테스트와 PostgreSQL 전용 항목 등을 포함한 건너뜀이다.
- 전용 임시 PostgreSQL 14, 포트 55493에서 `ADDRESS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55493/address93 ./gradlew test --tests '*ShippingAddress*HttpTest' --no-daemon --max-workers=1`: 19개 성공, 실패/오류/건너뜀 0개.
- 기존 주문 예약 통합 테스트와 배송지 HTTP 테스트를 함께 실행해 회귀를 확인했다.
- `git diff --check`: 통과.
- 운영 DB 적용·배포·실제 PG·배송 성공·앱 화면 검증은 수행하지 않았다.
