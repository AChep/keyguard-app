import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import desktop_startup_acceptance as acceptance


class LoginReceiptTests(unittest.TestCase):
    def receipt(self):
        return {"phase": "begin", "status": "BLOCKED", "expected_window": "hidden", "platform": "Linux",
                "events": [{"phase": "begin", "session": "boot1/login1"}]}

    def test_requires_two_distinct_real_login_sessions(self):
        report = self.receipt()
        acceptance.advance(report, "armed", "boot1/login1", [], registration_observation="enabled")
        acceptance.advance(report, "login-enabled", "boot1/login2", [{"pid": 100, "argv": ["Keyguard", "--launch-at-login"]}], "hidden", launch_provenance="no-manual-launch")
        self.assertEqual("BLOCKED", report["status"])
        acceptance.advance(report, "disabled", "boot1/login2", [], registration_observation="disabled")
        acceptance.advance(report, "login-disabled", "boot1/login3", [], launch_provenance="no-manual-launch")
        self.assertEqual("PASS", report["observation_status"])
        self.assertEqual("BLOCKED", report["status"])
        self.assertEqual("BLOCKED", report["acceptance_status"])
        self.assertEqual(acceptance.UNVERIFIED_ACCEPTANCE_GATES, report["blocked_reasons"])
        self.assertEqual("installed-login-observations", report["case"])
        self.assertEqual("operator", report["events"][-1]["launch_provenance"]["origin"])
        self.assertFalse(report["events"][-1]["launch_provenance"]["independently_verified"])
        self.assertEqual("hidden", report["events"][2]["operator_window_observation"])

    def test_same_session_relaunch_cannot_pass(self):
        report = self.receipt()
        acceptance.advance(report, "armed", "boot1/login1", [], registration_observation="enabled")
        before = copy.deepcopy(report)
        with self.assertRaisesRegex(AssertionError, "No new desktop login"):
            acceptance.advance(report, "login-enabled", "boot1/login1", [{"pid": 1}], "hidden")
        self.assertEqual(before, report)

    def test_wrong_sequence_and_late_arming_fail(self):
        with self.assertRaises(AssertionError):
            acceptance.advance(self.receipt(), "login-disabled", "new", [])
        with self.assertRaisesRegex(AssertionError, "before logging out"):
            acceptance.advance(self.receipt(), "armed", "new", [])

    def test_zero_or_duplicate_processes_and_manual_launch_fail(self):
        report = self.receipt()
        acceptance.advance(report, "armed", "boot1/login1", [], registration_observation="enabled")
        for processes in ([], [{"pid": 1}, {"pid": 2}], [{"pid": 1, "argv": ["Keyguard"]}],
                          [{"pid": 1, "argv": ["Keyguard --launch-at-login"]}],
                          [{"pid": 1, "argv": ["--launch-at-login"]}]):
            with self.subTest(processes=processes), self.assertRaises(AssertionError):
                acceptance.advance(copy.deepcopy(report), "login-enabled", "boot1/login2", processes, "hidden")

    def test_wrong_window_observation_fails(self):
        report = self.receipt()
        acceptance.advance(report, "armed", "boot1/login1", [], registration_observation="enabled")
        with self.assertRaisesRegex(AssertionError, "Window behavior"):
            acceptance.advance(report, "login-enabled", "boot1/login2", [{"pid": 1, "argv": ["Keyguard", "--launch-at-login"]}], "visible")

    def test_sentinel_mutation_is_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            sentinel = Path(directory) / "vault-sentinel"
            sentinel.write_text("fixture-only")
            report = {"sentinels": [{"path": str(sentinel), "sha256": acceptance.sha256(sentinel)}]}
            acceptance.verify_sentinels(report)
            sentinel.write_text("changed")
            with self.assertRaises(AssertionError):
                acceptance.verify_sentinels(report)

    def test_unattested_account_is_blocked_without_touching_receipt(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            report = Path(directory) / "receipt.json"
            self.assertEqual(2, acceptance.main(["begin", "--report", str(report)]))
            self.assertFalse(report.exists())
            self.assertEqual("BLOCKED", json.loads(report.with_suffix(".failure.json").read_text())["status"])

    def test_macos_audit_session_changes_only_on_new_login(self):
        with patch.object(acceptance.platform, "system", return_value="Darwin"), patch.object(acceptance, "command", side_effect=[
            "session = Aqua\n asid = 101", "boot1", "session = Aqua\n asid = 101", "boot1",
            "session = Aqua\n asid = 102", "boot1",
        ]):
            first = acceptance.desktop_session()
            self.assertEqual(first, acceptance.desktop_session())
            self.assertNotEqual(first, acceptance.desktop_session())

    def test_registration_and_launch_provenance_are_required_operator_evidence(self):
        report = self.receipt()
        with self.assertRaises(acceptance.Blocked):
            acceptance.advance(report, "armed", "boot1/login1", [])
        acceptance.advance(report, "armed", "boot1/login1", [], registration_observation="enabled")
        for provenance in (None, "manual-launch", "unknown"):
            with self.subTest(provenance=provenance), self.assertRaises(acceptance.Blocked):
                acceptance.advance(copy.deepcopy(report), "login-enabled", "boot1/login2",
                                   [{"pid": 1, "argv": ["Keyguard", "--launch-at-login"]}],
                                   "hidden", launch_provenance=provenance)

    def test_external_receipt_claims_cannot_verify_missing_acceptance_gates(self):
        report = {"status": "PASS", "acceptance_status": "PASS",
                  "unverified_acceptance_gates": [], "audit": {"verified": True}}
        acceptance.acceptance_scope(report)
        self.assertEqual("BLOCKED", report["status"])
        self.assertEqual("BLOCKED", report["acceptance_status"])
        self.assertEqual(acceptance.UNVERIFIED_ACCEPTANCE_GATES, report["unverified_acceptance_gates"])

    def test_windows_matches_only_the_current_token_session(self):
        launcher = Path("/fixture/Keyguard.exe")
        rows = [{"ProcessId": 10, "ExecutablePath": str(launcher), "CommandLine": "Keyguard --launch-at-login", "SessionId": 2},
                {"ProcessId": 11, "ExecutablePath": str(launcher), "CommandLine": "Keyguard --launch-at-login", "SessionId": 3}]
        with patch.object(acceptance.platform, "system", return_value="Windows"), patch.object(acceptance, "command", side_effect=["2", json.dumps(rows)]) as invoke:
            processes = acceptance.matching_processes(launcher)
        self.assertEqual([10], [process["pid"] for process in processes])
        self.assertIn("GetOwnerSid", invoke.call_args_list[1].args[0][-1])
        self.assertIn("$owner.Sid -eq $sid", invoke.call_args_list[1].args[0][-1])

    def test_linux_rejects_other_users_and_known_other_login_sessions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            launcher = root / "Keyguard"
            launcher.write_bytes(b"fixture")
            (root / "self").mkdir()
            (root / "self/sessionid").write_text("101")
            for pid, uid, session in [(10, 1001, "101"), (11, 1002, "101"), (12, 1001, "102"), (13, 1001, "4294967295")]:
                proc = root / str(pid)
                proc.mkdir()
                (proc / "status").write_text(f"Uid:\t{uid}\t{uid}\t{uid}\t{uid}\n")
                (proc / "sessionid").write_text(session)
                (proc / "cmdline").write_bytes(b"Keyguard\0--launch-at-login\0")
                (proc / "exe").symlink_to(launcher)
            real_path = Path
            def proc_path(value):
                return {"/proc": root, "/proc/self": root / "self"}.get(str(value), real_path(value))
            with patch.object(acceptance.platform, "system", return_value="Linux"), patch.object(acceptance, "Path", side_effect=proc_path), patch.object(acceptance.os, "getuid", return_value=1001), patch.object(acceptance.os, "geteuid", return_value=1001):
                processes = acceptance.matching_processes(launcher)
            self.assertEqual([10, 13], sorted(process["pid"] for process in processes))
            self.assertEqual(["Keyguard", "--launch-at-login"], processes[0]["argv"])
            unscoped = next(process for process in processes if process["pid"] == 13)
            self.assertIn("unverified", unscoped["session_evidence"])

    def test_windows_argv_follows_command_line_to_argv_w(self):
        cases = {
            '"C:/K/Keyguard.exe" "--launch-at-login"': ["C:/K/Keyguard.exe", "--launch-at-login"],
            "C:\\K\\Keyguard.exe --launch-at-login": ["C:\\K\\Keyguard.exe", "--launch-at-login"],
            '"C:\\Program Files\\K\\" --x': ["C:\\Program Files\\K\\", "--x"],
            'app "a b"  c\td': ["app", "a b", "c", "d"],
            'app "" x': ["app", "", "x"],
            'app a\\\\"b c"': ["app", "a\\b c"],
            'app a\\\\\\"b c': ["app", 'a\\"b', "c"],
            'app a\\\\b': ["app", "a\\\\b"],
            'app "a""b" c': ["app", 'a"b c'],
            'app """x"""': ["app", '"x"'],
            "app ": ["app"],
            "": [""],
        }
        for command_line, argv in cases.items():
            with self.subTest(command_line=command_line):
                self.assertEqual(argv, acceptance.windows_argv(command_line))

    def test_windows_login_accepts_quoted_and_unquoted_argument(self):
        for command_line in ('"C:/K/Keyguard.exe" "--launch-at-login"', "C:/K/Keyguard.exe --launch-at-login"):
            with self.subTest(command_line=command_line):
                report = dict(self.receipt(), platform="Windows")
                acceptance.advance(report, "armed", "boot1/login1", [], registration_observation="enabled")
                process = {"pid": 1, "command": command_line, "argv": acceptance.windows_argv(command_line)}
                acceptance.advance(report, "login-enabled", "boot1/login2", [process], "hidden",
                                   launch_provenance="no-manual-launch")
                self.assertEqual("login-enabled", report["phase"])
        report = dict(self.receipt(), platform="Windows")
        acceptance.advance(report, "armed", "boot1/login1", [], registration_observation="enabled")
        quoted = '"C:/K/Keyguard.exe" "--launch-at-login --x"'
        with self.assertRaisesRegex(AssertionError, "login activation argument"):
            acceptance.advance(report, "login-enabled", "boot1/login2",
                               [{"pid": 1, "argv": acceptance.windows_argv(quoted)}], "hidden",
                               launch_provenance="no-manual-launch")

    def test_linux_argv_keeps_argument_boundaries(self):
        self.assertEqual(["Keyguard", "--launch-at-login"], acceptance.linux_argv(b"Keyguard\0--launch-at-login\0"))
        self.assertEqual(["Keyguard", "a b", ""], acceptance.linux_argv(b"Keyguard\0a b\0\0"))
        self.assertEqual([], acceptance.linux_argv(b""))

    def test_macos_filters_both_real_and_effective_user_and_records_session_gap(self):
        launcher = Path("/fixture/Keyguard")
        class Library:
            pass
        library = Library()
        def path_for_pid(pid, buffer, length):
            buffer.value = str(launcher).encode()
            return len(buffer.value)
        library.proc_pidpath = path_for_pid
        with patch.object(acceptance.platform, "system", return_value="Darwin"), patch.object(acceptance.ctypes, "CDLL", return_value=library), patch.object(acceptance.os, "getuid", return_value=1001), patch.object(acceptance.os, "geteuid", return_value=1001), patch.object(acceptance, "command", return_value="10 1001 1001 Keyguard\n11 1002 1002 Keyguard\n12 1001 1002 Keyguard"):
            processes = acceptance.matching_processes(launcher)
        self.assertEqual([10], [process["pid"] for process in processes])
        self.assertIn("unverified", processes[0]["session_evidence"])


if __name__ == "__main__":
    unittest.main()
