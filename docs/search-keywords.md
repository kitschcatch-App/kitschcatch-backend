# 최근·인기·연관 검색어

이슈 #79, 브랜치 feat/79, 기준 develop d9fa461. 백엔드 API이며 사용자 ID는 access JWT에서 가져온다.

## HTTP 계약

| 요청 | 동작 |
|---|---|
| POST /api/search/records | JSON {"keyword":"포켓몬"}을 확정 기록한다. 최초·갱신 모두 200. data는 id, keyword, lastSearchedAt이다. |
| GET /api/search/recent | data.items에 본인 최근 검색어를 반환한다. |
| DELETE /api/search/recent/{recordId} | 본인 기록만 삭제한다. 없거나 타인 기록이어도 200이다. |
| DELETE /api/search/recent | 본인 최근 기록만 전체 삭제한다. |
| GET /api/search/popular?limit=10 | data에 timezone, windowStartDate, windowEndDate, generatedAt, items[{rank,keyword}]를 반환한다. |
| GET /api/search/related?keyword=포켓몬&limit=10 | data.keyword 및 items[{keyword}]를 반환한다. |

빈 목록은 items: []다. 삭제 성공은 기존 ApiResponse 방식의 success:true이며 null data는 생략한다. limit은 1~20이다. 인증 오류는 기존 401, 빈 검색어·1000자 초과·제어문자·잘못된 limit은 400이다.

상품 검색 GET은 기존 읽기 전용 계약을 유지한다. 앱은 검색 버튼 또는 최근·인기·연관어 선택으로 검색을 확정하고 상품 검색이 정상 응답한 뒤 기록 POST를 한 번 호출한다. 타이핑·페이지 이동·결과 새로고침에는 기록하지 않는다. 결과 0건도 기록 가능하다. 상품 결과와 기록은 별도 HTTP 요청이므로 기록 실패가 상품 결과의 실패를 의미하지 않는다. 서버는 접수된 기록 요청을 집계하며 실제 화면 열람을 증명하지 않는다.

## 규칙

표시 문자열은 strip 후 보존한다. 중복 문자열은 NFC 및 Locale.ROOT 소문자 변환으로 만들며 SHA-256 키로 유일성을 관리한다. 내부 공백·기호는 보존한다. strip 후 Java 길이 1000자 이내를 허용하며 소문자 변환 결과도 1000자 이내여야 한다. ISO 제어문자는 거절한다. 최근 기록은 마지막 검색 시각·ID 역순 최대 20개다. 시각은 UTC ISO 8601이며 저장 전에 마이크로초로 절삭한다.

인기는 Asia/Seoul 기준 오늘 포함 최근 7개 날짜를 사용한다. (날짜, 사용자, 검색어)당 1행이며 같은 날 재검색·최근 목록 삭제 후 재검색도 인기 점수가 증가하지 않는다. 서로 다른 사용자 최소 3명의 검색어만 노출한다. count(*) 역순, count(distinct user_id) 역순, 검색어 키 순으로 정렬한다. 영문 인기 표시 문자열은 정규화된 소문자다. 사용자별 기록과 원시 점수는 반환하지 않는다. 조건 미달이면 빈 배열이다. 여러 계정 조작까지 차단하는 장치는 아니다.

연관어는 검수 가능한 방향성 관계를 사용하며 priority 오름차순·대상 키 순이다. 원본 자신을 제외하고 관계 유일 제약으로 중복을 막는다. 역방향은 자동 생성하지 않는다. 비활성 관계를 제외하고 미등록 입력은 빈 배열이다. 상품 존재·개인화·오타 교정을 보장하지 않는다. 초기 SQL은 포켓몬→포켓몬 카드/피카츄/포켓몬 피규어 3개이며 운영 기획 검수 후 선택 적용한다. 재실행 시 기존 우선순위·활성 여부를 덮어쓰지 않는다.

## 스키마와 배포

신규 DB에는 users 테이블 이후 `db/manual/079_search_keywords.sql`을 애플리케이션 배포 전에 적용한다. 동일 정의 재실행을 검증하며 기존 다른 정의의 테이블을 자동 교정하지 않는다. 연관 데이터가 필요하면 `079_search_related_seed.sql`을 별도 적용한다. JPA 테스트 테이블과 수동 SQL을 함께 제공하며 운영의 사용자 외래 키·CHECK는 수동 SQL을 기준으로 한다.

기록 POST는 활성 사용자 행을 잠근 뒤 최근 upsert·20개 초과 삭제·일별 insert-on-conflict를 한 트랜잭션으로 처리한다. 삭제도 같은 사용자 잠금을 사용한다. 회원 탈퇴는 같은 잠금 아래 최근·일별 기록을 함께 지운다. 기록 실패 시 최근·일별 변경은 모두 롤백된다.

설정 기본값은 app.search.recent-limit=20(1~100), popular-days=7(1~90), minimum-users=3(최소 3)이며 시작 시 검증한다. app.search.cleanup-enabled=true, cleanup-delay-ms=60000이다. 한 번에 1000개씩 집계 범위 밖의 일별 행을 삭제하고, 정리가 지연되어도 조회 날짜 조건으로 기간 밖 기록을 제외한다. 신규 비밀 환경변수는 없다. 검색어·사용자 기록 전체를 로그에 출력하지 않는다.

인기 집계는 조회 시 PostgreSQL에서 실행하며 별도 캐시·순위 변화 스냅샷은 없다. 운영 규모의 쿼리 지연 시간과 실행 계획은 아직 측정하지 않았다. 최근/일별/연관 조회 인덱스를 제공한다. PostgreSQL의 ON CONFLICT/RETURNING을 사용하므로 저장 HTTP 검증은 실제 PostgreSQL에서 실행한다.

## 검증 범위

정규화 단위 테스트와 격리 PostgreSQL HTTP 테스트를 제공한다. DDL과 시드 2회 적용, 최근 중복·20개 제한·사용자별 삭제, 하루 중복 및 자정 경계·최소 사용자·기간 만료, 연관어 방향성·비활성, 인증·입력 오류, 동일 검색어 동시 저장, 저장 롤백, 기록/탈퇴 경쟁 및 상품 GET 읽기 전용을 검증한다. CI의 ISSUE79_TEST_DB_URL/USERNAME/PASSWORD 설정으로 필수 실행한다.

실제 앱 호출·서버 배포는 로컬 테스트와 구분한다.

2026-11-05 Java 21·격리 PostgreSQL 14와 CI와 동일한 DB 환경변수(ISSUE79 포함)에서 `./gradlew clean build --no-daemon --console=plain --max-workers=1` 성공. 전체 1,304개 테스트가 실패·오류·스킵 없이 통과했다. 검색어 정규화 2개와 PostgreSQL 통합 7개를 포함한다. `git diff --check` 통과. 기존 외부 결제·이미지 통합은 테스트 대역을 사용했으며 실제 앱과 개발 서버 배포는 수행하지 않았다.
