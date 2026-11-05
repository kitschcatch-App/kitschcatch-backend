# 매장 상세 정보

인증된 사용자가 호출하는 기존 GET /api/stores/{storeId}의 data에 아래 필드를 추가했다. 기본 정보·영업시간·관심 여부와 400/401/404 계약은 기존과 같다.

| 필드 | 의미 |
| --- | --- |
| description | 검수한 일반 텍스트 최대 1,000자. 미등록은 null. |
| thumbnailUrl | 정렬된 첫 사진의 임시 GET URL. 미등록은 null. |
| images | 사진 id, imageUrl, sortOrder, caption, credit, sourceUrl. 사진이 없으면 빈 배열. caption, credit, sourceUrl은 각각 미등록 시 null. |
| amenities | code, label, status, note를 가진 고정 순서의 배열. |

편의시설 코드 순서는 PARKING, RESTROOM, WIFI, ELEVATOR, WHEELCHAIR_ACCESS, PET_FRIENDLY다. AVAILABLE은 제공 확인, UNAVAILABLE은 미제공 확인, UNKNOWN은 미확인이다. note에 공용 여부나 이용 조건을 기록한다. 건물에 엘리베이터가 있어도 해당 매장의 휠체어 접근 가능 여부는 별도로 확인한다.

사진은 매장당 최대 10장이며 sortOrder는 0~9 중 중복되지 않는 값이다. thumbnailUrl은 첫 번째 images 항목의 imageUrl과 동일하다. 이미지 바이트를 내려받을 때 URL 전체를 그대로 GET한다. 앱 JWT는 S3에 보내지 않는다. APP_S3_DOWNLOAD_URL_TTL 기본값은 15분이며 서버의 역할 자격 증명 만료 시 더 일찍 만료될 수 있다. 만료 시 상세를 다시 조회해 최신 URL로 한 번 재시도한다. 사진의 캐시 키로는 URL 대신 사진 id를 쓸 수 있다. 서명 URL 전체를 로그나 영구 데이터에 저장하지 않는다.

상세 읽기는 PostgreSQL의 동일한 스냅샷 안에서 매장·영업시간·사진·편의시설·관심 여부를 읽는다. 사진 서명을 만들 때 객체별 HEAD/GET을 호출하지 않는다. 인증 응답은 공유 캐시에 저장하지 않는다. 전국·주변·관심 매장 목록과 리뷰 사진에는 이번 필드를 추가하지 않았다.

## 등록 도구

storeContent Gradle 작업은 독립 실행 도구다. 적용 대상 PostgreSQL과 스키마를 반드시 지정하며 비밀번호는 지정한 환경 변수에서 읽는다. 공유 개발 환경에 연결할 때는 sslmode=verify-full과 해당 서버의 신뢰 루트를 사용한다.

1. 매장 ID와 해당 DB의 매장명·주소를 대조한다. 이전 데이터나 다른 환경에서 같은 ID가 다른 매장에 배정된 경우 이름과 주소가 일치하지 않으면 중단한다.
2. UTF-8 JSON에 reviewer, ISO 8601 reviewedAt, stores를 작성한다. 실제 배치 예시는 [검수 자료](../data/store-content/2026-11-05/details.json)에 있다.
3. description을 추가하거나 변경할 때 descriptionSourceUrl에 확인 근거를 적는다. 편의시설마다 code, status, sourceUrl을 지정한다. note는 선택이며 최대 300자다. 미확인 항목을 배열에 넣지 않는다. 출처는 공개 HTTPS URL을 사용한다.
4. 사진은 자체 촬영·매장 제공·사용 허락이 확인된 파일만 JPEG/PNG/WebP로 준비한다. 환경의 비공개 버킷에 stores/{storeId}/{uuid}.jpg, .png 또는 .webp 형태의 새 키로 올리고 올바른 Content-Type을 설정한다. 같은 키를 덮어쓰지 않는다. 새 사진은 아래 예시처럼 입력한다.

~~~json
{
  "objectKey": "stores/123/00000000-0000-4000-8000-000000000001.png",
  "sortOrder": 0,
  "caption": "매장 입구",
  "credit": "매장 제공",
  "sourceUrl": null,
  "rightsBasis": "매장 운영자가 앱 내 사용을 허락한 파일. 내부 확인 기록 참조.",
  "sha256": "<검수한 원본 파일의 64자리 소문자 SHA-256>"
}
~~~

