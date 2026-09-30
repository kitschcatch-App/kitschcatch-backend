# 매장 지도 환경 변수와 앱 설정

이번 매장 조회 기능이 추가하는 백엔드 환경 변수는 없다. 기존 DB·JWT 설정으로 동작한다. 서버는 네이버 외부 API를 호출하지 않으므로 네이버 Client Secret이나 지도 키를 서버 환경에 추가하지 않는다.

## 백엔드 실행에 필요한 기존 값

| 환경 변수 | 용도 | 설정 위치·값 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 실행 프로필 | `local`, `dev`, `prod` 중 배포 환경에 맞게 선택. |
| `LOCAL_DB_URL`, `LOCAL_DB_USERNAME`, `LOCAL_DB_PASSWORD` | local DB | `jdbc:postgresql://<host>:<port>/<database>`와 해당 DB 계정. |
| `DEV_DB_URL`, `DEV_DB_USERNAME`, `DEV_DB_PASSWORD` | dev DB | 개발 서버의 DB 접속 설정. |
| `PROD_DB_URL`, `PROD_DB_USERNAME`, `PROD_DB_PASSWORD` | prod DB | 운영 DB 접속 설정. |
| `APP_JWT_SECRET` | 서비스 access token 서명·검증 | 기존 인증 서버와 같은 값. 운영에 개발 기본값을 사용하지 않는다. 최소 32 UTF-8 바이트. |
| `KAKAO_NATIVE_APP_KEY` | 기존 인증 구성 초기화 | 애플리케이션 전체 기동에 필요한 기존 카카오 네이티브 앱 키. 매장 검색에서 사용하지 않는다. |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | 스키마 관리 | 운영은 기존 전체 스키마와 수동 SQL 적용 후 `validate` 사용을 권장한다. 기존 local 설정은 `update`이므로 수동 SQL 제약 적용 순서를 확인한다. |

현재 프로젝트는 `.env`를 자동으로 읽는 설정이 없다. 셸·IDE 실행 구성 또는 배포 시스템에서 환경 변수로 주입한다. 값은 저장소나 Obsidian에 적지 않고 변수명과 설정 위치만 기록한다. 기존 S3·Toss 값은 해당 기능을 사용할 때 필요하며 매장 조회만을 위해 새로 발급하지 않는다.

## 네이버 지도 SDK용 앱 빌드 설정

앱 플랫폼과 앱 저장소를 수정하는 작업은 이번 PR에 포함하지 않았다. 아래 변수명은 앱 팀에 제안하는 빌드 변수이며 이 백엔드가 읽는 환경 변수가 아니다.

| 제안 빌드 변수 | 실제 SDK 설정 | 필요한 값 |
| --- | --- | --- |
| `NAVER_MAPS_KEY_ID` | Android `com.naver.maps.map.NCP_KEY_ID`; iOS `NMFNcpKeyId`; Web `ncpKeyId` | 네이버 Cloud Maps에서 발급한 앱의 Client/Key ID. |
| `API_BASE_URL` | 앱 HTTP 클라이언트 | 해당 환경의 백엔드 기본 URL. 실제 배포 주소는 별도 전달. |

Android 패키지 이름, iOS Bundle ID 또는 Web 서비스 URL을 네이버 Cloud의 해당 앱에 등록하고 Dynamic Map을 활성화해야 한다. 앱에 들어가는 SDK 식별 키와 서버 비밀키를 구분하며 Client Secret을 앱에 넣지 않는다. 실제 키 값과 앱 등록 결과는 이번 작업에서 확인하지 않았다.

2026-09-30 공식 문서 기준: [Android 시작하기](https://navermaps.github.io/android-map-sdk/guide-en/1.html), [iOS 시작하기](https://navermaps.github.io/ios-map-sdk/guide-ko/1.html), [Web 키 설정](https://navermaps.github.io/maps.js.en/docs/tutorial-1-Getting-Client-ID.html).

## 격리 PostgreSQL 테스트용 값

| 환경 변수 | 기본값·역할 |
| --- | --- |
| `ISSUE39_TEST_DB_URL` | `jdbc:postgresql://127.0.0.1:<port>/<test_database>`. 지정하면 매장 PostgreSQL 검증을 활성화한다. |
| `ISSUE39_TEST_DB_USERNAME` | 기본 `postgres`. 테스트용 DB 계정. |
| `ISSUE39_TEST_DB_PASSWORD` | 기본 빈 문자열. 격리 로컬 테스트에 한해 사용했다. |
| `ISSUE31_TEST_DB_URL`, `ISSUE31_TEST_DB_USERNAME`, `ISSUE31_TEST_DB_PASSWORD` | 기존 프로필 SQL·동시 등록 PostgreSQL 검증을 함께 실행할 때 설정한다. |
| `ISSUE27_TEST_DB_URL`, `ISSUE27_TEST_DB_DRIVER`, `ISSUE27_TEST_DB_USER` | 기존 예약 테스트를 PostgreSQL로 실행할 때 지정한다. 드라이버는 `org.postgresql.Driver`다. |

매장·프로필 테스트는 임시 UUID 스키마를 만들고 삭제한다. 기존 예약 테스트는 제공한 DB의 기본 스키마에 DDL을 실행하므로 전체 회귀 테스트에는 **통째로 폐기 가능한 전용 DB 인스턴스**를 사용해야 한다. 운영 DB나 다른 작업의 DB를 지정하지 않는다.

```sh
./gradlew test --tests '*domain.store.*' --console=plain
./gradlew clean test build --console=plain
```

환경 변수가 없으면 PostgreSQL 전용 테스트가 건너뛰어지므로 결과의 skipped 수를 확인한다. 이번 검증에서는 세 이슈의 PostgreSQL 설정을 모두 지정해 402개 테스트, 건너뜀 0을 확인했다.
