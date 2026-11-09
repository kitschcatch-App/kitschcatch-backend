#!/usr/bin/env bash
# CI와 개발 배포에 같은 PostgreSQL 테스트 설정을 적용한다.
set -euo pipefail
for issue in 31 39 41 43 45 47 48 49 50 51 52 54 55 56 57 79 83 85 87 89 91; do
  export "ISSUE${issue}_TEST_DB_URL=$CI_TEST_DB_URL"
  export "ISSUE${issue}_TEST_DB_USERNAME=$CI_TEST_DB_USER"
  export "ISSUE${issue}_TEST_DB_PASSWORD=$CI_TEST_DB_PASSWORD"
done
export ISSUE27_TEST_DB_URL="${CI_TEST_DB_URL}27"
export ISSUE27_TEST_DB_DRIVER=org.postgresql.Driver
export ISSUE27_TEST_DB_USER="$CI_TEST_DB_USER"
export ISSUE27_TEST_DB_PASSWORD="$CI_TEST_DB_PASSWORD"
export ISSUE53_TEST_DB_URL="${CI_TEST_DB_URL}53"
export ISSUE53_TEST_DB_USER="$CI_TEST_DB_USER"
export ISSUE53_TEST_DB_PASSWORD="$CI_TEST_DB_PASSWORD"
export ISSUE54_TEST_DB_USER="$CI_TEST_DB_USER"
export ADDRESS_TEST_DB_URL="${CI_TEST_DB_URL}93"
export ADDRESS_TEST_DB_USER="$CI_TEST_DB_USER"
export ADDRESS_TEST_DB_PASSWORD="$CI_TEST_DB_PASSWORD"
./gradlew clean build --no-daemon --console=plain "$@"
