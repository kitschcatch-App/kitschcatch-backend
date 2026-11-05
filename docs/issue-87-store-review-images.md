# 매장 리뷰 사진 API (#87).

사진 없는 기존 리뷰 요청은 그대로 사용할 수 있다. 사진은 비공개 S3에 업로드하고, 실제 이미지 검증을 통과한 `imageId`만 리뷰에 연결한다. 프론트엔드 구현 및 개발 서버 배포는 이 PR의 범위에 포함하지 않는다.

## API 사용 순서.

모든 엔드포인트는 Bearer 인증이 필요하다.

1. `POST /api/stores/{storeId}/review-images/upload-urls`에 파일 정보를 보낸다.

   ```json
   {"images":[{"fileName":"photo.jpg","contentType":"image/jpeg","fileSize":123456}]}
   ```

   `data.images[]`에 `imageId`, `uploadUrl`, `expiresAt`, `headers`가 반환된다. 동일한 파일명도 서로 다른 임시 키를 사용한다. 원본 파일명은 저장하지 않는다.

2. `uploadUrl`에 **원본 파일 바이트**를 PUT한다. JSON이나 multipart가 아니다. 응답 `headers`의 Content-Type, Content-Length, If-None-Match 값을 그대로 사용한다. PUT에 앱 Bearer 토큰을 보내지 않는다. URL은 5분 유효하고, `If-None-Match: *`로 같은 키 덮어쓰기를 방지한다. 네트워크 오류로 PUT 성공 여부가 불명확하면 완료 API를 호출해 확인한다. 412는 이미 객체가 있다는 뜻이다. 다른 파일로 바꾸려면 새 URL을 발급받는다.

3. `POST /api/store-review-images/{imageId}/complete`를 호출한다. 요청 본문은 없다. 서버가 업로드 객체의 길이·Content-Type·실제 포맷·해상도를 검사하고 이미지를 다시 인코딩한다.

   ```json
   {"success":true,"data":{"imageId":123,"state":"READY","width":1200,"height":1600}}
   ```

   완료된 사진의 재호출은 같은 결과를 반환한다. 이미 연결된 사진은 `ATTACHED`다. 연결 전 유효기간은 URL 발급부터 24시간이며, 만료한 사진은 새로 업로드한다. 완료 처리 중 일시적인 저장소 오류는 503, 디코딩 동시 처리 한도는 429로 응답한다. 잠시 후 같은 ID로 재시도할 수 있다.

4. 기존 리뷰 작성·수정 요청에 `imageIds`를 추가한다. 배열 순서가 표시 순서이며 `sortOrder`는 0부터 시작한다.

   ```json
   {"rating":5,"content":"사진과 함께 남기는 매장 후기입니다.","imageIds":[123,124]}
   ```

   ```json
   {"version":0,"imageIds":[124,123]}
   ```

   작성 시 생략/null은 사진 없음이다. 수정 시 생략/null은 기존 사진 유지, `[]`는 모두 제거, 배열은 전체 사진 교체·정렬이다. 사진만 수정할 수 있으며 별점·본문 수정과 동일한 `version` 충돌 방지를 적용한다. 자신의 사진이며 같은 매장에서 완료한 사진만 허용한다. 중복 ID, 다른 사용자·매장의 사진, 미완료·만료·제거된 사진은 연결할 수 없다. 기존 연결 사진은 24시간 이후에도 유지·정렬할 수 있다. 제거한 사진을 다시 쓰려면 새로 업로드한다.

## 조회와 제한.