사진이 있는 실행은 --s3-bucket 및 --s3-region을 추가한다. 도구는 저장소를 변경하기 전에 지정 버킷에서 사진을 읽고 길이(최대 5 MiB), Content-Type, 파일 시그니처, SHA-256을 확인한다. 사진 업로드와 조회 권한은 별도로 관리한다. 사진을 업로드한 뒤 DB 반영에 실패했다면 해당 실행에서 새로 올린 객체를 참조 여부 확인 후 정리한다.

~~~sh
./gradlew storeContent --args="--file data/store-content/2026-11-05/details.json \
  --jdbc-url $STORE_CONTENT_JDBC_URL --db-user $STORE_CONTENT_DB_USER \
  --password-env STORE_CONTENT_DB_PASSWORD --schema public --mode dry-run"
~~~

dry-run은 읽기 전용으로 검사하며 대상 ID, 변경할 소개 수·사진 목록 수·편의시설 목록 수, 파일 SHA-256을 출력한다. 적용 시에는 같은 옵션으로 --mode apply --expected-sha256 <dry-run에서 확인한 해시>를 사용한다. 대상 매장을 ID 순으로 잠근 뒤 매장명·주소를 다시 확인하고, 모든 변경과 등록 이력을 한 트랜잭션에서 커밋한다.

| 입력 | 적용 |
| --- | --- |
| 매장 또는 필드 생략. | 기존 데이터 유지. |
| description: null. | 소개 삭제. |
| images: []. | 사진 연결 삭제. S3 객체는 유지. |
| amenities: []. | 확인된 시설 정보 삭제. 응답은 모두 UNKNOWN. |
| 비어 있지 않은 배열. | 해당 매장의 배열을 새 목록으로 교체. 같은 객체 키는 정렬이 바뀌어도 사진 ID 유지. |

적용 뒤 store_content_imports에 해시·대상 ID·검수자·검수 및 적용 시각이 남는다. 동일 파일을 재적용하면 변경 수는 0이고 적용 이력은 새로 남는다. 기존 storeCsv 재실행은 소개·사진·편의시설을 건드리지 않는다.

## 개발 배포 및 확인

1. 대상 환경에서 기존 매장·영업시간·관심 관계 수와 변경 대상의 상세 필드를 백업한다. 새 SQL 083_store_details.sql을 먼저 적용한다. 이 SQL은 기존 매장 ID와 행을 보존하며, 이전 서버도 추가 컬럼과 테이블이 있는 상태에서 동작한다.
2. 개발 서버 역할의 s3:GetObject가 대상 버킷의 stores/* 경로에 적용되는지 확인한다. 필요하면 해당 접두사의 읽기 권한을 추가한다. 버킷 비공개 및 공개 접근 차단을 유지한다.
3. 새 JAR를 배포해 인증된 상세 조회에서 기존 매장과 미등록 응답을 확인한다. 검수 파일을 dry-run 후 적용하고 소개·편의시설 수와 기존 데이터 보존을 대조한다.
4. 사용 권한이 확인된 사진을 준비한 경우 실제 서버가 발급한 URL을 GET하여 HTTP 200, Content-Type, 파일 해시를 확인한다. 서명 없는 요청은 차단되고 만료 후 새 상세 응답으로 재발급되는지 검사한다. 사진 등록용 계정으로 읽기에 성공한 것만으로 서버 역할의 접근이 확인된 것은 아니다.
5. 앱 롤백이 필요하면 이전 JAR로 복구한다. 잘못 적용한 콘텐츠는 대상 매장의 이전 소개·목록을 입력으로 작성해 되돌리고 이전 객체를 재연결한다. 기존 S3 객체는 참조와 복구 필요성이 정리된 뒤 삭제한다.

이 PR에는 배포 전 검증용 테스트와 검수한 첫 입력 파일이 포함된다. 개발 DB, 서버, IAM 적용과 실제 S3 다운로드 검증은 배포 단계에 속한다.

2026-11-05 로컬 Java 21 및 격리 PostgreSQL 14에서 Gradle build를 실행했다. 테스트 1,330개가 통과했고 실패·오류·건너뜀은 0개다. 실제 검수 JSON으로 storeContent dry-run → apply → dry-run을 실행해 소개 30건과 편의시설 16건(11개 매장)을 등록했고, 마지막 dry-run에서 변경 건수가 0임을 확인했다. 매장 30개와 영업시간 194건이 유지됐다. 사진 URL의 AWS GET 서명은 테스트 자격 증명으로 검증했다.
