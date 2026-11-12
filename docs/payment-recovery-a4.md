# PG 승인 요청 직전 앱 종료와 예약 유지

추적 이슈는 [#126](https://github.com/kitschcatch-App/kitschcatch-backend/issues/126)이다. 앱 빌드의 기준 커밋은 `1c6ba551249fd66320361a4b29363b9154690f87`이다.

실험기가 시작한 첫 앱 JVM에만 루프백 디버거를 연결한다. 실제 앱 승인 API가 `PaymentService.confirmPayment`에서 `HttpTossPaymentsClient.confirm`으로 진입하는 순간 모든 스레드를 멈춘다. 이때 별도 DB 연결에서 승인 시작의 `PROCESSING / PENDING / RESERVED / PROCESSING` 커밋을 읽고 PG 승인 POST와 효과가 각각 0건인지 확인한 뒤, 재개 없이 앱에 `SIGKILL`을 보낸다.

같은 JAR와 DB 스키마로 새 JVM을 시작한다. PG 대역에는 승인 기록이 없어 조회에 `404 NOT_FOUND_PAYMENT`를 반환한다. 스케줄러가 시도를 `UNKNOWN`으로 바꾸고 다음 조회를 예약하는지 확인한다. 결제 `PROCESSING`, 주문 `PENDING`, 상품 `RESERVED`와 기존 예약 소유권이 유지돼야 한다. 동일 승인 재전송은 `202 PROCESSING`이어야 하고 PG 승인 POST·효과는 계속 각각 0건이어야 한다.

## 실행

[A1 실행 환경](payment-recovery-a1.md#실행)과 `java`, `javac`가 들어 있는 JDK 21이 필요하다. A4 실행 시 디버거 보조 프로그램을 결과 디렉터리에 컴파일한다.

```sh
./gradlew bootJar
python3 scripts/payment_recovery_a1.py --scenario A4 \
  --jar build/libs/kitschcatch-backend-0.0.1-SNAPSHOT.jar \
  --db-port 55486 --db-user a1 --db-name kitschcatch_a1 \
  --repeat 5 --out /tmp/kitschcatch-a4-run
```

기본 설정은 스캔 30초, 예약 15분, 예약 만료 스캔 60초다. 첫 조회 실패를 관측하고 종료하므로 기본 설정 실행은 예약 기한을 끝까지 관측하지 않는다. `--scan-seconds 1 --a4-short-expiry`를 추가하면 예약 15초와 만료 스캔 2초를 적용한다. 이 조건에서는 승인에 진입하지 않은 별도 주문이 스케줄러에서 만료돼 상품이 `ON_SALE`로 돌아오는 동안, 대상의 기한은 지나도 원래 예약을 유지하고 조회 실패를 두 번 기록하는지 확인한다. 승인 시각·예약 기한·다음 조회 시각을 SQL로 변경하지 않는다.

`approval-breakpoint.log`와 `events.jsonl`에 중단 위치, 커밋, PG 요청 수, 종료 및 재기동, 404 조회와 DB 관측을 남긴다.

## 2026-11-12 결과

Java 21.0.7과 PostgreSQL 14.18에서 기본 설정 20회와 단축 만료 검증 3회가 모두 실험 기준을 충족했다. 기본 설정은 같은 호스트의 독립 스키마 4개 묶음에서 각 5회씩 실행했다. 준비 중 발견한 검증 조건과 동시 기동 문제를 고친 뒤의 결과이며, 초기 시도 로그도 보존했다. 공용 실행기의 A1·A2·A3도 각각 1회 통과했다.

- 기본 20회 모두 승인 POST와 효과가 0건이었다. 첫 GET의 404 후 결제 `PROCESSING`, 주문 `PENDING`, 상품 `RESERVED`, 시도 `UNKNOWN`, 복구 `PENDING`이 유지됐고 다음 조회가 예약됐다. 동일 승인 재전송도 `202 PROCESSING`이었으며 승인 POST는 계속 0건이었다.
- 단축 만료 3회 모두 대조군은 만료 스캔으로 주문 `CANCELED`와 상품 `ON_SALE`이 됐다. 대상은 기한 경과와 404 조회 실패 두 번을 관측한 뒤에도 기존 예약을 유지했다.
- 재기동 준비 완료부터 첫 조회 실패를 DB에서 관측하기까지 기본 설정 중앙값은 30.346초, 최댓값은 30.515초였다. DB 상태는 0.25초 간격으로 확인했다.

관측 종료 시 대상 결제는 모두 미해결이었다. 기본 20회는 첫 조회 실패까지 관측했고 예약 기한 경과는 단축 조건에서 확인했다. 원자료는 작업 공간의 `build/payment-recovery-a4/2026-11-12/`에 보존했다.
