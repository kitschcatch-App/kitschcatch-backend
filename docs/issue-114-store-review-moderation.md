# 운영자 매장 리뷰 조치 (#114)

신고 심사자와 매장 리뷰 조치자 권한을 모두 가진 활성 계정이 요청할 수 있다. 본인 리뷰나 본인이 신고한 건은 조회·조치에서 제외한다. 권한 회수 후 동일 토큰으로 요청하면 403이다.

| 메서드 | 경로 | 응답 `data` |
| --- | --- | --- |
| GET | `/api/admin/store-reviews?storeId=1&hidden=true&page=0&size=20` | 리뷰 배열. 빈 결과는 `[]`. |
| GET | `/api/admin/store-reviews/{id}` | `review`, `content`, `images`, 만료 전 `relatedReports` 최대 20건. |
| GET | `/api/admin/store-reviews/{id}/moderation-history?page=0&size=20` | 최신 조치부터 배열. |
| PATCH | `/api/admin/store-reviews/{id}/moderation` | `review`, `history`, `replayed`. |

페이지는 0부터 시작하고 크기는 최대 100이다. 숨김은 같은 리뷰를 대상으로 한 만료 전 `IN_REVIEW` 또는 `RESOLVED` 신고와 연결한다. 신고 근거와 접수 시점의 내용은 기존 신고 상세로 확인한다. 현재 리뷰는 별도 상세로 확인한다.

```json
{
  "action": "HIDE",
  "reportId": 123,
  "reason": "신고 증거 확인",
  "expectedVersion": 4,
  "requestId": "d3093567-cdba-4d41-a97a-cf62706c6a82"
}
```

해제는 `action=RESTORE`로 보내고 `reportId`는 생략한다. 서버는 마지막 숨김의 근거를 이어서 기록한다. 요청을 그대로 재시도하면 같은 이력과 현재 리뷰 상태를 돌려준다. 다른 조치나 새 버전으로 다시 보내면 새 UUID를 사용한다. 409이면 상세를 재조회하고 조치를 재확인한다. 무권한은 `REPORT_008` 계열 403, 유효한 신고가 없으면 `REPORT_005` 404다.

모바일은 `moderationStatus`와 `publiclyVisible`을 따로 표시한다. 숨김 이후 공개 목록·평점·사진 모음을 다시 조회한다. 작성자가 이미 삭제했거나 탈퇴한 리뷰는 숨김 해제 후에도 공개 여부가 false다. 숨김 작성자 수정은 409이며 본인 삭제는 가능하다.

비공개 사진은 권한 있는 상세에서만 사용하고 URL을 영구 저장하지 않는다. 기존에 발급한 URL은 S3 만료 시각까지 유효할 수 있다. 삭제된 숨김 리뷰와 탈퇴 사용자의 숨김 리뷰는 각각 삭제일·탈퇴일부터 신고 보관 설정만큼 증거를 조회할 수 있다. 만료 후 운영자 상세는 증거를 반환하지 않으며 사진은 기존 정리 작업으로 삭제한다. 최소 조치 이력은 원 신고의 만료 삭제 이후에도 남는다.

개발 DB에 `114_store_review_moderation.sql`을 적용하고 스키마 계약을 확인한다. 권한 변경은 `scripts/report-reviewer.sql`과 `scripts/store-review-moderator.sql`을 각각 사용한다. 두 스크립트 모두 `reviewer_id`, `enabled`, `reason`을 받는다. 테스트 종료 후 권한을 회수하고 403을 확인한다.

Java 21과 PostgreSQL 14에서 리뷰 관련 38개 테스트가 통과했다. 신규 HTTP 테스트로 이중 권한, 숨김과 재전송, 공개 집계, 작성자 삭제, 신고 만료 후 해제, 권한 회수를 확인했다. 실제 개발 DB 권한 변경과 S3 증거 파일 조회는 개발 배포 후 검증한다.
