# 관심 매장 API 구현 계획

- 이슈: [#41](https://github.com/kitschcatch-App/kitschcatch-backend/issues/41).
- 브랜치: `feat/41`, 기준: 최신 develop `16b4971` (#40 병합).
- 범위: 관심 매장 등록·해제·내 목록과 기존 전국·주변·상세 조회의 `favorited`. 실제 매장 CSV 적재, 네이버 지도 앱 작업, 운영 배포는 제외한다.

## 계약과 결정

| API | 계약 |
| --- | --- |
| `POST /api/stores/{storeId}/favorites` | 본문 없음. 200, `storeId`, `favorited=true`, `favoriteCount`. 반복 등록도 성공. |
| `DELETE /api/stores/{storeId}/favorites` | 본문 없음. 200, `storeId`, `favorited=false`, `favoriteCount`. 반복 해제도 성공. |
| `GET /api/users/me/favorite-stores` | 본인의 목록만 조회. `page=0`, `size=20`, 최대 page 10000·size 100. 최근 등록 순과 ID 역순으로 안정적으로 정렬. |

- Bearer access token에서 사용자 ID를 얻는다. 입력으로 다른 사용자의 ID를 받지 않는다. 프로필 미등록 사용자도 사용할 수 있다.
- 기존 Notion [등록](https://app.notion.com/p/3c1ee6172f568017855be054edd7bc95), [해제](https://app.notion.com/p/3c1ee6172f56808b893affe17720cb9f), [목록](https://app.notion.com/p/3c1ee6172f5680519df1d463fde32ae5)과 대조한다. 등록 원문의 409 `FAVORITE_002`는 이번 이슈의 반복 요청 성공 계약으로 대체한다. 최종 계약은 저장소 문서와 Swagger에 기록한다.
- `favoriteCount`는 해당 매장을 등록한 전체 사용자 수를 변경 후 집계한 값이다. 다른 사용자 요청과 경합하면 응답 이후 달라질 수 있다. 별도 카운터를 유지하지 않는다.
- 잘못된 매장 ID는 400 `COMMON_002`, 페이지 오류는 400 `COMMON_001`, 없는 매장은 404 `STORE_001`, 없는 사용자는 404 `USER_001`, 인증 오류는 401 `AUTH_004`.
- 기존 조회의 정렬·페이지·거리 정책은 유지하며 `favorited`만 추가한다.

## 저장·동시성·성능

- `store_favorites`: ID, user_id, store_id, created_at. 사용자·매장 복합 유일 제약, 두 FK의 ON DELETE CASCADE, 사용자별 최신순 목록 인덱스와 매장별 집계 인덱스.
- 등록·해제 트랜잭션에서 기존 사용자 행의 쓰기 잠금을 먼저 획득한다. 같은 사용자의 변경만 직렬화하여 중복 등록·등록/해제 경합을 처리한다. DB 유일 제약은 우회 쓰기도 방어한다.
- 내 목록은 매장을 함께 가져오는 페이지 쿼리로 영업시간을 로드하지 않는다. 전국·주변 조회는 현재 페이지 ID 집합의 관심 여부를 한 번에 조회하여 N+1을 피한다.
- `041_store_favorites.sql`을 기존 사용자 스키마와 `039_stores.sql` 이후 적용한다. 격리 PostgreSQL에서 적용·재실행·유일 제약·FK·삭제 정리·동시 HTTP 요청을 검증한다.

## 커밋·검증·완료

계획 → 모델 → 수동 SQL → 등록·해제 → 내 목록 → 기존 조회 연동 → HTTP·Swagger → HTTP 계약 테스트 → PostgreSQL 검증 → 문서의 논리 단위로 커밋한다.

H2와 PostgreSQL에서 실제 HTTP·JWT 요청으로 사용자 격리, 잘못된 입력, 반복/동시 요청, 정렬·페이지, 세 기존 API의 관심 여부를 검증한다. 관련 테스트 후 전체 테스트·빌드를 실행한다. 완료 후 Obsidian 작업 기록과 환경 변수 노트를 따로 작성하고 템플릿을 사용한 develop 대상 Open PR을 생성한다.
