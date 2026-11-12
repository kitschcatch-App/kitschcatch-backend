# 시드니 개발 EC2 배포

GitHub Actions의 **Deploy Sydney development EC2**에서 `develop`과 실행 모드를 선택한다. `commit_sha`를 비우면 실행을 시작할 때 확인한 `develop`의 최신 커밋을 사용한다. SHA를 지정하면 `develop`의 first-parent 이력에 있는 전체 SHA인지 확인한다. 배포 준비 API와 스키마 계약이 들어간 커밋부터 지원한다. `preflight`가 기본이며, `deploy`를 선택하면 검사 후 앱을 교체한다.

빌드·테스트에서 생성한 JAR, 스키마 계약과 명세를 빌드 시도별 Actions 아티팩트로 전달한다. 배포 작업은 OIDC로 AWS에 접속해 이 파일을 버전 관리된 전용 S3 버킷에 올린다. 파일 경로는 소스 SHA, Actions 실행 ID, 배포 시도 번호를 사용한다. 실패한 배포 작업만 재실행하면 기존 빌드 아티팩트를 읽고 새 S3 경로와 배포 ID를 사용한다. EC2는 명세 해시와 JAR·계약의 해시를 대조하고, 명세에 기록한 JAR·계약의 S3 객체 버전을 읽는다. [배포 워크플로](../.github/workflows/deploy-sydney.yml)

## AWS와 GitHub 설정

