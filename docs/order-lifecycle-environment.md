# 거래 후속 처리 환경 변수와 적용 순서

실제 값과 키·계좌·지급 대상 정보는 저장소와 Obsidian에 적지 않는다. 프로젝트는 `.env`를 자동으로 읽지 않으므로 셸·IDE·배포 설정에서 주입한다.

## 추가 환경 변수

| 변수 | 기본값·용도 |
| --- | --- |
| `APP_SETTLEMENT_OPERATOR_USER_IDS` | 빈 집합. 내부 정산 실행을 허용할 기존 사용자 PK의 쉼표 구분 목록. 실제 값은 운영 담당자가 지정한다. 일반 판매자 목록을 넣지 않는다. |
| `APP_SETTLEMENT_FEE_BASIS_POINTS` | 미설정(null). 정산 수수료율 0~9999. 1은 0.01%다. 0도 명시적으로 설정해야 한다. 운영 정책 확정 후 입력한다. |

수수료/실행자 변수만으로 지급은 활성화되지 않는다. 현재 기본 `SettlementGateway`는 실제 지급을 실행하지 않으며 503으로 차단한다. 실제 지급 제공자와 검증된 판매자 수취 정보, API 인증/암호화 및 멱등 조회 계약을 구현해야 한다. 아직 선정되지 않은 업체의 키 이름이나 임의 계좌 환경 변수를 만들지 않았다.

배송 후 환불은 REQUESTED로 저장한다. 반품 확인·승인 기능과 정책이 정해지기 전에는 자동 PG 취소를 실행하지 않는다. 이를 자동 실행으로 바꾸는 환경 변수는 없다.

## 기존 환경 변수·운영 설정

| 변수/속성 | 용도 |
| --- | --- |
| `LOCAL_DB_*`, `DEV_DB_*`, `PROD_DB_*`, `SPRING_PROFILES_ACTIVE` | 기존 PostgreSQL 접속과 프로필. |
| `APP_JWT_SECRET` | 기존 JWT 서명 키. 운영에서는 개발 기본값을 사용하지 않는다. |
| `KAKAO_NATIVE_APP_KEY` | 기존 인증 구성 초기화 값. 새 거래 API에서 카카오를 호출하지 않는다. |
| `APP_TOSS_PAYMENTS_BASE_URL`, `APP_TOSS_PAYMENTS_SECRET_KEY` | 기존 카드 승인/취소/조회용 Toss 설정. 지급대행 설정과 동일하다고 가정하지 않는다. |
| `APP_PAYMENTS_RECOVERY_ENABLED` | Spring relaxed binding으로 기존 `app.payments.recovery.enabled`를 설정. 기본 false이므로 자동 복구를 사용할 환경에서 true가 필요하다. |
| `app.payments.recovery.scan-delay`, `success-scan-delay`, `batch-size` | 기존 복구 스케줄러 설정. 기본 30s, 1h, 100. |
| `APP_S3_PUBLIC_BASE_URL` 또는 `APP_S3_BUCKET/APP_S3_REGION` | 기존 거래 상세 이미지 URL 구성. 신규 API 자체에 별도 S3 자격 증명은 추가하지 않는다. |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | 배포 시 수동 SQL 적용 후 `validate`로 확인. |

PG 응답 미확정 시 상품 예약을 임의 해제하지 않는다. 스케줄러/웹훅 복구가 비활성화돼 있으면 불확실 상태가 자동 종료되지 않으므로 운영에서 복구 상태를 확인해야 한다. 정산에는 아직 주기 조회 스케줄러가 없으며 내부 POST 재요청이 같은 지급 식별자를 조회한다.

## 수동 SQL과 배포

1. 기존 027·029·043 스키마가 적용됐는지 확인한다.
2. 주문/결제 쓰기와 복구 작업을 중단하고 구버전 인스턴스를 내린다.
3. `src/main/resources/db/manual/045_order_lifecycle.sql`을 PostgreSQL에 적용한다. SQL은 BEGIN/COMMIT과 주문 테이블 잠금을 사용한다.
4. 새 버전의 전체 스키마를 validate하고 주문 조회·취소·배송·환불·확정·정산 차단 상태를 확인한다.
5. 트래픽과 복구 작업을 재개한다.

구버전은 PURCHASE_CONFIRMED를 읽을 수 없으므로 혼합 버전 운용을 피한다. SQL은 취소 시각을 기존 결제 취소 시각에서 한 번 보완하고, 과거 주문의 배송·환불·구매 확정·정산 정보를 추정하지 않는다. 신규 필드와 유일 인덱스를 추가하고 알려진 주문 상태 CHECK 제약 두 이름만 교체한다. 재실행 시 이미 저장한 이력을 덮어쓰지 않는다. 운영 적용은 이번 작업에서 실행하지 않았다.

## 격리 PostgreSQL 검증

`ISSUE45_TEST_DB_URL`, `ISSUE45_TEST_DB_USERNAME`(기본 postgres), `ISSUE45_TEST_DB_PASSWORD`(기본 빈 값)를 지정한다. 테스트마다 `issue45_<UUID>` 스키마를 만들고 지운다. JPA가 생성한 신규 컬럼을 제거한 뒤 실제 045 SQL을 두 번 적용하고 HTTP 계약을 실행한다.

전체 회귀에는 기존 `ISSUE27_TEST_DB_URL/DRIVER/USER`, `ISSUE31/39/41/43_TEST_DB_URL/USERNAME/PASSWORD`도 함께 지정한다. 27번 검증은 기본 스키마를 변경하므로 운영·공유 DB가 아닌 폐기 가능한 전용 PostgreSQL 인스턴스를 사용한다.

```sh
./gradlew clean test build --console=plain
```

외부 PG와 지급 제공자는 테스트 대역이다. 테스트의 정산 담당 ID 900000과 500 basis points는 검증용 값이며 운영 설정이 아니다. PG/S3 비밀키나 실자금 지급 없이 계약과 동시성을 검증한다.
