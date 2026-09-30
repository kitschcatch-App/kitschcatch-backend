# 관심 매장 API

이슈 [#41](https://github.com/kitschcatch-App/kitschcatch-backend/issues/41), 브랜치 `feat/41`. 최신 develop `16b4971`에서 시작했다. 관심 매장 API 3개와 전국·주변·상세 조회의 사용자별 `favorited`를 구현했다. 실제 매장 자료는 추후 사용자가 제공하며 테스트에서는 가상 fixture만 사용했다.

## HTTP 계약

세 API 모두 `Authorization: Bearer {accessToken}`이 필요하다. JWT 사용자 ID만 사용하며, 프로필 미등록 사용자도 이용할 수 있다. 다른 사용자 ID를 전달해 조회·변경 대상을 바꿀 수 없다.

| 메서드 | URL | 성공 응답 |
| --- | --- | --- |
| POST | `/api/stores/{storeId}/favorites` | 200, `storeId`, `favorited=true`, `favoriteCount`. |
| DELETE | `/api/stores/{storeId}/favorites` | 200, `storeId`, `favorited=false`, `favoriteCount`. |
| GET | `/api/users/me/favorite-stores?page=0&size=20` | 200, `content`, `page`, `size`, `totalElements`, `totalPages`. |

등록·해제에는 요청 본문이 없다. 이미 등록된 매장을 다시 등록하거나 미등록 매장의 관심을 해제해도 성공한다. 등록 반복은 최초 등록 시각과 관계 ID를 유지한다. 해제 후 다시 등록하면 최근 항목으로 이동한다. 존재하지 않는 매장에 대한 등록·해제는 모두 404다.

```json
{
  "success": true,
  "data": { "storeId": 20, "favorited": true, "favoriteCount": 1 }
}
```

`favoriteCount`는 해당 매장에 등록한 **전체 사용자 수**다. 트랜잭션에서 변경을 반영한 뒤 실제 관계 행을 집계한다. 별도 카운터를 증감하지 않아 반복 요청으로 누적되지 않는다. 다른 사용자의 동시 변경과 응답 이후의 변경 때문에 이 값이 계속 고정되는 것은 아니다.

## 내 관심 목록

- `page`는 0~10000, 기본 0. `size`는 1~100, 기본 20. 선택 숫자 파라미터를 생략하거나 빈 값으로 보내면 기본값을 적용한다.
- 등록 시각 내림차순, 동일 시각이면 관심 관계 ID 내림차순이다. 매장 ID 순서와 다르다.
- 빈 목록은 `content=[]`, `totalElements=0`, `totalPages=0`. 마지막 페이지를 넘으면 내용만 비고 전체 건수는 유지한다.
- 페이지 사이에 등록·해제가 일어나면 항목 위치가 바뀔 수 있다. 전체 페이지를 하나의 스냅샷으로 고정하지 않는다.
- `favorited`는 항상 true다. WGS84 좌표와 전화번호를 함께 반환한다.
- 현재 위치를 입력받지 않아 `distanceMeters=null`이다. 거리 기반 조회에는 `/api/stores/nearby`를 사용한다. 매장 이미지 모델이 없어 `thumbnailUrl=null`이다. 가상의 거리·이미지 주소는 반환하지 않는다.

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
        "favorited": true,
        "distanceMeters": null,
        "thumbnailUrl": null
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

## 기존 조회와 클라이언트 연결

`GET /api/stores`, `GET /api/stores/nearby`, `GET /api/stores/{storeId}`의 각 매장에 boolean `favorited`를 추가했다. 기존 필드·정렬·반경 정책은 그대로다. 이 값은 요청 사용자의 값이므로 여러 사용자 사이에 응답 캐시를 공유하면 안 된다.

앱은 관심 버튼의 목표 상태에 따라 POST 또는 DELETE를 호출한다. 성공 후 해당 매장 ID의 목록·마커·상세 상태를 갱신하고 내 관심 목록을 다시 조회한다. SDK 키나 위치 권한 없이도 관심 등록과 목록을 사용할 수 있다.

## 오류

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | COMMON_001 | page·size 형식이나 범위 오류. |
| 400 | COMMON_002 | storeId가 정수가 아니거나 0 이하, Long 범위를 넘음. |
| 401 | AUTH_004 | 토큰 누락, 잘못된 토큰, refresh token으로 접근. |
| 404 | USER_001 | 인증 토큰의 사용자가 DB에 없음. |
| 404 | STORE_001 | 등록·해제 대상 매장이 없음. |
| 500 | COMMON_999 | 처리하지 못한 서버 오류. |

기존 공통 오류 형식 `{ "success": false, "error": { "code": "STORE_001", "message": "매장을 찾을 수 없습니다." } }`를 사용한다. 중복 등록은 409 오류가 아니다.

## DB와 동시 요청

`store_favorites`에 ID, 사용자 FK, 매장 FK, 등록 시각을 저장한다. `(user_id, store_id)` 유일 제약으로 중복 관계를 차단한다. 사용자·매장 삭제 시 관계를 정리하는 `ON DELETE CASCADE`, 최신순 목록 인덱스 `(user_id, created_at DESC, id DESC)`, 매장 집계 인덱스 `(store_id)`를 둔다.

등록·해제는 동일 트랜잭션에서 기존 `UserRepository.findByIdForUpdate`로 사용자 행을 먼저 잠근다. 같은 사용자의 두 등록 또는 등록·해제 경합을 순서대로 처리한다. 다른 사용자 행은 잠그지 않는다. 같은 사용자의 프로필 갱신과는 잠금을 공유하므로 그 사용자의 변경 요청이 몰리면 대기할 수 있다. JVM 메모리 잠금이 아니므로 여러 서버에서도 DB가 조정한다.

내 목록은 매장까지 fetch join하고 별도 count 쿼리로 페이지 집계를 구한다. 영업시간은 로드하지 않는다. 전국·주변 조회는 페이지 내 ID만 대상으로 관심 여부를 한 번에 조회한다. 상세는 해당 ID 하나를 조회한다.

배포 전 기존 사용자 스키마와 [039_stores.sql](../src/main/resources/db/manual/039_stores.sql) 이후 [041_store_favorites.sql](../src/main/resources/db/manual/041_store_favorites.sql)을 대상 스키마에 적용해야 한다. 최신 매장 조회도 이 테이블을 참조하므로 **SQL을 먼저 적용한 뒤 애플리케이션을 배포**한다. 기존 테이블이나 행을 변경·삭제하지 않는 추가 스키마다.

SQL은 최초 생성과 동일 정의의 재실행을 지원한다. 이미 다른 정의로 생성한 테이블의 누락 제약을 자동 교정하지는 않는다. `ddl-auto=update`보다 먼저 수동 SQL을 적용하고 운영에서는 전체 스키마를 검증한다. 이번 작업은 격리 DB만 사용했으며 운영 적용은 하지 않았다.

## 검증 결과

2026-09-30, Java 21과 격리 PostgreSQL 14 및 H2에서 실행했다.

- 새 HTTP 계약: H2 32개, PostgreSQL 32개. 등록·해제·사용자 분리, 본인 외 ID 주입 무시, 프로필 미등록, 빈 목록·페이지·최신순, 입력 오류, JWT, 기존 세 API의 관심 여부, Swagger를 확인했다.
- 8개 동시 등록, 8개 동시 해제, 등록·해제 혼합 8개 요청을 실제 HTTP로 실행했다. 모두 200이며 중복 행이 없고 다른 사용자의 관계가 보존됐다.
- 20개 항목 조회에서 JPA statement 수가 4개 이하이고 엔티티 추가 fetch와 컬렉션 fetch가 0인 것을 확인했다. 주변 조회는 이와 별도로 기존 JDBC 거리 쿼리 1개를 사용한다.
- 수동 SQL 검증 7개: 데이터가 있는 상태의 재실행, 유일·FK·필수값 제약, 양쪽 부모 삭제 시 정리, 인덱스 생성. PostgreSQL HTTP 테스트도 Hibernate 생성 매장 테이블을 제거하고 실제 039·041 SQL로 만든 테이블에서 실행했다.
- 기존 매장 계약 116개를 포함해 관련 테스트 **187개, 실패 0, 오류 0, 건너뜀 0**.

- `ISSUE27/31/39/41` PostgreSQL 설정을 모두 지정하고 `./gradlew clean test build --console=plain` 실행: **473개, 실패 0, 오류 0, 건너뜀 0**, 빌드 성공.

실행 설정과 환경 변수는 [별도 문서](store-favorites-environment.md)에 정리했다. 실제 CSV 적재, 운영 배포, 앱 지도 화면 검증, 매장 이미지 기능은 포함하지 않았다.

## Notion 대조와 차이

참조한 원문: [등록](https://app.notion.com/p/3c1ee6172f568017855be054edd7bc95), [해제](https://app.notion.com/p/3c1ee6172f56808b893affe17720cb9f), [내 목록](https://app.notion.com/p/3c1ee6172f5680519df1d463fde32ae5).

- 원문의 경로, 등록·해제 `storeId/favorited/favoriteCount`, 내 목록의 페이지 구조와 `id/name/address/distanceMeters/thumbnailUrl` 필드를 유지했다.
- 원문의 중복 등록 409 `FAVORITE_002`는 이번 이슈에서 정한 반복 요청 성공 정책에 따라 200으로 바꿨다.
- 내 목록 예시의 거리·이미지 실값은 현재 데이터와 입력 계약으로 제공할 수 없어 null이며, 좌표·전화번호·관심 여부를 추가했다.
- Swagger와 이 문서를 구현 계약으로 갱신했다. 이번 작업에서는 Notion 원문을 수정하지 않았다.

## 작업 기록 보관

- Obsidian `키치캐치/관심 매장 API - 작업 기록.md`와 `키치캐치/관심 매장 API - 환경 변수와 앱 설정.md`를 각각 저장하고 재확인했다. 기존 매장 지도 기록에도 관심 여부 연동을 반영했다.
- 격리 PostgreSQL의 임시 이슈 스키마가 0개 남았음을 확인하고 DB 프로세스를 정상 종료했다.
- 운영 배포나 운영 DB SQL 적용은 수행하지 않았다. 로컬 검증과 GitHub 상태 검사는 별도다.