- 기존 리뷰 작성·수정·단건·목록·내 리뷰 응답의 `images`는 `[{"id":123,"imageUrl":"...","sortOrder":0}]` 형태다. 사진이 없으면 빈 배열이다. 목록은 사진을 한 번에 조회한다.
- `GET /api/stores/{storeId}/review-images?page=0&size=20`은 사진 단위 페이징을 제공한다. `content[]` 항목은 `id`, `reviewId`, `authorId`, `authorNickname`, `imageUrl`, `sortOrder`, `createdAt`이며 전체 응답은 `page`, `size`, `totalElements`, `totalPages`를 포함한다. `createdAt`은 리뷰 작성 시각이다. 리뷰 작성 시각 내림차순 → 리뷰 ID 내림차순 → 사진 순서로 정렬한다. 페이지 크기는 1~100이다.
- 삭제·숨김 리뷰와 탈퇴 작성자 사진은 공개 목록에서 제외된다. 숨김 리뷰의 사진 교체는 409다. 숨김 리뷰를 삭제해도 원본 최종 사진은 보존한다. 숨김 리뷰는 내 리뷰에서 본인만 조회할 수 있다.
- S3 공개 설정은 사용하지 않는다. 최종 조회 URL은 기존 `APP_S3_DOWNLOAD_URL_TTL`을 사용하며 기본 15분이다. URL은 저장하지 말고 만료 시 API를 재조회한다. 이미 발급한 URL은 만료되거나 객체가 삭제될 때까지 접근할 수 있다.
- JPEG/JPG, PNG, WebP만 지원한다. HEIC·GIF·SVG는 지원하지 않는다. 확장자와 선언 MIME 및 실제 포맷이 일치해야 한다. 요청당 최대 10장, 리뷰당 최대 10장, 사용자당 최근 24시간 URL 발급 최대 100장이다. 실패하거나 사용하지 않은 URL도 발급 한도에 포함한다.
- 입력 파일은 최대 5MiB(5,242,880바이트), 한 변 최대 8,192px, 전체 12,000,000픽셀 이하로 제한한다. 디코딩은 서버 인스턴스당 최대 2개를 동시에 처리한다.
- EXIF 방향을 반영하고 긴 변 최대 1,600px로 축소한 정적 PNG로 저장한다. GPS·촬영 정보·원본 메타데이터는 최종 PNG에 포함하지 않는다. 여러 프레임이 있는 포맷은 첫 프레임만 사용하며 움직임은 보존하지 않는다. 재인코딩 결과도 5MiB를 초과하면 거부한다.

## 상태와 실패 복구.

`PENDING → READY → ATTACHED`로 진행한다. 제거한 사진은 `DELETE_PENDING`, 정리 성공은 `DELETED`다. DB에는 ID·소유자·매장·객체 키·크기·순서·상태·만료 및 재시도 시각을 기록한다.

- 변경 요청은 사용자 행을 먼저 잠그고 사진 행을 ID 오름차순으로 잠근다. 리뷰 작성/수정/탈퇴와 같은 사용자 잠금 순서를 사용한다. 정리 작업은 사진 행만 잠그고 최신 상태를 재검사한다.
- 완료 처리는 임시 객체를 읽어 별도의 최종 키에 서버가 PNG를 조건부 저장한다. 최종 키는 URL 발급 때 DB에 이미 기록하므로 저장 성공 후 DB 롤백이 발생해도 정리 대상에서 빠지지 않는다. 재시도 시 기존 최종 파일의 SHA-256·길이·타입이 일치해야 성공으로 처리한다.
- 연결하지 않은 임시·최종 파일은 24시간 만료 후 정리한다. 연결된 사진은 임시 파일만 정리한다. 제거된 사진의 물리 삭제는 제거 시각과 발급 후 1시간 중 늦은 시각부터 시작한다. 사용 중인 PUT URL과 삭제가 겹치지 않도록 여유 시간을 둔다.
- 리뷰 삭제는 즉시 공개에서 제외하고 물리 삭제는 별도로 진행한다. 탈퇴 작성자의 사진도 정리 대상에 포함한다. 숨김 리뷰는 신고 증거 보존을 위해 최종 객체를 유지하며, 별도의 보존 기간·관리자 폐기 기능은 후속 정책에서 다룬다.
- 정리 실패는 재시도 횟수와 다음 시각을 DB에 저장한다. 120초부터 지수 백오프하며 이후 약 17시간 간격으로 재시도한다. 배치당 20건이고 후보 선택 후 사진 행을 다시 잠근다. S3 예외 본문·URL·자격 증명은 로그에 남기지 않는다. `DELETED` 행은 기록으로 유지한다.
- 버전이 활성화된 버킷도 정확히 일치하는 객체 키의 모든 버전과 삭제 마커를 제거한다. 같은 접두사의 다른 키는 삭제하지 않는다. 버킷 Object Lock/보존 정책 등으로 삭제가 차단되면 재시도 로그를 확인해야 한다.

## 개발 서버 반영 전 준비.

