# 반품 요청 사진 첨부

구매자는 반품이나 전액 환불을 신청할 때 검증이 끝난 사진을 최대 5장 첨부할 수 있습니다. 사진은 선택 항목입니다. JPEG, PNG, WebP를 사진당 최대 5MiB까지 받습니다.

| 순서 | 메서드·경로 | 앱 동작 |
| --- | --- | --- |
| 1 | `POST /api/orders/{orderId}/refund-images/upload-urls` | `{"images":[{"fileName":"damage.jpg","contentType":"image/jpeg","fileSize":1250000}]}`를 보냅니다. 응답 사진마다 `imageId`, `uploadUrl`, `expiresAt`, `headers`가 있습니다. |
| 2 | S3 `PUT {uploadUrl}` | 5분 안에 반환된 헤더와 일치하는 원본 바이트를 업로드합니다. 앱의 인증 토큰은 S3에 전송하지 않습니다. 파일 길이를 업로드 요청에 정확히 전달합니다. |
| 3 | `POST /api/orders/{orderId}/refund-images/{imageId}/complete` | PUT 성공 후 본문 없이 호출합니다. 서버가 실제 바이트와 형식을 확인하고, 방향을 보정한 뒤 EXIF 없는 PNG로 저장합니다. 응답의 `READY`는 첨부 가능함을 의미합니다. |
| 4 | `POST /api/orders/{orderId}/refunds` | 기존 `reason`, `amount`와 함께 `imageIds`를 표시할 순서대로 제출합니다. 필드를 생략하거나 null·빈 배열을 보내면 사진 없이 접수합니다. |

`orderId`는 공개 주문 번호입니다. `amount`는 주문의 실제 청구액과 일치해야 합니다. 같은 사유·금액·사진 ID 목록과 순서로 재시도하면 기존 환불 결과를 받습니다. 사진을 바꾸거나 순서를 변경하면 `REFUND_002`(409)입니다. 사진을 업로드했더라도 반품이 접수되기 전까지 판매자에게 표시되지 않습니다.

`GET /api/orders/{orderId}/refunds`의 `images`와 당사자 주문 상세의 `refund.images`는 `[{ "imageId": 501, "imageUrl": "...", "expiresAt": "...", "sortOrder": 0 }]` 형태입니다. 사진이 없으면 빈 배열입니다. 조회 URL은 5분간 유효하며 만료 시 API를 다시 호출합니다. 응답을 내려받을 수 있는 주체는 구매자·판매자 및 환불 조회 권한을 가진 반품 운영자입니다. 서명 URL은 임시 접근 권한이므로 원문을 로그나 영구 식별자로 저장하지 않습니다.

| 오류 | 앱 동작 |
| --- | --- |
| `REFUND_IMAGE_001` 400 또는 요청 검증 400 | 파일 형식·크기·장수와 중복 ID를 확인합니다. 서버는 입력 이미지의 실제 형식, 최대 1,200만 픽셀 및 변당 8,192픽셀을 검증합니다. 검증본은 긴 변 최대 1,600픽셀로 저장합니다. |
| `REFUND_IMAGE_002` 404 | 이미지가 존재하는지와 동일 주문에서 발급받았는지 확인합니다. |
| `REFUND_IMAGE_003` 409 | S3 PUT을 완료하고 완료 API를 호출합니다. PUT 재시도에서 412를 받으면 이전 업로드가 이미 성공했을 수 있으므로 완료 API로 확인합니다. |
| `REFUND_IMAGE_004` 409 | 발급 후 24시간이 지나 첨부할 수 없습니다. 새 URL을 발급받아 다시 업로드합니다. 정리 이후에는 404를 반환합니다. |
| `REFUND_IMAGE_005` 429 | 잠시 후 재시도합니다. URL 발급은 구매자당 최근 24시간 최대 100장입니다. |
| `REFUND_IMAGE_006` 503 | 저장소에 일시적으로 접근할 수 없습니다. 완료 또는 조회를 재시도합니다. 접수·승인 응답에서 발생했다면 기존 요청을 조회하거나 동일한 내용으로 재시도합니다. |

배송 후에는 반품 담당 운영자가 실물 수령을 확인하고 승인해야 결제 취소를 실행합니다. 배송 전에는 기존 전액 결제 취소 흐름을 적용합니다. 접수와 이미지 연결은 한 DB 트랜잭션으로 처리하며 PG 호출은 커밋 후에 실행합니다. 승인 처리에 필요한 사유는 주문에서 읽기 때문에 이미지 서명 실패가 PG 취소 호출을 막지 않습니다.

## 적용 순서

1. `orders`의 반품 컬럼이 적용된 PostgreSQL에 `src/main/resources/db/manual/097_refund_images.sql`을 백엔드 배포 전에 실행합니다. 기존 주문·환불 값의 변경이나 사진 데이터 이관은 필요하지 않습니다.
2. `APP_S3_BUCKET`에 비공개 버킷을 지정합니다. 실행 역할에 `refund-uploads/*`와 `refund-images/*`의 `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`, `s3:DeleteObjectVersion` 및 두 접두사의 버전 목록을 확인할 `s3:ListBucketVersions` 권한을 부여합니다. 웹에서 직접 PUT하는 경우 반환된 서명 헤더와 PUT을 버킷 CORS에 맞춥니다.
3. 백엔드를 배포한 뒤 앱에서 URL 발급 → PUT → 완료 → 반품 접수 순서로 연결합니다. 운영자 권한은 기존 `APP_REFUNDS_OPERATOR_USER_IDS`를 사용합니다.
4. 정리 작업은 기본 활성화되며 `APP_REFUND_IMAGES_CLEANUP_ENABLED`와 `APP_REFUND_IMAGES_CLEANUP_DELAY_MS`로 제어합니다. 24시간이 지난 미첨부 행과 해당 S3 객체를 제거하고, 첨부된 사진은 임시 원본만 제거합니다. 버전 관리 버킷에서도 정확한 키의 버전과 삭제 마커를 정리합니다. 접수된 증빙은 기존 반품 기록과 함께 유지하며, 보존 기간과 파기 절차는 운영 정책으로 정합니다. 최종 사진 접두사 전체에 일괄 만료 규칙을 적용하면 접수된 증빙도 삭제됩니다.
