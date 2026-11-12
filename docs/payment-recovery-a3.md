# 배송 전 전액 취소 직후 앱 종료와 자동 복구

추적 이슈는 [#126](https://github.com/kitschcatch-App/kitschcatch-backend/issues/126)이다. 앱 빌드의 기준 커밋은 `1c6ba551249fd66320361a4b29363b9154690f87`이다.

실제 주문·승인 API로 배송 전 결제 `SUCCESS`와 상품 `SOLD_OUT`을 만든다. 같은 결제의 취소 API를 호출하면 PG 대역이 `CANCELED`와 잔액 0을 기록하고 응답을 보류한다. 실행기는 DB에 결제 `PROCESSING`, 주문 `PAID`, 상품 `SOLD_OUT`, 취소 시도 `PROCESSING`이 커밋된 것을 확인한 뒤 앱 JVM에 `SIGKILL`을 보낸다. 기존 예약 소유 주문은 종료 직후에도 유지되어야 한다.

같은 JAR와 DB 스키마로 새 JVM을 시작한다. 스케줄러의 PG 조회 후 결제와 주문은 `CANCELED`, 취소 시도는 `SUCCEEDED`, 상품은 `ON_SALE`이고 기존 예약 소유자는 비어 있어야 한다. 동일 취소 요청을 다시 보내면 `200 CANCELED`가 반환되고 승인·취소 POST와 PG 대역의 효과는 각각 1회, 승인·취소 알림은 구매자와 판매자에게 종류별로 각각 1건이어야 한다. 마지막으로 다른 구매자가 실제 주문 API에서 `201`을 받고 같은 상품을 새 주문으로 예약할 수 있는지 확인한다.

## 실행

[A1 실행 환경](payment-recovery-a1.md#실행)에서 공용 실행기에 `--scenario A3`를 지정한다.

```sh
./gradlew bootJar
python3 scripts/payment_recovery_a1.py --scenario A3 \
  --jar build/libs/kitschcatch-backend-0.0.1-SNAPSHOT.jar \
  --db-port 55486 --db-user a1 --db-name kitschcatch_a1 \
  --repeat 4 --out /tmp/kitschcatch-a3-run
```

기본 스캔 주기는 30초다. `--scan-seconds 1`은 재기동 후 스캔 주기만 줄인다. 회차별 `events.jsonl`에 승인 완료, PG 취소 효과와 잔액, JVM 종료 전후 상태, 새 JVM의 조회와 상태 확정, 취소 재전송, 새 구매자의 예약을 기록한다.

## 2026-11-12 결과

Java 21.0.7과 PostgreSQL 14.18에서 1초 재기동 스캔으로 준비 검증 3회가 모두 통과했다. 공용 실행기 변경 후 A1과 A2도 각각 1회 재검증해 통과했다.

기본 30초 스캔에서는 같은 호스트와 PostgreSQL 인스턴스에서 스키마를 분리한 5개 묶음을 병행했다. 각 묶음 4회씩 총 20회 모두 통과했다. 전 회차에서 종료 직후 상품은 `SOLD_OUT`이었고, 새 JVM은 PG 조회 1회로 결제·주문 취소와 상품 `ON_SALE`을 반영했다. 동일 취소 요청을 다시 보내도 PG 호출과 알림은 늘지 않았다. 다른 구매자의 주문은 전 회차에서 `201`을 반환했고 새 주문이 상품의 예약 소유자가 됐다.

| 기본 주기 20회의 관측 구간 | 중앙값 | 최댓값 |
| --- | ---: | ---: |
| PG 대역의 취소 효과 기록부터 SIGKILL 전송까지 | 0.046초 | 0.056초 |
| PG 대역의 취소 효과 기록부터 DB 반영 관측까지 | 43.694초 | 53.593초 |
| 재기동 준비 완료부터 DB 반영 관측까지 | 30.538초 | 30.682초 |

DB 상태는 0.25초 간격으로 관측했다. 이 결과는 로컬 PG 대역과 배송 전 전액 취소 조건에 해당한다. 원자료는 작업 공간의 `build/payment-recovery-a3/2026-11-12/`에 보존했다.
