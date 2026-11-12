#!/usr/bin/env python3
# 테스트에서 생성한 JAR와 스키마 계약을 배포 명세에 연결한다.
import json
import sys
from pathlib import Path
from zipfile import ZipFile

from deploy import RELEASE_ID, sha256

release_id, path = sys.argv[1:]
match = RELEASE_ID.fullmatch(release_id)
if not match:
    sys.exit("배포 ID 형식 오류")
stage = Path(path)
commit = match.group(1)
with ZipFile(stage / "app.jar") as jar:
    if jar.read("BOOT-INF/classes/kitschcatch-release.properties").decode().strip() != f"commit={commit}":
        sys.exit("JAR에 포함된 커밋과 배포 요청이 다릅니다.")
manifest = {
    "commit": commit,
    "releaseId": release_id,
    "jarSha256": sha256(stage / "app.jar"),
    "schemaSha256": sha256(stage / "schema-contract.jsonl"),
    "health": "readiness",
}
(stage / "release.json").write_text(json.dumps(manifest, sort_keys=True) + "\n")
