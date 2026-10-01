# 검수 매장 CSV 검증·적재·갱신 도구.

[이슈 #55](https://github.com/kitschcatch-App/kitschcatch-backend/issues/55)의 별도 Java CLI다. Spring 서버·스케줄러·자동 seed를 시작하지 않는다. 실제 매장 자료는 포함하지 않으며 `src/test/resources/issue55`는 테스트 전용 합성 자료다. [기존 매장 계약](store-map.md)을 기준으로 구현했다. 제공된 Notion 상위 페이지는 접근되지 않아 CSV의 세부 null 표기·갱신 정책은 아래와 같이 확정했다. 외부 명세는 변경하지 않았다.

## 파일 계약.

- 매장 헤더는 정확히 `id,name,region,address,latitude,longitude,phone` 순서다.
- 영업시간 헤더는 정확히 `store_id,day_of_week,open_time,close_time,closed` 순서다. 두 파일 경로 모두 필수다. 변경 없는 파일은 헤더만 제공할 수 있지만 두 파일 모두 데이터가 없으면 거부한다.
- UTF-8이며 선두 BOM을 허용한다. LF/CRLF, RFC4180의 큰따옴표 인용, 쉼표·개행·`""` 이스케이프를 지원한다. 잘못된 UTF-8·미종료 인용·빈 행·열 수·헤더 순서 오류는 거부한다. 각 파일은 16MiB, 데이터 100000행 이하로 제한한다. 파일을 한 번 읽은 바이트로 파싱과 SHA-256을 계산하여 실행 중 파일 교체가 적용 이력과 데이터에 차이를 만들지 않는다.
- ID는 선행 0 없는 양의 십진 정수이며 BIGINT 최댓값 미만이어야 한다. 같은 매장 ID, 같은 `(store_id,day_of_week)`는 파일 내 중복을 거부한다. 좌표 중복은 허용한다.
- 이름 200자, 주소 500자, 전화번호 30자 이하를 적용한다. 필수 문자열의 공백만 있는 값과 NUL은 거부한다. 값을 자동 trim하지 않는다. 지역은 기존 17개 표준 약칭만 받는다.
- WGS84 좌표는 유한 십진수이며 위도 -90~90, 경도 -180~180이다. 지수·NaN·Infinity·16진수 표기는 받지 않는다.
- 선택 값인 전화번호·두 시간만 빈 필드 또는 `\N`을 SQL null로 해석한다. 인용된 빈 문자열과 인용된 `\N`도 null이다. `null` 문자열은 null 표기가 아니며 전화번호에서는 그대로 문자열로 저장한다.
- 요일은 `MONDAY`~`SUNDAY` 대문자, `closed`는 정확히 소문자 `true`/`false`다. 시간은 Asia/Seoul 현지 `HH:mm`, 00:00~23:59이다. 휴무는 두 시간 null, 영업은 두 시간 모두 필수다. 야간 영업·동일 시각의 24시간 영업은 기존 계약을 따른다.
- 영업시간의 부모는 같은 매장 CSV 또는 지정 DB의 매장에 있어야 한다. 기존 부모에 영업시간만 갱신할 수도 있다.

## 갱신·폐점·이력 정책.

매장 ID와 매장·요일 키로 UPSERT한다. 기존 매장 행을 삭제하거나 다시 생성하지 않아 `store_favorites` ID·등록 시각·관계가 유지된다. 입력한 행은 선택 null을 포함한 모든 필드를 갱신한다. CSV에 누락된 매장·요일은 보존한다. 전체 목록 동기화나 영업시간 전체 교체가 아니다. 오기입된 요일 삭제도 자동 처리하지 않는다.

`closed=true`는 **해당 요일의 휴무**다. 매장의 폐점을 뜻하지 않는다. 현재 조회 API에 폐점 상태 필드가 없으므로 이 도구는 폐점을 지원하지 않는다. 폐점 확인 시 출처·검수 근거를 따로 기록하고, 후속 폐점 상태/조회 제외 계약을 승인·구현한 뒤 명시적으로 처리해야 한다. CSV에서 제외하거나 모든 요일을 휴무로 바꾸는 방식으로 폐점 정보를 임의 추정하지 않는다. 이 도구로 매장·관심 관계를 삭제하지 않는다.

성공한 apply마다 `store_csv_imports`에 UUID, 출처, 검수자, offset을 포함해 입력한 검수 시각의 UTC instant, 적용 시각, DB 역할, 원본 두 파일의 SHA-256, 매장 ID 배열과 매장·요일 키 배열을 남긴다. 같은 파일을 재실행하면 데이터 상태는 같고 실행 이력은 한 건 더 생성한다. 파일 원문·비밀번호·DB URL을 출력하지 않는다. 출처/검수자는 운영자가 주장하는 검수 기록이며 외부 검수 시스템 인증은 아니다. 원본 파일은 해시를 대조할 수 있도록 별도의 접근 제한 저장소에서 보관한다. 이력 테이블에는 매장 FK를 두지 않아 향후 명시적인 폐점 처리 이후에도 실행 기록을 보존한다.

## 준비와 권한.

운영 DB에는 이 작업에서 적용하지 않았다. 관리자가 명시적으로 선택한 대상 스키마에 기존 `039_stores.sql` 및 관심 API용 `041_store_favorites.sql`을 준비하고 `src/main/resources/db/manual/055_store_csv_imports.sql`을 적용한다. 055 SQL 재실행은 이력을 보존하며 기존 테이블 정의 교정은 하지 않는다. CLI는 스키마·테이블을 생성하지 않는다. 자동 DDL이 아닌 수동 SQL의 제약·키·identity 정의를 전제로 한다.

- dry-run 계정은 DB CONNECT, 스키마 USAGE, stores SELECT면 된다. READ ONLY 트랜잭션으로 CSV 계약과 부모 존재·신규/기존 행 수를 검사한다. 테이블/이력/sequence에 쓰지 않는다. apply 전용 DML·sequence 변경 권한, 사용자 추가 제약·트리거, 커밋 성공까지 보장하는 모드는 아니다.
- apply 계정은 해당 스키마 USAGE, stores와 store_business_hours SELECT/INSERT/UPDATE 및 테이블 잠금 권한, store_csv_imports INSERT, stores identity sequence SELECT 및 **ALTER 가능한 소유권/소유 역할**이 필요하다. sequence 변경을 위해 superuser를 부여할 필요는 없다. 제한된 관리 역할을 사용하고 애플리케이션 역할에 적재 권한을 추가하지 않는다. 이력 UPDATE/DELETE 권한은 적재 계정에 불필요하다.
- apply는 stores → store_business_hours 순으로 SHARE ROW EXCLUSIVE 잠금을 잡고 데이터·이력·sequence 변경을 하나의 트랜잭션에 넣는다. 조회는 계속 가능하지만 해당 테이블의 일반 INSERT/UPDATE/DELETE와 다른 apply는 기다린다. 실행은 작은 검수 배치로 나누고 쓰기 트래픽이 적은 시간에 수행한다. 잠금 대기 10초, SQL 문장 60초를 넘으면 실패하고 롤백한다. 트랜잭션 자동 재시도는 없으며 운영자가 결과를 확인한 후 재실행한다.
- sequence는 increment=1, cache=1, no cycle만 지원한다. sequence 잠금을 먼저 얻고 이미 발급한 값과 테이블 최대 ID보다 뒤로 `ALTER SEQUENCE ... RESTART`한다. 값을 뒤로 낮추지 않는다. 데이터/이력 또는 지연 제약으로 커밋이 실패하면 sequence도 롤백한다. 직접 `nextval`/`setval`을 호출하는 별도 관리 도구와 동시에 실행하지 않는다.

## 명시적인 실행.

Java 21과 Gradle Wrapper를 사용한다. 비밀번호는 선택한 환경 변수에서만 읽으며 CLI 인자/URL에 넣지 않는다. 아래는 운영 데이터가 아닌 별도 로컬 검증 대상의 예다. 호스트·포트·DB명·계정·스키마·검수 정보를 실제 승인된 대상에 맞춰 모두 지정한다. JDBC URL에는 호스트/DB 경로 및 선택 `sslmode=disable|require|verify-ca|verify-full`만 허용한다. URI 사용자 정보·password·currentSchema·options·임의 JDBC 플러그인 옵션은 받지 않는다.

```sh
export ISSUE55_LOCAL_PASSWORD=''
./gradlew storeCsv --max-workers=1 --args='--stores /absolute/reviewed/stores.csv --hours /absolute/reviewed/business-hours.csv --jdbc-url jdbc:postgresql://127.0.0.1:56555/postgres --db-user postgres --password-env ISSUE55_LOCAL_PASSWORD --schema csv_review --mode dry-run --source synthetic-review-v1 --reviewer local-reviewer --reviewed-at 2026-10-01T12:00:00+09:00'
```

dry-run 결과의 신규 매장·기존 매장·영업시간 행 수와 두 해시를 확인한다. apply는 **같은 모든 인자에서 `--mode apply`로 명시적으로 바꾼 뒤** 실행한다. 두 모드의 실행 사이 파일 또는 DB 상태가 바뀔 수 있어 dry-run 결과가 apply를 예약하지 않는다. 출력의 existingStores는 변경 여부와 관계없이 동일 ID가 있던 입력 행 수, hours는 입력 영업시간 행 수다. 성공 시 exit 0, 실패 시 exit 1이다. SQL 오류는 민감한 서버 메시지 대신 SQLState를 출력한다. 연결 10초 제한을 둔다.

## 검증.

`ISSUE55_TEST_DB_URL=jdbc:postgresql://127.0.0.1:<port>/<disposable_db>`를 지정하면 실제 PostgreSQL 테스트를 실행한다. `ISSUE55_TEST_DB_USERNAME` 기본값은 postgres, `ISSUE55_TEST_DB_PASSWORD` 기본값은 빈 문자열이다. 테스트는 UUID 스키마를 생성·정리하고 합성 자료만 사용한다. 환경 변수가 없으면 PostgreSQL 테스트를 건너뛴다.

```sh
./gradlew test --tests 'com.kitschcatch.backend.tools.storecsv.*' --max-workers=1 --console=plain
./gradlew clean test build --max-workers=1 --console=plain
```

전체 회귀에는 ISSUE27/31/39/41/43 PostgreSQL 설정도 모두 지정한다. ISSUE27은 기본 스키마를 변경하므로 다른 채팅과 공유하지 않는 전용 폐기 가능 인스턴스를 사용한다. 실제 수행 결과와 독립 리뷰 대상 SHA는 PR에 기록한다. 운영 데이터 수집·운영 DB 적용·배포·실제 지도 앱·S3/Toss 연동·푸시는 검증 대상에 포함하지 않는다.
