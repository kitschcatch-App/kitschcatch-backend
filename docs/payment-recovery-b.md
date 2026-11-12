# PG 승인 성공 후 PostgreSQL 커밋 거절과 자동 복구

추적 이슈는 [#126](https://github.com/kitschcatch-App/kitschcatch-backend/issues/126)이다. 앱 빌드의 기준 커밋은 `1c6ba551249fd66320361a4b29363b9154690f87`이다.

격리 스키마에서 대상 결제의 `SUCCESS` 전이에만 실행되는 `AFTER UPDATE` 행 제약 트리거를 설치한다. `DEFERRABLE INITIALLY DEFERRED`이므로 트리거는 커밋에서 실행된다. 트랜잭션 내부의 결제·주문·상품·시도 상태와 결제 성공 알림을 읽은 뒤 SQLSTATE `P126B`를 던진다. PostgreSQL 로그에서 같은 백엔드 PID의 오류와 `STATEMENT: COMMIT`을 대조한다.

실제 승인 API를 호출한다. PG 대역은 승인 효과를 `DONE`으로 저장하고, 별도 DB 연결에서 최초 `PROCESSING / PENDING / RESERVED / PROCESSING` 커밋을 확인하면 HTTP 200을 보낸다. 앱은 실제 복구 경로의 커밋에서 실패한다. 클라이언트 응답은 `500 COMMON_999`이며, 별도 DB 연결에서는 결제·주문·상품·시도·알림이 승인 응답 전 관측과 일치해야 한다.

트리거와 함수를 별도 연결에서 제거한 뒤 같은 JVM의 스케줄러를 기다린다. PG GET으로 `SUCCESS / PAID / SOLD_OUT / SUCCEEDED`가 확정되고 성공 알림은 구매자와 판매자에게 각각 1건이어야 한다. 동일 승인을 재전송해도 PG 승인 POST·효과·승인 시도는 각각 1회다.

## 실행

[A1 실행 환경](payment-recovery-a1.md#실행)의 실험 전용 PostgreSQL을 `log_error_verbosity=verbose`, `log_min_error_statement=error`, PID를 포함한 로그 접두사로 실행한다. 예시의 서버 로그 경로를 실제 격리 인스턴스 로그로 바꾼다.

```sh
./gradlew bootJar
python3 scripts/payment_recovery_a1.py --scenario B \
  --jar build/libs/kitschcatch-backend-0.0.1-SNAPSHOT.jar \
  --db-port 55486 --db-user a1 --db-name kitschcatch_a1 \
  --b-postgres-log /tmp/kitschcatch-b-postgres.log \
  --repeat 5 --out /tmp/kitschcatch-b-run
```

기본 복구 스캔은 30초다. `--scan-seconds 5`로 실행기를 점검할 수 있다. `postgres-commit.log`에는 대상 결제의 커밋 거절 기록을, `events.jsonl`에는 PG 승인·조회와 트리거 설치·제거 및 DB 상태를 남긴다. 앱에 장애 코드를 넣거나 DB의 재조회 시각을 수정하지 않는다.

## 2026-11-12 결과

Java 21.0.7과 PostgreSQL 14.18에서 기본 30초 스캔 20회와 5초 준비 검증 3회가 통과했다. 기본 설정은 같은 호스트의 독립 스키마 4개 묶음에서 각 5회씩 실행했다. 공용 실행기의 A1~A4도 각 1회 재검증해 통과했다. 초기 준비에서 확인한 SQLSTATE 로그 설정 문제는 PostgreSQL의 상세 오류 로그를 켜서 해결했다. 초기 기록도 보존했다.

- 전 회차에서 PG 대역은 HTTP 200 `DONE`을 보냈고 앱은 `500 COMMON_999`를 반환했다. PostgreSQL 로그의 `P126B`와 `STATEMENT: COMMIT`을 대조했다.
- 커밋 직전 트리거 안에서는 `SUCCESS / PAID / SOLD_OUT / SUCCEEDED`와 성공 알림 2건을 관측했다. 커밋 거절 뒤 별도 DB 연결에서는 승인 응답 전과 동일한 `PROCESSING / PENDING / RESERVED / PROCESSING`, 원래 예약 소유권 및 성공 알림 0건을 관측했다. 승인·완료 시각과 상태 버전도 승인 응답 전과 일치했다.
- 장애 제거 후 같은 JVM의 자동 PG GET 1회로 상태가 확정됐다. 동일 승인 재전송은 `200 SUCCESS`를 반환했다. 승인 POST·효과·시도 각각 1회와 사용자별 성공 알림 1건을 확인했다.

| 기본 주기 20회의 관측 구간 | 중앙값 | 최댓값 |
| --- | ---: | ---: |
| PG 대역의 승인 효과 기록부터 DB 반영 관측까지 | 29.130초 | 29.470초 |
| 장애 트리거 제거 확인부터 DB 반영 관측까지 | 28.922초 | 29.331초 |

DB 상태는 0.25초 간격으로 확인했다. 이 결과는 PostgreSQL이 COMMIT을 거절한 경우에 해당한다. 커밋은 성공하고 확인 응답만 유실되는 경우는 별도 시나리오다. 원자료는 작업 공간의 `build/payment-recovery-b/2026-11-12/`에 보존했다.
