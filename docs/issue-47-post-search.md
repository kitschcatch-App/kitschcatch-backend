# #47 상품 목록 검색 계약.

기존 `GET /api/posts`의 `ApiResponse<Page<PostResponse>>` 응답·이미지·판매자 정보를 유지한다. 목록 전용 `PostSearchQuery`, `PostSearchService`, `PostSearchRepository`를 사용하며 기존 상품 CRUD·주문·결제·판매자별 목록은 변경하지 않는다.

## 계약 근거와 가정.

Notion 원문 `341ee6172f5680679c13c4fa1e53f23a`는 2026-10-01 커넥터 조회에서 object_not_found(404)이어서 읽지 못했다. 기존 enum·상품 목록 응답·다른 목록의 페이지 정책과 GitHub #47을 기준으로 아래 계약을 정했다. 정렬 허용값·검색어 정규화·가격 경계는 이번 구현의 명시적 가정이며 외부 명세는 수정하지 않았다.

| 입력 | 계약. |
| --- | --- |
| status | `ON_SALE`, `RESERVED`, `SOLD_OUT`. 생략하면 모든 판매 상태. |
| keyword | 제목 OR 설명의 대소문자 무시 부분 문자열. 양끝 공백 제거, 빈 검색어는 필터 없음. 최대 1000 UTF-16 코드 단위. `%`, `_`, `!`는 문자 그대로 검색. DB lower에 따른 언어별 대소문자 처리는 DB collation에 영향받는다. |
| category | `ANIME_MANGA`, `GAME`, `GOODS`, `COSPLAY`, `BOOK`, `MUSIC_VIDEO`, `ETC` 또는 기존 라벨 `애니/만화`, `게임`, `굿즈`, `코스프레`, `서적`, `음반/영상`, `기타`. |
| condition | `NEW`, `LIKE_NEW`, `USED`, `DAMAGED`. |
| minPrice / maxPrice | 0~Long.MAX_VALUE의 정수. 양끝 포함. 둘 다 있을 때 minPrice <= maxPrice. 한쪽만 지정 가능. |
| sort | `LATEST`(기본), `PRICE_ASC`, `PRICE_DESC`. 가격 동률은 생성 시각 DESC, ID DESC. LATEST도 생성 시각 DESC, ID DESC. |
| page / size | 0~10000(기본 0) / 1~100(기본 20). DB OFFSET 최대 1000000, LIMIT 최대 100. |

모든 필터는 AND로 결합하며 deletedAt이 있는 상품은 항상 제외한다. 필터 enum과 sort는 대소문자를 구분하고 빈 값도 오류다. page/size의 빈 값은 Spring 기본값, 선택 가격의 빈 값은 미지정으로 처리한다. keyword만 양끝 공백을 제거한다. 미등록 상태를 조회하면서 예약 만료나 상품 상태를 바꾸지 않는다.

유효한 기존 사용자의 access JWT가 필요하다. 누락·잘못된 JWT·refresh JWT·존재하지 않는 사용자 JWT는 401 AUTH_004다. 필터·페이지·숫자 형식/범위·가격 역전·검색어 길이 오류는 400 COMMON_001이다. 기존 postId 형식 오류는 400 COMMON_002를 유지한다. 빈 결과·범위 밖 페이지는 200이며 content=[]와 전체 개수를 반환한다.

예: `GET /api/posts?status=ON_SALE&category=GOODS&condition=NEW&keyword=키링&minPrice=0&maxPrice=20000&sort=PRICE_ASC&page=0&size=20`.

## SQL과 동시성.

JPA Criteria로 지정된 조건만 추가하고 값은 매개변수로 전달한다. 작성자는 단일 join fetch, 이미지는 기존 BatchSize(20)로 읽으며 컬렉션 fetch join에 의한 메모리 페이지 제한을 피한다. 조건과 count에 같은 predicate를 적용한다. 읽기 전용 REPEATABLE_READ 트랜잭션으로 사용자 존재 확인·상품 페이지·count·이미지를 동일 스냅샷에서 읽는다. 목록 조회가 거래 잠금을 얻거나 외부 S3·PG를 호출하지 않는다. 이미지 URL 생성만 기존 로컬 설정을 사용한다.

독립 HTTP 요청 사이의 스냅샷은 공유하지 않는다. 상품 삽입·가격/상태 변경 중 OFFSET 페이지를 이동하면 이전 요청과의 중복·누락은 가능하다. ID 동률 규칙은 데이터가 변하지 않는 페이지에서 순서를 안정화한다. 대량 데이터의 부분 문자열 검색·count·깊은 OFFSET 성능은 이번 기능 검증에서 측정하지 않았고 새 인덱스나 운영 SQL 적용은 포함하지 않는다.

## 검증 기록.

검증 완료 후 실제 실행 명령·결과·독립 리뷰를 아래에 기록한다.

S3/Toss 실제 연동, 운영 DB 적용·배포·실제 푸시/메시지는 사용자 요청에 따라 미완료 상태를 유지한다.
