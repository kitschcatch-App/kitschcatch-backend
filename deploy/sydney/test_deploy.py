# 배포 파일 거절과 전환 중단 후 이전 릴리스 복구를 검증한다.
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from deploy import (
    DeploymentError, LEGACY_NOTIFICATION_TYPES, TRADE_NOTIFICATION_TYPES, Server,
    notification_type_check, sha256, write_json,
)

BEFORE = "a" * 40
NEXT = "b" * 40


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        (self.root / "deploy").mkdir()
        write_json(self.root / "deploy/config.json", {})
        self.server = Server(self.root)
        self.previous_path = self.root / "releases" / BEFORE
        self.previous_path.mkdir(parents=True)
        (self.previous_path / "app.jar").write_bytes(b"old")
        baseline_schema = self.root / "deploy/baseline-schema.jsonl"
        baseline_schema.write_text(json.dumps(["table", "reports", None]))
        self.previous = {"commit": BEFORE, "jarSha256": sha256(self.previous_path / "app.jar"),
                         "schemaSha256": sha256(baseline_schema), "health": "legacy"}
        self.original_env = f"BACKEND_RELEASE_DIR={self.previous_path}\nKEEP_THIS=value\n"
        self.server.runtime.write_text(self.original_env)
        self.job = self.server.jobs / f"{NEXT}-12-1"
        self.artifact = self.job / "artifact"
        self.artifact.mkdir(parents=True)
        (self.artifact / "app.jar").write_bytes(b"candidate")
        (self.artifact / "schema-contract.jsonl").write_text(json.dumps(["table", "reports", None]))
        self.manifest = {"commit": NEXT, "releaseId": self.job.name, "health": "readiness",
                         "jarSha256": sha256(self.artifact / "app.jar"),
                         "schemaSha256": sha256(self.artifact / "schema-contract.jsonl")}
        write_json(self.artifact / "release.json", self.manifest)
        write_json(self.job / "request.json",
                   {"mode": "deploy", "source": "local", "manifestSha": sha256(self.artifact / "release.json")})
        self.server.state(self.job, status="QUEUED")
        for method in ("preflight_target", "current_release", "schema_check", "recreate"):
            mocked = patch.object(self.server, method, return_value=self.previous if method == "current_release" else None)
            setattr(self, method, mocked.start())
            self.addCleanup(mocked.stop)

    def test_tampered_jar_is_rejected_before_compose_switch(self):
        (self.artifact / "app.jar").write_bytes(b"bad")
        self.server.run(self.job)
        self.assertEqual(self.server.state(self.job)["status"], "REJECTED")
        self.assertEqual(self.server.runtime.read_text(), self.original_env)
        self.recreate.assert_not_called()

    def test_schema_mismatch_preserves_running_release(self):
        self.schema_check.side_effect = DeploymentError("column/reports/version")
        self.server.run(self.job)
        self.assertEqual(self.server.state(self.job)["error"], "column/reports/version")
        self.assertEqual(self.server.runtime.read_text(), self.original_env)
        self.recreate.assert_not_called()

    def test_startup_failure_recreates_verified_previous_release(self):
        self.recreate.side_effect = [DeploymentError("HTTPS 실패"), None]
        self.server.run(self.job)
        state = self.server.state(self.job)
        self.assertEqual((state["status"], state["restoredCommit"]), ("ROLLED_BACK", BEFORE))
        self.assertEqual(self.server.runtime.read_text(), self.original_env)
        self.assertEqual([call.args[0]["commit"] for call in self.recreate.call_args_list], [NEXT, BEFORE])

    def test_recovery_after_process_interruption_restores_previous(self):
        (self.job / "runtime.env.previous").write_text(self.original_env)
        write_json(self.job / "previous.json", self.previous)
        self.server.runtime.write_text("BACKEND_RELEASE_DIR=/changed\n")
        self.server.state(self.job, status="AWAITING_CONFIRMATION")
        self.server.recover()
        self.assertEqual(self.server.state(self.job)["status"], "ROLLED_BACK")
        self.assertEqual(self.server.runtime.read_text(), self.original_env)

    def test_rollback_failure_is_not_success(self):
        self.recreate.side_effect = DeploymentError("앱을 시작할 수 없습니다.")
        self.server.run(self.job)
        state = self.server.state(self.job)
        self.assertEqual(state["status"], "ROLLBACK_FAILED")
        self.assertIn("앱을 시작할 수 없습니다.", state["rollbackError"])

    def test_schema_drift_after_preflight_blocks_incompatible_rollback(self):
        self.recreate.side_effect = DeploymentError("HTTPS 실패")
        self.schema_check.side_effect = [None, DeploymentError("column/reports/version")]
        self.server.run(self.job)
        state = self.server.state(self.job)
        self.assertEqual((state["status"], state["rollbackError"]), ("ROLLBACK_FAILED", "column/reports/version"))
        self.assertIn(str(self.server.releases / self.job.name), self.server.runtime.read_text())
        self.recreate.assert_called_once_with(self.manifest)

    def test_late_confirmation_does_not_create_receipt(self):
        self.server.state(self.job, status="AWAITING_CONFIRMATION", confirmationDeadline=250)
        with patch("deploy.time.monotonic", return_value=251):
            with self.assertRaisesRegex(DeploymentError, "시간이 초과"):
                self.server.confirm(self.job)
        self.assertFalse((self.job / "confirmed").exists())

    def test_confirmation_receipt_cannot_bypass_deadline(self):
        write_json(self.job / "confirmed", {"receivedAt": 251})
        with patch("deploy.time.monotonic", return_value=100):
            with self.assertRaisesRegex(DeploymentError, "시간이 초과"):
                self.server.wait_for_confirmation(self.job)

class NotificationSchemaCompatibilityTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "deploy").mkdir()
        write_json(root / "deploy/config.json", {"postgresImage": "test-postgres"})
        (root / "deploy/catalog.sql").write_text("catalog")
        (root / "backend.env").write_text("")
        self.server = Server(root)
        compose = patch.object(self.server, "compose_run", return_value="")
        self.compose = compose.start()
        self.addCleanup(compose.stop)
        self.contract = root / "contract.jsonl"
        self.table = ("table", "notifications", None)
        self.legacy = notification_type_check(LEGACY_NOTIFICATION_TYPES)
        self.expanded = notification_type_check(LEGACY_NOTIFICATION_TYPES + TRADE_NOTIFICATION_TYPES)

    def snapshot(self, *rows):
        return "\n".join(json.dumps(row) for row in (self.table, *rows))

    def test_old_reader_can_rollback_before_new_types_are_written(self):
        self.contract.write_text(self.snapshot(self.legacy))
        with patch("deploy.execute", side_effect=[self.snapshot(self.expanded), "t"]) as query:
            self.server.schema_check(self.contract)
        self.assertEqual(query.call_count, 2)
        self.assertIn("FROM public.notifications", query.call_args.kwargs["stdin"])

    def test_old_reader_cannot_rollback_after_new_types_are_written(self):
        self.contract.write_text(self.snapshot(self.legacy))
        with patch("deploy.execute", side_effect=[self.snapshot(self.expanded), "f"]):
            with self.assertRaisesRegex(DeploymentError, "constraint/notifications/notifications_type_check"):
                self.server.schema_check(self.contract)

    def test_old_reader_is_not_accepted_when_emission_is_enabled(self):
        self.contract.write_text(self.snapshot(self.legacy))
        (self.server.root / "backend.env").write_text("APP_NOTIFICATIONS_TRADE_ENABLED=true\n")
        with patch("deploy.execute", return_value=self.snapshot(self.expanded)) as query:
            with self.assertRaisesRegex(DeploymentError, "거래 알림 생성을 꺼야"):
                self.server.schema_check(self.contract)
        query.assert_called_once()

    def test_old_reader_cannot_rollback_while_container_still_emits_after_file_change(self):
        self.contract.write_text(self.snapshot(self.legacy))
        (self.server.root / "backend.env").write_text("APP_NOTIFICATIONS_TRADE_ENABLED=false\n")
        def observe(args, error, *, stdin=None):
            if stdin == "catalog":
                return self.snapshot(self.expanded)
            if stdin and "FROM public.notifications" in stdin:
                return "t"
            if args[:2] == ["docker", "inspect"]:
                return "APP_NOTIFICATIONS_TRADE_ENABLED=true"
            self.fail(f"예상하지 않은 검사: {args[:2]}")

        self.compose.return_value = "a" * 12
        with patch("deploy.execute", side_effect=observe) as query:
            with self.assertRaisesRegex(DeploymentError, "앱 컨테이너.*거래 알림"):
                self.server.schema_check(self.contract)
        self.assertEqual(query.call_count, 2)

    def test_old_reader_can_rollback_after_container_is_recreated_with_emission_disabled(self):
        self.contract.write_text(self.snapshot(self.legacy))
        self.compose.return_value = "a" * 12
        with patch("deploy.execute", side_effect=[
            self.snapshot(self.expanded), "APP_NOTIFICATIONS_TRADE_ENABLED=false", "t",
        ]) as query:
            self.server.schema_check(self.contract)
        self.compose.assert_called_once_with(["ps", "--all", "--quiet", "app"], "앱 컨테이너 조회 실패")
        self.assertEqual(query.call_count, 3)
        self.assertIn("FROM public.notifications", query.call_args.kwargs["stdin"])

    def test_new_reader_requires_migration_and_can_rollback_after_activation(self):
        self.contract.write_text(self.snapshot(self.expanded))
        with patch("deploy.execute", return_value=self.snapshot(self.legacy)):
            with self.assertRaises(DeploymentError):
                self.server.schema_check(self.contract)
        with patch("deploy.execute", return_value=self.snapshot(self.expanded)) as query:
            self.server.schema_check(self.contract)
        query.assert_called_once()

    def test_unapproved_or_unvalidated_check_does_not_qualify(self):
        self.contract.write_text(self.snapshot(self.legacy))
        other = notification_type_check(LEGACY_NOTIFICATION_TYPES + TRADE_NOTIFICATION_TYPES + ("EXTRA",))
        for row in (other, (*self.expanded[:-1], False)):
            with self.subTest(row=row):
                with patch("deploy.execute", return_value=self.snapshot(row)) as query:
                    with self.assertRaises(DeploymentError):
                        self.server.schema_check(self.contract)
                query.assert_called_once()

    def test_expanded_check_matches_packaged_contract(self):
        contract = Path(__file__).with_name("schema-contract.jsonl")
        self.assertIn(list(self.expanded), [json.loads(line) for line in contract.read_text().splitlines()])