인프라의 `environments/dev-kc-deploy`는 개발 EC2의 기존 배포 구성과 별도 Terraform state를 사용한다. 버킷·SSM 문서·역할을 준비한 뒤 SSM Agent가 `Online`이고 `KitschCatch-Dev-Sydney-Deploy`의 `status` 요청을 지정 인스턴스가 수행하는지 확인한다. OIDC를 사용할 수 있는 계정에서만 배포 역할을 설정한다. [인프라 PR #7](https://github.com/kitschcatch-App/kitschcatch-infrastucture/pull/7)

GitHub 환경 `development-sydney`는 `develop` 브랜치만 허용한다.

| 환경 변수 | 값 |
| --- | --- |
| `SYDNEY_AWS_ACCOUNT` | 시드니 개발 AWS 계정 ID. |
| `SYDNEY_DEPLOY_ROLE_ARN` | Terraform이 출력한 GitHub OIDC 역할 ARN. |
| `SYDNEY_ARTIFACT_BUCKET` | 버전 관리를 설정한 배포 전용 버킷. |
| `SYDNEY_INSTANCE_ID` | 배포할 개발 EC2 ID. |
| `SYDNEY_SSM_DOCUMENT` | `KitschCatch-Dev-Sydney-Deploy`. |
| `SYDNEY_TARGET_GROUP_ARN` | 개발 ALB 대상 그룹 ARN. |
| `SYDNEY_HTTPS_ORIGIN` | `https://dev-kitschcatch.p-e.kr`. |

## 서버 초기 설정

1. `/opt/kitschcatch/deploy`를 root 소유 권한 `0700`으로 만들고, 검토한 `deploy/sydney/deploy.py`와 `catalog.sql`을 설치한다.
2. 확인된 실행 릴리스의 SHA, 컨테이너 내부·서버 파일의 JAR 해시, 해당 버전의 스키마 계약을 확보한다. 기존 릴리스가 준비 확인 API를 제공하지 않으면 `health: "legacy"`로 기록하고 `baseline-schema.jsonl`에 그 버전의 계약을 설치한다. 복구 검증에서 해당 릴리스의 내부·외부 OpenAPI와 미인증 API 응답을 사용한다.
3. `/opt/kitschcatch/deploy/config.json`을 root 소유 권한 `0600`으로 작성한다. `account`, `instance`, `bucket`, `httpsOrigin`, `dbTarget` 배열의 호스트·포트·DB 경로, `postgresImage`, `baseline`의 `commit`, `jarSha256`, `schemaSha256`, `health`를 설정한다. PostgreSQL 클라이언트 이미지는 서버에 내려받은 digest를 지정한다. DB 비밀번호는 기존 `backend.env`를 사용한다.
4. `kitschcatch-deploy@.service`와 `kitschcatch-deploy-recovery.service`를 `/etc/systemd/system`에 설치하고 `systemctl daemon-reload`, `systemctl enable --now kitschcatch-deploy-recovery.service`를 실행한다.

호스트에서는 `runtime.env`의 `BACKEND_ENV_FILE`이 `/opt/kitschcatch/backend.env`를 가리키는지 확인한다. `dbTarget`은 같은 파일의 개발 JDBC 접속 대상으로 설정한다. 계정, EC2 ID, 개발 프로필, DB 대상이 설정과 맞아야 배포를 시작한다.

## DB 계약 관리

`schema-contract.jsonl`에는 대상 PostgreSQL의 테이블·컬럼·제약·인덱스·실행 권한을 기록한다. 초기 계약은 개발 DB PostgreSQL 17의 애플리케이션 테이블 38개에서 확인했다. `catalog.sql`을 읽기 전용으로 실행하며 검사 결과에 누락되거나 정의가 다른 객체의 종류와 이름이 표시된다. 예를 들어 `column/reports/version`은 `107_report_reviews.sql`의 적용 상태를 확인한다.

거래 알림 스키마를 확장하는 릴리스는 [이슈 #69 적용 순서](issue-69-trade-notifications.md#개발운영-적용-순서)에 따라 새 알림 생성을 끈 상태에서 호환 릴리스를 먼저 확보한다.

수동 SQL을 추가하거나 수정할 때 관련 스키마 정의를 계약에 반영하고, SQL 목록과 해시도 함께 갱신한다. 새 환경에서는 검토한 마이그레이션을 먼저 적용하고 동일한 PostgreSQL 버전의 카탈로그와 계약을 대조한다. 카탈로그 문자열 형식은 PostgreSQL 주요 버전에 따라 달라질 수 있다.

```sh
sha256sum src/main/resources/db/manual/*.sql > deploy/sydney/schema-migrations.sha256
bash deploy/sydney/check-contract.sh
```

검사는 `BEGIN READ ONLY`와 쿼리 제한 시간을 사용한다. 누락된 SQL 적용은 백업과 검증을 포함한 별도 작업이다.

## 배포와 복구 결과

서버는 `.deploy.lock`으로 중복 배포를 차단한다. 새 릴리스를 설치하기 전에 현재 실행 JAR와 파일의 해시, 복구 대상의 DB 계약을 확인한다. 전환 직전에 `runtime.env`와 이전 릴리스 명세를 저장한다. 변경 후 Compose 앱을 재생성하고 Nginx 구성을 검사·재로드한다.

서버 내부 준비 상태, 마운트된 JAR 해시, 컨테이너 상태, 공개 HTTPS의 준비 상태·커밋·필수 OpenAPI 경로와 미인증 `401`을 확인한다. Actions는 같은 커밋이 공개 HTTPS로 응답하고 지정 ALB 대상이 `healthy`이면 확인을 보낸다. 준비 확인 API는 개발 프로필에서 DB 연결 상태와 JAR에 포함된 커밋을 반환한다.

| 결과 | 의미 |
| --- | --- |
| `PREFLIGHT_OK` | 파일·DB·복구 대상이 확인됐다. |
| `REJECTED` | 실행 릴리스를 전환하기 전에 실패했다. |
| `AWAITING_CONFIRMATION` | EC2 검증이 완료됐고 Actions의 HTTPS·ALB 확인을 기다린다. |
| `DEPLOYED` | 서버와 Actions 검증이 완료됐다. |
| `ROLLED_BACK` | 새 버전 배포 실패 후 이전 JAR와 HTTPS를 검증했다. |
| `ROLLBACK_FAILED` | 이전 버전 복구 또는 검증에서 실패했다. |

앱·Nginx·HTTPS 검증에 실패하거나 서버가 150초 안에 확인을 받지 못하면 롤백을 시작한다. 이전 버전의 JAR와 DB 계약을 다시 확인한 뒤 실행 설정을 복원하고 앱 재생성, Nginx 재로드, JAR 해시와 HTTPS 검증을 수행한다. 이전 버전이 현재 DB와 호환되지 않으면 `ROLLBACK_FAILED`로 기록한다. 복구 범위는 앱 릴리스다.

실행 중 연결을 끊어도 systemd가 서버 배포 작업을 유지한다. 비정상 종료 시 서비스의 `ExecStopPost`, 재부팅 시 복구 서비스가 미완료 상태를 읽어 전환을 되돌린다. 장애가 나면 다음 기록으로 마지막 상태와 복구 기준을 확인한다.

```sh
sudo python3 /opt/kitschcatch/deploy/deploy.py status "$release_id"
sudo journalctl -u "kitschcatch-deploy@$release_id.service"
```

작업이 중단돼 전환 중 상태가 남으면 `sudo python3 /opt/kitschcatch/deploy/deploy.py recover "$release_id"`로 기록된 이전 릴리스를 복구한다. `ROLLBACK_FAILED`이면 해당 배포 디렉터리의 `runtime.env.previous`와 `previous.json`을 확인한다. 이전 JAR 해시와 DB 호환성을 검증한 뒤 배포 잠금을 잡고 실행 설정을 복원하고, 앱 재생성·Nginx 재로드·HTTPS 검증을 수행한다.

## 2026-11-09 개발 환경 검증

이슈 #111에서 시드니 개발 계정에 비공개 배포 버킷, 버전 관리·암호화·수명 주기, SSM 명령 문서와 EC2 인라인 정책 등 8개 리소스를 적용했다. OIDC를 끈 Terraform 재계획에서 변경 0개를 확인했다. GitHub에 `develop`만 허용하는 `development-sydney` 환경과 리소스 변수 6개를 설정했다.

AWS 조직 SCP는 OIDC 공급자 생성 `iam:CreateOpenIDConnectProvider`와 EC2 등록 `ssm:UpdateInstanceInformation`을 명시적으로 차단한다. 인스턴스의 에이전트는 실행되지만 SSM 관리 노드가 되지 않아 Run Command가 `InvalidInstanceId`를 반환한다. GitHub 역할 변수는 조직 제한이 해소된 뒤 설정할 수 있다.

이번 검증은 기존 SSH 관리 경로에서 호스트의 같은 systemd 배포 프로그램을 호출했다. 배포 JAR는 전용 S3에 올렸고, EC2는 인스턴스 역할로 파일을 가져왔다. GitHub OIDC를 사용한 워크플로의 종단 간 실행은 조직 제한에 막혀 있다.

- Java 21과 격리 PostgreSQL 14에서 전체 테스트 1,555개가 통과했다. 실패·오류·건너뜀은 0개다.
- 배포 프로그램 테스트 5개와 두 GitHub 워크플로의 `actionlint`, 수동 SQL 계약 검사, Terraform `fmt -check`와 `validate`가 통과했다.
- 실제 개발 DB에서 `PREFLIGHT_OK`를 확인했다. 존재하지 않는 검증용 컬럼을 계약에 추가하자 `REJECTED`가 기록됐으며 기존 JAR와 외부 API가 그대로 응답했다. 이 검증은 DB 스키마를 변경하지 않았다.
- 후보 릴리스 `3ea9bb9`의 실행 JAR SHA-256은 `8754693bec9cad2f2b9ddd2e73fc76be0d4cef0c2a0db1ccf76c58ff290afed5`였다. 공개 HTTPS의 준비 응답과 실행 커밋, ALB `healthy`를 확인한 뒤 외부 확인을 보내지 않았다. 대기 중 중복 배포 요청도 차단됐다.
- 20:42:39 KST에 외부 확인 시간 초과가 기록됐고, 20:43:11 KST에 `ROLLED_BACK`으로 완료됐다. 이전 릴리스 `a25540b`의 실행 JAR 해시, 공개 OpenAPI, ALB `healthy`를 다시 확인했다.
- 정상 경로는 동일 후보로 다시 실행했다. 공개 HTTPS의 커밋과 ALB 상태를 확인하고 외부 확인을 전달해 20:44:31 KST에 `DEPLOYED`로 확정했다.
- PR을 열기 전 임시 후보 검증을 마치고 20:45:14 KST에 기존 `a25540b`로 복귀했다. 실행 JAR SHA-256 `f7bb2b1e3d5e7a58fd86252d8ac9974424e6044f9308f41ea80e2d7d9aa12c5c`, HTTPS OpenAPI, ALB `healthy`를 확인했다.