1. `085_store_reviews.sql` 적용 여부 확인 후 `src/main/resources/db/manual/087_store_review_images.sql`을 적용한다. 사진 테이블과 리뷰 소유권 복합 FK, 순서 고유 제약, 상태·크기·객체 키 제약과 인덱스를 추가한다. 동일 SQL 재적용을 지원한다. 새 JAR 실행 전에 적용해야 한다. 애플리케이션만 롤백할 때 테이블을 삭제하지 않는다.
2. 기존 비공개 버킷과 서버 역할을 사용한다. 다음 최소 범위 권한이 필요하다.

   | 대상 | 권한 |
   | --- | --- |
   | `arn:aws:s3:::<bucket>/store-review-uploads/*` | `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`, `s3:DeleteObjectVersion` |
   | `arn:aws:s3:::<bucket>/store-review-images/*` | `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`, `s3:DeleteObjectVersion` |
   | `arn:aws:s3:::<bucket>` | `s3:ListBucketVersions`를 위 두 접두사로 제한 |

   미업로드 객체의 404를 정확하게 구분하려면 위 접두사에 제한한 `s3:ListBucket`도 필요하다. 해당 권한이 없으면 S3의 403을 저장소 오류 503으로 처리할 수 있다. 서버 외 사용자에게 자격 증명이나 직접 버킷 권한을 주지 않는다. SSE-KMS 버킷이라면 기존 KMS 키 정책도 별도 확인한다. 이번 PR은 실제 IAM·버킷 설정을 변경하지 않는다. 권한 근거는 AWS의 [객체 삭제](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html), [버전 목록](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectVersions.html), [객체 조회](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html) 문서를 참고한다.
3. `APP_STORE_REVIEW_IMAGES_CLEANUP_ENABLED=true`로 정리 작업을 활성화한다. 기본값은 false이고, 활성화하지 않으면 삭제·미연결 파일의 저장 비용이 계속 발생한다. `APP_STORE_REVIEW_IMAGES_CLEANUP_DELAY_MS` 기본값은 60,000이다. 정리 상태와 재시도 로그를 운영에서 확인한다.
4. 임시 접두사 `store-review-uploads/`에는 24시간 연결 기한보다 긴 기간(예: 3일)의 만료 및 비현재 버전 정리 수명 주기를 보조 수단으로 설정할 수 있다. 최종 사진 접두사 전체에 일괄 만료 규칙을 설정하면 연결된 사진도 삭제되므로 사용하지 않는다. 이번 PR은 수명 주기 정책을 적용하지 않는다.
5. 브라우저에서 PUT할 경우 해당 개발 Origin·PUT 메서드·발급한 헤더를 허용하는 CORS가 필요하다. 네이티브 Android의 HTTP 업로드는 브라우저 CORS와 다르다.
6. 배포 뒤 테스트 계정으로 URL 발급 → PUT → 완료 → 리뷰 연결 → GET 서명 조회 → 사진 제거 및 정리를 확인한다. 이 PR의 로컬 검증은 실제 PostgreSQL과 HTTP, 실제 이미지 디코딩, AWS SDK 서명 계산 및 모의 S3 응답을 사용한다. 실제 개발 버킷 호출·IAM 검증·모바일 앱 종단간 테스트는 배포 후 검증 사항이다.

## 검증 방법.

Java 21 및 PostgreSQL 14에서 `ISSUE87_TEST_DB_URL`, `ISSUE87_TEST_DB_USERNAME`, `ISSUE87_TEST_DB_PASSWORD`를 설정하고 `./gradlew test --tests '*StoreReviewImage*' --tests '*S3StoreReviewImage*'`를 실행한다. CI의 PostgreSQL 환경에도 87번 테스트 변수를 포함한다. DB 테스트는 독립 스키마를 생성하고 종료 후 제거한다.

소유권·매장 범위·만료·중복 연결·순서 변경·버전 충돌·삭제·숨김·탈퇴·정리 실패 및 재시도·DB 롤백 후 최종 객체 복구·동시 완료/수정/탈퇴/정리, SQL 재적용·제약을 검증한다. 이미지 테스트는 실제 JPEG/PNG/WebP, EXIF 8방향 보정·제거, MIME 위조·크기 제한을 확인한다. S3 테스트는 실제 GET 서명 검증, 조건부 PUT 서명, 바이트/메타데이터 불일치와 객체 버전 삭제를 확인한다.

전체 회귀 검증: Java 21·PostgreSQL 14 환경의 `clean build` 성공, 1,367건 통과, 실패·오류·건너뜀 0건. 새 사진 기능 검증은 HTTP/PostgreSQL 12건, 이미지 코덱 12건, S3 어댑터 5건이다.
