# 두 JVM의 결제 복구 lease 인계와 늦은 결과 거절

추적 이슈는 [#126](https://github.com/kitschcatch-App/kitschcatch-backend/issues/126)이다. 앱 빌드는 `e64edfe`의 애플리케이션 코드다.

실제 주문·승인 API로 미확정 결제를 만든다. A의 스케줄러가 lease를 확보하고 PG GET 응답 또는 오류를 받은 뒤, 저장 메서드의 첫 줄에서 루프백 디버거로 멈춘다. DB에서 A의 현재 token과 기한을 확인하고 A에만 `SIGSTOP`을 보낸다. B는 같은 DB와 스키마를 검증 모드로 열고, 실제 45초 lease가 만료된 뒤 새 token으로 선점한다. B의 GET 응답은 새 token을 DB에서 확인한 뒤 보낸다.

| 조건 | A가 보류한 결과 | B가 커밋한 결과 |
| --- | --- | --- |
| `late-done` | PG `DONE`. | 나중에 조회한 `CANCELED`, 잔액 0. |
| `late-error` | PG 조회 HTTP 503 오류. | 나중에 조회한 `DONE`. |

B의 트랜잭션이 끝난 뒤 A를 `SIGCONT`하고 디버거에서 재개한다. A의 이전 token이 lease 거절 분기의 return에 도달했는지 확인한다. 그 분기에서 보유한 결제 시도 행의 잠금이 풀린 뒤 두 관측의 상태·버전·확정 시각·다음 조회 시각·알림을 비교한다. DB의 lease나 조회 시각을 수정하지 않는다. 스캔 30초, lease 45초, PG 읽기 제한 10초를 사용한다.

## 실행

[A1 실행 환경](payment-recovery-a1.md#실행)과 `java`, `javac`가 있는 JDK 21이 필요하다. 앱 소스와 JAR의 줄 번호가 일치해야 한다.

```sh
./gradlew bootJar
PYTHONDONTWRITEBYTECODE=1 python3 scripts/payment_recovery_d.py \
  --profile late-done --jar build/libs/kitschcatch-backend-0.0.1-SNAPSHOT.jar \
  --db-port 55486 --db-user a1 --db-name kitschcatch_w126 \
  --repeat 3 --out /tmp/kitschcatch-d-late-done
```

`--profile late-error`는 별도 결과 디렉터리에서 실행한다. 회차별 앱 로그와 디버거 로그, `events.jsonl`, 결과 CSV에 두 PID, 만료 전후 token, 조회와 커밋 순서를 남긴다.

## 2026-11-12~13 결과

| A가 보류한 결과 | B가 반영한 결과 | 통과 | B의 선점 시각 |
| --- | --- | ---: | --- |
| 늦은 `DONE`. | `CANCELED / CANCELED / ON_SALE`. | 3회. | lease 만료 후 20.667~21.033초. |
| 늦은 HTTP 503 오류. | `SUCCESS / PAID / SOLD_OUT`. | 3회. | lease 만료 후 20.555~21.273초. |
| 늦은 `DONE`, 최종 디버거 재검증. | `CANCELED / CANCELED / ON_SALE`. | 1회. | lease 만료 후 21.043초. |

모든 회차에서 A의 실제 lease 만료 뒤 B가 다른 token을 확보했다. B가 DB 반영을 마친 뒤 A를 재개하자 이전 token으로 거절 분기의 return을 실행했다. 재개 전후 결제·주문·상품·시도 상태와 상태 버전, 검증·완료 시각, 다음 조회 시각, 알림이 일치했다. 회차마다 승인 POST 1회와 PG GET 2회를 기록했다.

초기 오류 조건 준비에서는 디버거가 최초 승인 API의 오류 처리도 감지했다. 복구 스케줄러에서 온 호출만 멈추도록 조정했고, 최종 디버거로 성공 조건도 한 번 더 확인했다. 공용 실행기 변경 후 A4가 1회 통과했다. 원자료는 작업 공간의 `build/payment-recovery-d/2026-11-12/`에 보존했다.
