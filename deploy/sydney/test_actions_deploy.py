# 배포 작업만 재실행해도 같은 빌드와 새로운 S3 경로를 사용하는지 검증한다.
import json
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from deploy import read_json, sha256, write_json

ActionsDeployment = runpy.run_path(str(Path(__file__).with_name("actions-deploy.py")))["ActionsDeployment"]


class ActionsRetryTests(unittest.TestCase):
    def test_deploy_retry_reuses_build_but_uploads_to_new_release(self):
        commit = "a" * 40
        build_id = f"{commit}-123-1"
        retry_id = f"{commit}-123-2"
        env = {
            "BUILD_RELEASE_ID": build_id, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2",
            "MODE": "deploy", "AWS_ACCOUNT": "123456789012", "ARTIFACT_BUCKET": "bucket",
            "INSTANCE_ID": "instance", "SSM_DOCUMENT": "document", "TARGET_GROUP_ARN": "group",
            "HTTPS_ORIGIN": "https://dev.example.com",
        }
        with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", env, clear=True):
            stage = Path(directory)
            (stage / "app.jar").write_bytes(b"tested")
            (stage / "schema-contract.jsonl").write_bytes(b"schema")
            write_json(stage / "release.json", {
                "releaseId": build_id, "commit": commit,
                "jarSha256": sha256(stage / "app.jar"),
                "schemaSha256": sha256(stage / "schema-contract.jsonl"),
            })
            deployment = ActionsDeployment(stage)
            with patch.object(deployment, "aws", side_effect=[
                env["AWS_ACCOUNT"], json.dumps({"VersionId": "jar"}),
                json.dumps({"VersionId": "schema"}), json.dumps({"VersionId": "manifest"}),
            ]) as aws:
                deployment.upload()
            self.assertEqual(deployment.release, retry_id)
            manifest = read_json(stage / "release.json")
            self.assertEqual((manifest["buildReleaseId"], manifest["releaseId"]), (build_id, retry_id))
            requests = [call.args[0] for call in aws.call_args_list[1:]]
            self.assertEqual([args[args.index("--key") + 1] for args in requests],
                             [f"backend/{retry_id}/{name}" for name in ("app.jar", "schema-contract.jsonl", "release.json")])
