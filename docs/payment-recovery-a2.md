# PG 승인 응답 유실과 같은 JVM의 자동 복구

추적 이슈는 [#126](https://github.com/kitschcatch-App/kitschcatch-backend/issues/126)이다. 앱 빌드의 기준 커밋은 `1c6ba551249fd66320361a4b29363b9154690f87`이다.

A2는 실제 주문·승인 HTTP API로 진입한다. PG 대역이 승인 효과를 `DONE`으로 기록하고 실행기가 DB의 승인 시작 커밋을 확인하면, PG 대역이 HTTP 상태 줄과 본문을 보내기 전에 승인 응답 소켓을 닫는다. 같은 앱 JVM은 전송 오류를 처리해 `202 PROCESSING`을 반환하고, DB에는 결제 `PROCESSING`·주문 `PENDING`·상품 `RESERVED`·시도 `UNKNOWN`과 다음 조회 시각이 남는다.

실행기는 미확정 상태에서 같은 승인 요청을 다시 보내 `202 PROCESSING`과 PG 재호출 방지를 확인한다. 같은 PID의 스케줄러가 PG를 조회해 결제 `SUCCESS`·주문 `PAID`·상품 `SOLD_OUT`·시도 `SUCCEEDED`를 반영할 때까지 기다린다. 복구 뒤 동일 요청에 `200 SUCCESS`를 받고 승인 POST·효과·시도 각 1회, 예약 소유권 유지, 구매자·판매자의 성공 알림 각 1건을 확인한다.

## 실행

[A1 실행 환경](payment-recovery-a1.md#실행)에서 같은 실행기에 `--scenario A2`를 지정한다.

```sh
./gradlew bootJar
python3 scripts/payment_recovery_a1.py --scenario A2 \
  --jar build/libs/kitschcatch-backend-0.0.1-SNAPSHOT.jar \
  --db-port 55486 --db-user a1 --db-name kitschcatch_a1 \
  --repeat 4 --out /tmp/kitschcatch-a2-run
```

기본 스캔 주기는 30초다. `--scan-seconds 2`로 실행기 동작을 먼저 확인할 수 있다. 이 옵션은 스캔 주기에만 적용되고 전송 오류 뒤 첫 조회 대기 30초는 그대로다. 회차별 `events.jsonl`에 PG 승인 효과, 연결 단절, 클라이언트가 받은 두 개의 202 응답 본문, 같은 JVM의 최종 반영과 재전송 응답 상태를 기록한다.

## 2026-11-12 결과

Java 21.0.7과 PostgreSQL 14.18에서 2초 스캔으로 준비 검증 3회가 통과했다. 실행기를 공유하는 A1도 1회 재검증해 통과했다.

기본 30초 스캔에서는 같은 호스트와 PostgreSQL 인스턴스에서 스키마를 분리한 5개 묶음을 병행했다. 묶음마다 4회씩 총 20회가 모두 통과했다. 각 회차에서 최초 승인과 복구 전 재전송에 `202 PROCESSING`을 받았다. 같은 JVM의 조회 1회로 자동 복구됐고 복구 후 재전송은 `200 SUCCESS`였다. 승인 POST·효과·시도 각각 1회, 구매자·판매자 알림 각각 1건을 확인했다.

| 기본 주기 20회의 관측 구간 | 중앙값 | 최댓값 |
| --- | ---: | ---: |
| PG 응답 연결 단절부터 202 응답 수신까지 | 0.058초 | 0.091초 |
| 202 응답 수신부터 DB 반영 관측까지 | 59.114초 | 59.574초 |
| PG 대역의 승인 효과 기록부터 DB 반영 관측까지 | 59.200초 | 59.622초 |

전송 오류 뒤 첫 조회 가능 시각이 첫 스캔보다 늦어 다음 스캔에서 복구됐다. DB 상태는 0.25초 간격으로 관측했다. 이 결과는 로컬 PG 대역의 소켓 단절 실험에 해당한다. 원자료는 작업 공간의 `build/payment-recovery-a2/2026-11-12/`에 보존했다.
