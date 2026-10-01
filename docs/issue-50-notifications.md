# 이슈 #50: 기기 토큰·알림함·푸시 기반.

## 계약과 명세 접근 범위.

기준은 develop `5f6d18fb2d4d8fc1e2f435919a548c64d33c9627` 및 [이슈 #50](https://github.com/kitschcatch-App/kitschcatch-backend/issues/50)이다. [Notion API 명세](https://app.notion.com/p/341ee6172f5680679c13c4fa1e53f23a)는 커넥터 404와 웹 접근 실패로 원문을 확인하지 못했다. 아래 필드·상태·페이지 규칙은 기존 ApiResponse/JWT 계약에 맞춘 구현 가정이며 외부 명세를 수정하지 않았다.

모든 API는 기존 Bearer access JWT 인증을 거치며 기기 등록 시 실제 사용자 존재를 확인한다. 응답은 ApiResponse이고 성공은 200이다. 요청의 userId로 소유자를 지정할 수 없다.

| 메서드·경로 | 계약 |
|---|---|
| POST /api/users/me/device-tokens | JSON `{ "token": "FCM등록토큰", "platform": "IOS" }`. platform은 ANDROID/IOS/WEB이며 token은 공백 없는 ASCII 영숫자·`_:.-`으로 구성한 값으로 최대 2048자이다. 같은 사용자 반복 등록은 멱등하며 동일 토큰의 마지막 등록 계정으로 소유권을 이전한다. 클라이언트는 로그인 계정 변경 시 재등록한다. |
| DELETE /api/users/me/device-tokens | JSON `{ "token": "FCM등록토큰" }`. 자기 소유 활성 토큰만 해제한다. 없는 토큰·이미 해제·타인 소유도 200이다. 이전 계정이 뒤늦게 해제해도 새 계정의 토큰은 유지한다. 로그아웃 전에 클라이언트가 이 API를 호출해야 한다. 기존 로그아웃 API는 토큰을 직접 해제하지 않는다. |
| GET /api/notifications | page 기본 0, 범위 0..10000. size 기본 20, 범위 1..100. `notifications`, `page`, `size`, `totalElements`, `totalPages`, `unreadCount`. 생성 시각·ID 역순이며 unreadCount는 전체 개인 미읽음 수이다. 빈 목록은 200이다. |
| PATCH /api/notifications/{notificationId}/read | 양의 숫자 ID. 최초 readAt을 보존하고 반복 호출도 200이다. 타인·없는 알림 모두 404 NOTIFICATION_001이다. |
| PATCH /api/notifications/read-all | 현재 사용자의 미읽음만 갱신한다. `{ "updatedCount": N }`이며 반복 호출은 0이다. 동시 생성된 알림은 해당 UPDATE 시점 이후 미읽음으로 남을 수 있다. |

알림 항목은 `id`, `type`, `title`, `targetId`, `createdAt`, `readAt`이다. 미읽음 알림의 readAt은 null이다. CHAT_MESSAGE의 targetId는 채팅방 ID, PAYMENT_SUCCESS/PAYMENT_CANCELED는 주문번호이다. 개인 메시지 본문·이미지·결제 키를 푸시 본문에 포함하지 않는다. 제목은 종류별 고정 문구이다. 생성·읽음 시각은 기존 도메인과 같은 LocalDateTime 기준이다. offset 페이지는 목록 변화 중 항목 위치가 이동할 수 있다.

## 저장과 동시성.

`050_notifications.sql`은 PostgreSQL용 수동 SQL이다. users 테이블 생성 후 사전 적용하며 앱 시작에서 자동 적용하지 않는다. BEGIN/COMMIT 및 CREATE TABLE/INDEX IF NOT EXISTS로 같은 버전을 재실행할 수 있다. 이 SQL은 이슈 전용 새 테이블을 만들며 기존 사용자·주문·결제 스키마를 변경하지 않는다. JPA와 SQL을 함께 변경해야 한다. 운영 DB 적용은 이번 작업에서 실행하지 않았다.

- device_tokens는 token 유일 키와 현재 user_id·active·ownership_version을 저장한다. 해제 후 행을 보존한다. 기존 토큰 변경은 FOR UPDATE로 직렬화한다. 처음 등록하는 동일 토큰의 경쟁은 HQL `INSERT ... ON CONFLICT (token) DO UPDATE SET token=excluded.token`으로 기존 토큰 값을 그대로 유지한 후 행 잠금으로 처리한다. Hibernate가 PostgreSQL/H2 방언에 맞춰 변환하며, 정상적인 충돌에서 예외와 원문 토큰 로그가 발생하지 않는다.
- notifications는 `(event_key,user_id)`를 유일하게 저장한다. 같은 수신자 User 행 잠금으로 이벤트 재실행을 직렬화하고 DB 제약을 추가 방어로 사용한다. 결제의 두 수신자는 ID 오름차순으로 잠근다.
- 채팅은 `chat:{messageId}`, 결제는 `payment:{paymentId}:{type}`를 중복 기준으로 쓴다. HTTP/STOMP 텍스트 저장, 이미지 저장, 정상 결제 승인·취소, 웹훅/워커가 사용하는 PaymentRecoveryService의 승인·전체 취소 경로에서 동기 Spring 이벤트를 발행한다. 알림과 기기별 push_deliveries를 원래 트랜잭션에서 저장하므로 실패 시 원래 메시지·결제도 함께 롤백한다.
- 거래 판매자는 현재 상품 주인 대신 주문 sellerId 스냅샷을 사용한다. 채팅은 발신자를 제외한 참여자 한 명, 결제는 구매자와 판매자 모두 수신한다. 확정되지 않은 PG 결과·부분 취소·실패·조회는 완료 알림을 생성하지 않는다.
- 알림 생성 시 활성 기기만 발송 작업을 만든다. 이후 새로 등록한 기기로 과거 알림을 보내지 않는다. 토큰이 없어도 알림함은 저장한다.
- push_deliveries는 `(notification_id,device_token_id)` 유일 키, 상태, 시도 수, 다음 시각, 소유권 세대, 안전한 결과 코드, 제공자 메시지 ID를 저장한다. push_attempts는 각 완료된 시도의 번호·결과 코드·시각을 보존한다. 제공자 원문·토큰·개인 키는 로그에 남기지 않는다.
- 워커는 PostgreSQL FOR UPDATE SKIP LOCKED로 최대 20건을 각 개별 트랜잭션에서 처리한다. 커밋된 작업만 다른 트랜잭션에서 조회 가능하다. 별도 lease 상태가 없으므로 프로세스가 죽으면 DB 잠금 해제 후 다시 처리한다.
- 발송 중 토큰 행을 잠가 계정 이전·해제와 겹치지 않게 한다. 등록 계정이나 ownership_version이 달라지면 SKIPPED로 끝낸다. 계정 A→B→A 이전이나 해제→재등록도 오래된 작업을 재발송하지 않는다. HTTP 연결 2초, 개별 요청 3초로 제한하며 인증이 필요할 때 두 HTTP 호출이 한 트랜잭션에 포함된다. 계정 전환은 진행 중 호출이 끝날 때까지 기다린다.
- 외부 제공자의 성공 직후 DB 커밋 전에 장애가 나면 재시도에서 중복 발송될 수 있다. 정확히 한 번 전달을 보장하지 않는다. `data.notificationId`를 기준으로 클라이언트에서 중복 표시를 제거해야 한다. 읽음 여부로 대기 푸시를 취소하지 않는다.

## FCM 제공자와 기본 비활성 설정.

[FCM HTTP v1 OAuth 인증](https://firebase.google.com/docs/cloud-messaging/send/v1-api) 및 [공식 오류 코드](https://firebase.google.com/docs/reference/fcm/rest/v1/ErrorCode)를 기준으로 어댑터를 작성했다. 서비스 계정 RS256 JWT assertion을 OAuth endpoint로 교환하고 Firebase Messaging scope의 단기 Bearer 토큰을 캐시한다. 실제 Google 자격 증명 또는 사용자 토큰은 이번 검증에서 사용하지 않았다.

| 환경 변수 | 기본값·역할 |
|---|---|
| APP_NOTIFICATIONS_PUSH_ENABLED | false. 워커와 어댑터 모두 비활성이면 HTTP를 호출하지 않는다. |
| APP_FCM_PROJECT_ID | 빈 값. Firebase 프로젝트 ID. |
| APP_FCM_CLIENT_EMAIL | 빈 값. 서비스 계정 이메일. |
| APP_FCM_PRIVATE_KEY | 빈 값. PKCS8 PEM RSA 개인 키. 실제 줄바꿈과 리터럴 `\n`을 지원한다. 비밀 환경 변수로 주입한다. |
| APP_FCM_BASE_URL | https://fcm.googleapis.com. 로컬 대역 검증에서만 loopback HTTP로 대체한다. |
| APP_FCM_TOKEN_URL | https://oauth2.googleapis.com/token. 로컬 대역 검증에서만 loopback HTTP로 대체한다. |
| APP_NOTIFICATIONS_PUSH_SCAN_DELAY | 30s. |

FCM 2xx는 유효한 `name`이 있을 때 SENT이다. 잘못된 2xx는 재시도한다. HTTP 401은 OAuth 캐시를 무효화하며 401/403/429/5xx 및 인증·전송 예외는 재시도한다. typed FcmError UNREGISTERED + 404만 토큰을 비활성화한다. 다른 400/404는 FAILED로 끝내며 토큰을 삭제하지 않는다. 429/5xx의 Retry-After 초·HTTP 날짜를 존중하고 60초~24시간 범위로 제한한다. 기본 재시도는 60/120/240/480초이며 완료된 시도가 5회에 도달하면 FAILED로 끝낸다. 설정 오류도 원문 노출 없이 최대 5회에서 멈춘다.

FAILED의 원인을 해결한 후 필요 작업만 관리자가 `status='PENDING', attempts=0, next_attempt_at=CURRENT_TIMESTAMP`로 재설정할 수 있다. 이를 위한 공개 재발송 API는 제공하지 않는다. 이력은 지우지 않으며 SKIPPED 소유권 작업을 강제로 재발송하지 않는다. 대량 토큰 정리·보존 기간·운영 모니터링은 이후 운영 정책으로 결정해야 한다.

## 연결 범위와 남은 의존성.

#45의 배송·환불·구매 확정·정산 이벤트는 아직 연결하지 않았다. 해당 브랜치의 미커밋 코드·주문 상태·정산 코드를 복사하거나 변경하지 않았으며 develop 병합 후 상위 통합 확인 대상이다. 현재 PAYMENT_CANCELED는 기존 결제 전체 취소만 의미하며 #45의 환불 연결 완료를 뜻하지 않는다. 신규 알림 종류가 필요하면 enum·SQL CHECK·목표 ID 계약·권한/중복/롤백 검증을 함께 확장해야 한다.

실제 S3·Toss 연동, 운영 DB 적용, 운영 배포, 실제 FCM 서비스 계정 교환·발송, 기존 사용자 기기 토큰 활용과 단말 수신/클라이언트 중복 제거는 미검증이다. 기존 S3/Toss 실제 연동 미완료 상태를 유지한다. PR은 develop 대상이며 병합·자동 병합하지 않는다.

## 검증 실행.

전용 PostgreSQL 인스턴스·포트/DB 또는 별도 DB를 준비하고 아래를 실행한다. 테스트는 UUID 스키마를 생성·삭제하며 공유 스키마를 변경하지 않는다. HTTP 서버와 FCM HTTP 대역은 임의 포트를 사용한다.

```sh
ISSUE50_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55450/postgres ISSUE50_TEST_DB_USERNAME=issue50 ./gradlew test --tests '*notification*' --max-workers=1 -Dorg.gradle.jvmargs='-Xmx512m -XX:MaxMetaspaceSize=256m'
./gradlew test bootJar --max-workers=1 -Dorg.gradle.jvmargs='-Xmx512m -XX:MaxMetaspaceSize=256m'
```

PostgreSQL 환경 변수가 없으면 NotificationPostgresTest는 건너뛴다. FcmPushGatewayTest는 자격 증명 없이 로컬 HTTP 서버·합성 토큰·테스트 RSA 키로 수행한다. 다른 이슈의 PostgreSQL 테스트는 각 전용 환경 변수 없이 건너뛰며 그 결과를 PostgreSQL 실행 성공으로 보고하지 않는다.

## 수행 결과와 독립 리뷰.

- #50 관련 검증 16개를 모두 수행해 통과했다. 전용 PostgreSQL HTTP/SQL/이벤트/동시성 9개, 합성 RSA 키·로컬 HTTP FCM 검증 6개, H2 방언의 토큰 반복등록/전환 검증 1개이다. 동시 등록 로그에 원문 토큰이 없는 것도 assertion으로 확인했다.
- 최종 전체 `test bootJar` 결과는 총 482개, 수행 성공 408개, 실패/오류 0개, 건너뜀 74개이며 bootJar도 성공했다. 다른 이슈의 사용자 프로필·매장·관심 매장·거래 내역 PostgreSQL/SQL 검증은 해당 환경 변수 부재로 건너뛰었다. #50 전용 실제 PostgreSQL 검증은 수행했다.
- GPT 6.1 Sol High 독립 리뷰는 최초 `72102ab994d1f5bb41c98dd57337922821972d1d`의 전체 diff·권한·입력·동시성·회귀·SQL·테스트를 확인했다. 최초 문서 인증 문구 P3 및 추가 확인된 동시 등록 토큰 로그 노출 P2를 수정했다. 수정된 6개 파일과 회귀 영향을 `1757cf761172555d21eef71a95846e25cb2dca2c`에서 재검토한 결과 잔여 findings=0이다. 리뷰어는 직접 테스트/DB 호출 없이 코드와 실행 XML을 독립 확인했으며 실제 테스트는 구현자가 수행했다.
- PostgreSQL FK 잠금 교착 가설은 실제 bootJar의 Hibernate 7.2.7.Final이 사용하는 FOR NO KEY UPDATE와 FK KEY SHARE의 호환성을 확인하여 결함에서 제외했다. 운영 자격 증명·실제 푸시·#45 통합을 검증했다고 보고하지 않았다.
- `git diff --check`를 통과했다. 저장소에 GitHub Actions workflow가 없어 CI 성공으로 보고하지 않는다. 로컬 HTTP 검증·전체 테스트·빌드와 운영 적용·실제 단말 수신은 별도 증거이다.
