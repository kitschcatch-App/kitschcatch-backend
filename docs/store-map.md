# 매장 지도 조회 API

이슈 [#39](https://github.com/kitschcatch-App/kitschcatch-backend/issues/39)의 전국 목록·주변 검색·상세 API다. 네이버 지도 SDK는 클라이언트가 사용하고 백엔드는 저장된 매장 좌표와 운영 정보를 반환한다. 실제 매장 수집·적재는 사용자 요청으로 이번 범위에서 제외했다. 운영 데이터가 없는 DB에서는 목록이 비어 있는 것이 정상이다.

## 공통 계약

- 세 API 모두 `Authorization: Bearer {accessToken}`이 필요하다. 프로필 미등록 사용자도 사용할 수 있다.
- 좌표는 WGS84 십진수 `latitude`(위도), `longitude`(경도) 순서다. 네이버 `LatLng(latitude, longitude)`에 대응한다.
- #41에서 각 매장 응답에 현재 요청 사용자의 `favorited` boolean을 추가했다.
- 조회는 읽기 전용이다. 요청자의 현재 위치를 DB에 저장하지 않는다.
- `page`는 0~10000(기본 0), `size`는 1~100(기본 20)이다. 선택 숫자 파라미터는 생략하거나 빈 값이면 기본값을 사용한다.
- 데이터 갱신이 페이지 요청 사이에 일어나면 결과가 달라질 수 있다. 여러 페이지 전체를 하나의 시점으로 고정하는 계약은 아니다.

## 전국 목록

`GET /api/stores?region=서울&page=0&size=20`

`region` 생략 시 전국이며 제공하면 정확히 일치하는 지역만 조회한다. 표준 약칭은 서울·부산·대구·인천·광주·대전·울산·세종·경기·강원·충북·충남·전북·전남·경북·경남·제주다. 빈 문자열·공백·정식 행정구역명 등 다른 값은 거부한다. ID 오름차순이며 영업시간은 목록에서 불러오지 않는다.

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 20,
        "name": "예시 매장",
        "address": "예시 주소",
        "latitude": 37.57,
        "longitude": 126.98,
        "phone": null,
        "favorited": false
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

등록 매장이 없으면 `content=[]`, `totalElements=0`, `totalPages=0`이다. 마지막 페이지를 넘겨 요청하면 `content=[]`이며 전체 건수·페이지 수는 유지한다.

## 주변 매장

`GET /api/stores/nearby?latitude=37.5665&longitude=126.978&radiusKm=3&page=0&size=20`

위도(-90~90)와 경도(-180~180)는 필수다. 숫자 형식, NaN·Infinity, 범위를 검증한다. 반경은 1·3·5km만 허용하며 기본값은 1km다.

```json
{
  "success": true,
  "data": {
    "stores": [
      {
        "id": 20,
        "name": "예시 매장",
        "address": "예시 주소",
        "latitude": 37.57,
        "longitude": 126.98,
        "distanceMeters": 427,
        "favorited": false
      }
    ],
    "page": 0,
    "size": 20,
    "hasNext": false
  }
}
```

- 평균 지구 반지름 6,371,008.8m를 사용한 Haversine 구면 직선거리다. 도보·차량 경로 거리나 타원체 측량 거리가 아니다.
- 위도 인덱스로 후보를 줄인 뒤 DB에서 거리를 계산하고 반경 이내만 반환한다. 경도 경계(±180도)와 극지에서도 검색된다.
- 실제 거리, ID 순으로 정렬하고 응답할 때만 미터 단위로 반올림한다. 반경 경계의 부동소수점 오차에 한해 0.000001m를 허용한다.
- 같은 좌표의 서로 다른 매장을 각각 반환한다. 최대 `size+1`개를 DB에서 읽어 `hasNext`를 결정한다.
- 클라이언트는 `hasNext=true`이면 같은 조건으로 `page`를 늘린다. 위치·반경이 바뀌면 `page=0`부터 다시 조회하고 이전 결과를 초기화한다.
- 빈 결과는 200, `stores=[]`, `hasNext=false`다. 한 페이지가 반경 전체의 매장 수를 뜻하지 않는다.
- 현재는 위도 B-tree 인덱스와 표준 SQL을 사용한다. 실제 운영 규모에서 실행 계획과 지연을 측정한 후 필요하면 공간 인덱스를 도입한다.

## 상세와 영업시간

`GET /api/stores/20`

목록의 기본 필드에 `businessHours` 배열을 더한다. 요일은 MONDAY부터 SUNDAY까지 정렬한다. 시각은 한국 매장 현지 시각(Asia/Seoul)의 `HH:mm` 문자열이다.

```json
{
  "success": true,
  "data": {
    "id": 20,
    "name": "예시 매장",
    "address": "예시 주소",
    "latitude": 37.57,
    "longitude": 126.98,
    "phone": null,
    "favorited": false,
    "businessHours": [
      {"dayOfWeek": "MONDAY", "openTime": "11:00", "closeTime": "20:00", "closed": false},
      {"dayOfWeek": "FRIDAY", "openTime": "20:00", "closeTime": "02:00", "closed": false},
      {"dayOfWeek": "SUNDAY", "openTime": null, "closeTime": null, "closed": true}
    ]
  }
}
```

휴무일은 `closed=true`와 두 시간 null로 저장한다. 시간 미등록은 해당 요일 항목이 없으며, 모두 미등록이면 빈 배열이다. 연락처 미등록은 `phone=null`이다. `closeTime < openTime`은 다음 날 종료, 같은 시각은 24시간 영업을 뜻한다. 임시 휴무·공휴일 예외나 현재 영업 중 여부 계산은 이번 범위가 아니다.

## 오류

| HTTP | 코드 | 조건 | 메시지 |
| --- | --- | --- | --- |
| 400 | COMMON_001 | 지역 또는 페이지 형식·범위 오류 | 입력값이 올바르지 않습니다. |
| 400 | COMMON_002 | 매장 ID 형식 오류 또는 0 이하 | 잘못된 요청입니다. |
| 400 | STORE_002 | 필수 좌표 누락, 잘못된 숫자·범위·반경 | 위도, 경도 또는 조회 반경이 올바르지 않습니다. |
| 401 | AUTH_004 | access token 누락·오류 | 인증 토큰이 올바르지 않습니다. |
| 404 | STORE_001 | 존재하지 않는 양의 매장 ID | 매장을 찾을 수 없습니다. |
| 500 | COMMON_999 | 처리하지 못한 서버 오류 | 서버 내부 오류가 발생했습니다. |

복수 입력이 잘못됐을 때 어느 오류가 먼저 반환되는지는 보장하지 않는다. 오류 응답은 `{ "success": false, "error": { "code": "STORE_001", "message": "매장을 찾을 수 없습니다." } }` 형식이다.

## DB 적용과 추후 CSV 관리

운영 DB에 적용하지 않았다. 배포 전에 [039_stores.sql](../src/main/resources/db/manual/039_stores.sql)을 해당 DB의 대상 스키마에서 한 번 적용한다. 동일한 스키마에서 재실행해도 기존 행은 보존된다. 테이블·시간별 유일 제약·FK와 위도·경도·영업시간 입력 제약을 포함한다.

이 스크립트는 최초 생성과 동일 스키마의 재실행용이며, 이미 다른 정의로 만들어진 테이블을 교정하는 스크립트는 아니다. `local`의 기존 `ddl-auto=update`로 먼저 테이블을 생성하면 수동 SQL의 CHECK 제약이 자동 추가되지 않는다. 처음부터 수동 SQL로 생성하거나 별도 스키마를 사용해야 한다. 운영에서는 자동 DDL 대신 적용한 스키마를 기준으로 검증한다.

실제 매장 자료는 운영자가 검수한 CSV로 추후 관리한다. 이번 PR에는 실제 데이터 파일, 자동 적재기, 운영 DB 쓰기, 가상의 운영 seed를 넣지 않았다. 다음 데이터 적재 작업에서 확정할 파일 계약은 다음과 같다.

- 매장 파일 헤더: `id,name,region,address,latitude,longitude,phone`.
- 영업시간 파일 헤더: `store_id,day_of_week,open_time,close_time,closed`.
- UTF-8, 첫 행 헤더, 쉼표가 들어간 필드는 CSV 인용 규칙을 적용한다. 매장 ID는 양의 정수이며 운영자가 유지하는 식별자다. 같은 좌표만으로 중복 판정하지 않는다.
- 필수 항목은 이름·표준 지역·주소·WGS84 좌표다. 전화번호 미상은 null로 적재한다. 미확인 영업시간을 휴무로 간주하지 않는다.
- 동일 ID의 내용 갱신, 요일별 중복, 폐점 처리, 출처·최종 검수 기록, 트랜잭션과 실패 행 처리 정책을 적재 작업에서 구현한다. 명시적 ID를 넣을 때 identity sequence도 최댓값 뒤로 맞춘다.

## 클라이언트 연결

1. 위치 권한을 받을 수 없으면 전국/지역 목록으로 탐색한다.
2. 위치가 있으면 `nearby`로 조회하고 응답 좌표를 네이버 SDK의 위도·경도 순서로 전달한다. 마커 식별에는 매장 ID를 사용한다.
3. 마커 선택 시 상세 API로 연락처와 영업시간을 조회한다.
4. API 인증 오류는 서비스 토큰 흐름으로 처리한다. SDK의 키/앱 등록 오류는 지도 SDK 인증 설정에서 처리한다.

키와 실행 설정은 [별도 환경 변수 문서](store-map-environment.md)에 정리했다. 관심 매장 API 3개는 #41에서 구현했다. 매장 관리 API와 실제 앱의 지도 표시·키·위치 권한 검증은 후속 작업이다.

## 검증 결과

2026-09-30, 격리 로컬 PostgreSQL 14 및 H2에서 검증했다.

- H2 실제 HTTP 계약 49개, 수동 SQL로 다시 만든 PostgreSQL 실제 HTTP 계약 49개, SQL 재실행·제약·FK·삭제 연동 18개: **116개 통과**.
- 1·3·5km 경계, 경계 밖 0.2m 제외, 반올림 전 정렬, 중복 좌표, 날짜 변경선·양극, 페이지 최대 크기와 다음 페이지, 입력 오류, 인증, 지역 필터, 영업시간 정렬·null, Swagger를 확인했다.
- H2의 곱셈 매개변수 정수 추론으로 경계 매장이 누락되는 문제를 발견해 거리 상수를 `DOUBLE PRECISION`으로 명시했다. H2와 PostgreSQL의 공통 HTTP 검증으로 수정 결과를 확인했다.
- `ISSUE27_TEST_DB_*`, `ISSUE31_TEST_DB_*`, `ISSUE39_TEST_DB_*`를 격리 PostgreSQL에 연결하고 `./gradlew clean test build --console=plain` 실행: **402개, 실패 0, 오류 0, 건너뜀 0**, 빌드 성공.
- 운영 DB 적용·운영 데이터 적재·실제 네이버 SDK 앱 화면 검증은 수행하지 않았다. 로컬 테스트 통과가 배포 완료를 의미하지 않는다.

## 명세 출처

- [전국 매장 목록](https://app.notion.com/p/3c1ee6172f56803593dbdd086721e236).
- [주변 매장 검색](https://app.notion.com/p/3c1ee6172f5680fc97baf2ba535f1d0d).
- [매장 상세](https://app.notion.com/p/3c1ee6172f5680ceaa60c5cd6d9db48b).
- [네이버 좌표](https://navermaps.github.io/maps.js.ncp/docs/naver.maps.LatLng.html), [마커](https://navermaps.github.io/maps.js.ncp/docs/tutorial-2-Marker.html).

## 명세 동기화와 PR

- Notion의 전국 목록·주변 검색·상세 명세에 인증, 입력 범위, 페이지와 거리 계산, 영업시간 해석 및 오류를 반영했다. 각 페이지를 다시 열어 저장된 내용을 확인했다. 주변 응답 예시는 `page`, `size`, `hasNext`와 좌표에 맞는 `distanceMeters=427`을 포함한다.
- 작업 기록과 환경 변수 문서를 Obsidian `키치캐치` 폴더에 각각 저장했다. Obsidian CLI는 비활성화되어 보관함의 Markdown 파일로 저장하고 내용을 재확인했다.
- [PR #40](https://github.com/kitschcatch-App/kitschcatch-backend/pull/40): `feat/39` → `develop`, 2026-09-30 병합 확인. 저장소 PR 템플릿에 맞춰 구현·테스트·제외 범위를 기록했다.
- 격리 PostgreSQL의 임시 매장·프로필 스키마가 0개 남았음을 확인하고 테스트 DB 프로세스를 정상 종료했다.
- 로컬 테스트와 빌드는 통과했다. PR 생성 시 GitHub 상태 검사 항목은 없었으며 운영 배포는 수행하지 않았다.

## 관심 매장 연동

#41의 [관심 매장 계약](store-favorites.md)에 따라 `favorited`가 추가됐다. 기존 조회도 `store_favorites`를 읽으므로 041 SQL을 애플리케이션보다 먼저 적용한다.
