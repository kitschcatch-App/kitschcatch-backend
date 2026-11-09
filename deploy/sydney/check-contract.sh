#!/usr/bin/env bash
# 수동 마이그레이션 목록이 커밋의 배포 계약과 일치하는지 확인한다.
set -euo pipefail
contract=deploy/sydney/schema-migrations.sha256
diff -u <(find src/main/resources/db/manual -maxdepth 1 -type f -name '*.sql' | LC_ALL=C sort) \
  <(awk '{print $2}' "$contract" | LC_ALL=C sort)
sha256sum -c --quiet "$contract"
