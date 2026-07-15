"""Tests for Desktop package launcher discovery."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from scripts.run_native_crypto_desktop_package_smoke import (
    RESULT_NONCE_ENV,
    RESULT_PATH_ENV,
    SUCCESS_PREFIX,
    TLS_SUCCESS_MARKER,
    find_launcher,
    run_smoke,
    validate_smoke_evidence,
)


class DesktopPackageSmokeTest(unittest.TestCase):
    def test_finds_each_packaged_launcher_layout(self) -> None:
        layouts = {
            "macos": Path("Keyguard.app/Contents/MacOS/Keyguard"),
            "linux": Path("Keyguard/bin/Keyguard"),
            "windows": Path("Keyguard/Keyguard.exe"),
        }
        for target_platform, relative_path in layouts.items():
            with self.subTest(target_platform=target_platform), tempfile.TemporaryDirectory() as tmp:
                launcher = Path(tmp, relative_path)
                launcher.parent.mkdir(parents=True)
                launcher.touch()
                self.assertEqual(
                    launcher.resolve(),
                    find_launcher(Path(tmp), target_platform),
                )

    def test_rejects_ambiguous_packages(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            for directory in ("one", "two"):
                launcher = Path(tmp, directory, "Keyguard", "bin", "Keyguard")
                launcher.parent.mkdir(parents=True)
                launcher.touch()
            with self.assertRaisesRegex(RuntimeError, "Expected one linux"):
                find_launcher(Path(tmp), "linux")

    def test_requires_nonce_and_completion_markers(self) -> None:
        evidence = f"nonce\n{SUCCESS_PREFIX} abi=1 sha256=PASS {TLS_SUCCESS_MARKER}\n"
        self.assertIn(SUCCESS_PREFIX, validate_smoke_evidence(evidence, "nonce"))

        with self.assertRaisesRegex(RuntimeError, "wrong nonce"):
            validate_smoke_evidence(evidence, "different")
        with self.assertRaisesRegex(RuntimeError, TLS_SUCCESS_MARKER):
            validate_smoke_evidence(
                f"nonce\n{SUCCESS_PREFIX} abi=1 sha256=PASS\n",
                "nonce",
            )

    def test_requires_result_evidence_even_when_launcher_has_no_stdout(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            for target_platform, relative_path in (
                ("linux", Path("Keyguard/bin/Keyguard")),
                ("windows", Path("Keyguard/Keyguard.exe")),
            ):
                launcher = Path(tmp, target_platform, relative_path)
                launcher.parent.mkdir(parents=True)
                launcher.touch()

            def publish_evidence(*_args, **kwargs):
                environment = kwargs["env"]
                Path(environment[RESULT_PATH_ENV]).write_text(
                    f"{environment[RESULT_NONCE_ENV]}\n"
                    f"{SUCCESS_PREFIX} abi=1 sha256=PASS {TLS_SUCCESS_MARKER}\n",
                    encoding="utf-8",
                )
                return SimpleNamespace(returncode=0, stderr="", stdout="")

            with patch("subprocess.run") as subprocess_run, patch("builtins.print"):
                subprocess_run.side_effect = publish_evidence
                run_smoke(Path(tmp, "linux"), "linux")
                run_smoke(Path(tmp, "windows"), "windows")


if __name__ == "__main__":
    unittest.main()
