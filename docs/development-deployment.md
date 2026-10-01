# 개발 서버 CI/CD

`.github/workflows/ci-cd.yml`은 `develop` 대상 PR과 `develop` 푸시에서 Java 21 빌드 및 테스트를 실행한다. CI는 폐기 가능한 PostgreSQL 14 서비스 컨테이너를 사용해 `ISSUE27/31/39/41/43/45/47~57` 전용 테스트도 실행한다. 기본 스키마를 쓰는 27·53번 테스트에는 각각 별도 데이터베이스를 사용한다. 외부 S3와 Toss는 CI에서 실제 호출하지 않는다.

`develop` 푸시 또는 `develop`에서 수동 실행한 빌드가 통과하면 `DEV_DEPLOY_ENABLED=true`일 때만 `development` 환경의 ECS 서비스에 배포한다. 새 커밋의 Docker 이미지를 ECR에 올리고 현재 서비스의 태스크 정의에서 컨테이너 이미지만 바꾼 새 리비전을 등록한 뒤 서비스가 안정화될 때까지 기다린다. GitHub OIDC로 단기 AWS 자격 증명을 얻으며 장기 AWS 액세스 키는 GitHub에 저장하지 않는다.

## 배포 전 준비

1. 개발 계정에 ECR 저장소, ECS 클러스터·서비스·태스크 정의, 개발 PostgreSQL과 네트워크를 먼저 준비한다. 태스크 정의의 컨테이너 환경에는 `SPRING_PROFILES_ACTIVE=dev`, `DEV_DB_URL`, `DEV_DB_USERNAME`, `DEV_DB_PASSWORD`, `APP_JWT_SECRET` 등 필요한 값을 AWS Secrets Manager 또는 SSM Parameter Store 참조로 설정한다. S3·Toss는 실제 연동을 준비하기 전까지 비활성 상태로 둔다.
2. 개발 DB의 기존 스키마와 `src/main/resources/db/manual/`의 수동 SQL 적용 상태를 확인한다. 이 워크플로는 마이그레이션을 자동 실행하지 않는다. 스키마 변경이 있는 릴리스에는 [거래 후속 처리 배포 순서](order-lifecycle-environment.md) 등 기능별 절차를 먼저 따른다.
3. AWS IAM에 GitHub OIDC 공급자와 배포 역할을 만들고, 신뢰 정책을 이 저장소의 `development` 환경으로 제한한다. 역할에는 대상 ECR에 대한 이미지 업로드, 대상 ECS 서비스·태스크 정의 조회 및 업데이트, 태스크 실행 역할 전달에 필요한 최소 권한을 부여한다. GitHub `development` 환경의 배포 브랜치를 `develop`으로 제한한다.
4. GitHub `development` 환경 변수를 아래와 같이 설정한다. 값은 실제 리소스 이름과 맞춰야 한다.

| 환경 변수 | 값 |
| --- | --- |
| `DEV_AWS_REGION` | ECS/ECR 리전. |
| `DEV_AWS_ROLE_ARN` | GitHub OIDC로 수임할 IAM 역할 ARN. |
| `DEV_ECR_REPOSITORY` | ECR 저장소 이름. |
| `DEV_ECS_CLUSTER` | ECS 클러스터 이름. |
| `DEV_ECS_SERVICE` | ECS 서비스 이름. |
| `DEV_ECS_CONTAINER` | 태스크 정의 안의 애플리케이션 컨테이너 이름. |

모든 준비와 첫 배포 전 DB 검증이 끝난 뒤에만 GitHub 저장소 변수 `DEV_DEPLOY_ENABLED`를 `true`로 설정한다. 변수가 없거나 `false`이면 CI만 실행하고 배포 작업은 건너뛴다. 기존 `develop` 커밋은 변수를 켠 것만으로 재배포되지 않으며, Actions에서 `develop`을 지정해 수동 실행하거나 다음 `develop` 푸시를 보내야 배포가 시작된다.

## 상태 확인

GitHub Actions의 `Test and build` 성공은 CI 빌드 결과다. `Deploy to development ECS`가 성공하고 ECS 서비스의 태스크가 실행되어야 배포 완료로 본다. 실제 API와 DB 동작은 개발 서버 주소에서 별도로 확인한다. 배포 작업이 건너뛰어졌다면 서버에 반영되지 않았다.
