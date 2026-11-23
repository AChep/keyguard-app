import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "startup_package", Path(__file__).with_name("verify-macos-startup-package.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class StartupPackageEvidenceTest(unittest.TestCase):
    def assess(self, case="quiet", **overrides):
        values = dict(
            case=case, os_version="14.7", expected_major=14, signed=True,
            entitlements={"com.apple.security.app-sandbox": True, "com.apple.security.application-groups": ["qa.group"]},
            group="qa.group", observation={"on_console": True, "process_ids": [123], "visible_window_count": 0},
        )
        values.update(overrides)
        return MODULE.assess(**values)

    def test_quiet_requires_a_single_process_and_zero_visible_windows(self):
        self.assertEqual("PASS", self.assess()["status"])
        for pids, windows in (([], 0), ([1, 2], 0), ([1], 1)):
            result = self.assess(observation={"on_console": True, "process_ids": pids, "visible_window_count": windows})
            self.assertEqual("FAIL", result["status"])

    def test_required_gui_minimum_os_and_team_signing_cannot_silently_pass(self):
        for values in ({"signed": False}, {"os_version": "15.0"}, {"observation": None}):
            result = self.assess(**values)
            self.assertEqual("BLOCKED", result["status"])
            self.assertTrue(result["blocked_reasons"])

    def test_missing_app_group_fails_and_visible_case_requires_window(self):
        self.assertEqual("FAIL", self.assess(group="wrong.group")["status"])
        self.assertEqual("FAIL", self.assess(case="visible")["status"])
        self.assertEqual("PASS", self.assess(case="visible", observation={
            "on_console": True, "process_ids": [1], "visible_window_count": 1})["status"])


if __name__ == "__main__":
    unittest.main()
