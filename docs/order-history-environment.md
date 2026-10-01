# 거래 내역 환경 변수와 DB 적용 설정

새로 추가하는 백엔드 환경 변수는 없다. 기존 DB·JWT·이미지 URL 설정을 사용한다. 실제 값과 자격 증명은 저장소·Obsidian에 기록하지 않는다.

## 기존 실행 변수

| 환경 변수 | 설정할 내용 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local`, `dev`, `prod` 중 환경에 맞는 프로필. |
| `LOCAL_DB_URL`, `LOCAL_DB_USERNAME`, `LOCAL_DB_PASSWORD` | local PostgreSQL URL·계정·비밀번호. |
| `DEV_DB_URL`, `DEV_DB_USERNAME`, `DEV_DB_PASSWORD` | 개발 DB 접속 값. |
| `PROD_DB_URL`, `PROD_DB_USERNAME`, `PROD_DB_PASSWORD` | 운영 DB 접속 값. |
| `APP_JWT_SECRET` | 기존 access token 발급·검증과 동일한 키. 최소 32 UTF-8 바이트. 운영에서 개발 기본값을 사용하지 않는다. |
| `KAKAO_NATIVE_APP_KEY` | 기존 인증 구성 초기화용. 거래 조회에서 카카오를 호출하지 않는다. |
| `APP_S3_PUBLIC_BASE_URL` | 대표 이미지 URL의 기존 CDN 기본 주소. 값이 있으면 이 주소와 저장된 객체 키를 조합한다. |
| `APP_S3_BUCKET`, `APP_S3_REGION` | CDN 주소가 없을 때 S3 URL을 조합하는 기존 버킷과 리전. 리전 기본값 `ap-northeast-2`. |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | 수동 SQL 적용 뒤 운영 전체 스키마 검증에는 `validate`. 기존 local의 `update`가 기존 데이터 보완 SQL을 대체하지 않는다. |

대표 이미지가 있는 주문은 CDN 주소 또는 버킷 설정이 필요하다. 이미지가 없는 주문의 URL은 null이다. 조회에서는 서명 URL 발급이나 S3·PG 네트워크 요청을 하지 않으므로 거래 조회만을 위한 AWS 자격 증명이나 Toss 비밀키를 추가하지 않는다. 기존 업로드·결제 기능의 자격 증명 요구사항은 그대로다.

프로젝트는 `.env`를 자동으로 읽지 않는다. 셸·IDE 실행 구성·배포 시스템에서 주입한다. 앱은 기존 백엔드 주소와 access token을 사용한다.

## 배포 순서

기존 027·029 주문/결제 스키마 확인 → 애플리케이션 쓰기 중단 → `043_order_history.sql` 적용 → 새 버전과 스키마 검증 → 쓰기 재개 순서다. SQL 적용 후 구버전으로 쓰기를 재개하면 새 필수 스냅샷 값이 누락될 수 있다. 운영 적용·배포는 이번 작업에서 실행하지 않았다.

## 격리 PostgreSQL 검증 변수

| 환경 변수 | 기본값·용도 |
| --- | --- |
| `ISSUE43_TEST_DB_URL` | `jdbc:postgresql://127.0.0.1:<port>/<test_database>`. 지정하면 거래 내역 PostgreSQL 테스트 활성화. |
| `ISSUE43_TEST_DB_USERNAME` | 기본 `postgres`. |
| `ISSUE43_TEST_DB_PASSWORD` | 기본 빈 문자열. 격리 로컬 테스트에 한해 사용. |
| `ISSUE31_TEST_DB_URL`, `ISSUE31_TEST_DB_USERNAME`, `ISSUE31_TEST_DB_PASSWORD` | 프로필 PostgreSQL 회귀 검증. |
| `ISSUE39_TEST_DB_URL`, `ISSUE39_TEST_DB_USERNAME`, `ISSUE39_TEST_DB_PASSWORD` | 매장 PostgreSQL 회귀 검증. |
| `ISSUE41_TEST_DB_URL`, `ISSUE41_TEST_DB_USERNAME`, `ISSUE41_TEST_DB_PASSWORD` | 관심 매장 PostgreSQL 회귀 검증. |
| `ISSUE27_TEST_DB_URL`, `ISSUE27_TEST_DB_DRIVER`, `ISSUE27_TEST_DB_USER` | 예약 회귀 검증. 드라이버 `org.postgresql.Driver`. |

거래 내역 테스트는 UUID 스키마를 만들고 종료 시 삭제한다. 기존 예약 테스트는 기본 스키마에 DDL을 실행하므로 전체 검증에는 **폐기 가능한 전용 DB 인스턴스**를 사용한다. 운영·공유 개발 DB를 지정하지 않는다.

환경 변수를 주입한 뒤 실행한다.

```sh
./gradlew test --tests '*OrderHistory*Test' --console=plain
./gradlew clean test build --console=plain
```

PostgreSQL 변수가 없으면 전용 테스트를 건너뛰므로 테스트 결과의 skipped 수를 확인한다. HTTP 테스트는 고정 테스트 CDN 주소와 외부 서비스 대역을 사용하며 실제 업로드·PG 거래를 발생시키지 않는다.
