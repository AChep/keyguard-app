"""Launcher, completion-evidence and process-lifetime regressions."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import run_native_package_smoke as smoke


def evidence(nonce: str = "nonce", markers: tuple[str, ...] = smoke.REQUIRED_MARKERS) -> str:
    return f"{nonce}\n{smoke.SUCCESS_PREFIX} {' '.join(markers)}\n"


PUBLISH = (
    "import os,pathlib; "
    f"pathlib.Path(os.environ[{smoke.RESULT_PATH_ENV!r}]).write_text("
    f"os.environ[{smoke.RESULT_NONCE_ENV!r}] + '\\n' + "
    f"{smoke.SUCCESS_PREFIX + ' ' + ' '.join(smoke.REQUIRED_MARKERS)!r} + '\\n')"
)


class DesktopPackageSmokeTest(unittest.TestCase):
    def test_finds_each_packaged_launcher_layout(self) -> None:
        for target, relative in {
            "macos": "Keyguard.app/Contents/MacOS/Keyguard",
            "linux": "Keyguard/bin/Keyguard", "windows": "Keyguard/Keyguard.exe",
        }.items():
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                launcher = Path(tmp, relative)
                launcher.parent.mkdir(parents=True)
                launcher.touch()
                self.assertEqual(launcher.resolve(), smoke.find_launcher(Path(tmp), target))

    def test_rejects_missing_and_ambiguous_launchers(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "found none"):
                smoke.find_launcher(Path(tmp), "linux")
            for name in ("one", "two"):
                launcher = Path(tmp, name, "bin", "Keyguard")
                launcher.parent.mkdir(parents=True)
                launcher.touch()
            with self.assertRaisesRegex(RuntimeError, "Expected one linux"):
                smoke.find_launcher(Path(tmp), "linux")

    def test_requires_nonce_and_every_exact_completion_marker(self) -> None:
        self.assertTrue(smoke.validate_smoke_evidence(evidence(), "nonce").startswith(smoke.SUCCESS_PREFIX))
        with self.assertRaisesRegex(RuntimeError, "wrong nonce"):
            smoke.validate_smoke_evidence(evidence(), "different")
        for marker in smoke.REQUIRED_MARKERS:
            with self.subTest(marker=marker), self.assertRaisesRegex(RuntimeError, marker):
                smoke.validate_smoke_evidence(evidence(markers=tuple(x for x in smoke.REQUIRED_MARKERS if x != marker)), "nonce")
        with self.assertRaisesRegex(RuntimeError, "io=PASS"):
            smoke.validate_smoke_evidence(evidence().replace("io=PASS", "io=PASS_BOGUS"), "nonce")
        with self.assertRaisesRegex(RuntimeError, "completion record"):
            smoke.validate_smoke_evidence(evidence() + "extra\n", "nonce")

    def run_python(self, code: str, directory: Path, timeout: float = 5) -> str:
        return smoke._run([sys.executable, "-c", code], directory, os.environ.copy(), "nonce", timeout, None)

    def test_tls_runtime_is_diagnostic_but_success_is_required(self) -> None:
        for runtime in ("OkHttp/SunJSSE/JDK21", "OkHttp/OtherProvider/JDK25"):
            with self.subTest(runtime=runtime):
                record = evidence(markers=(*smoke.REQUIRED_MARKERS, f"tlsRuntime={runtime}"))
                self.assertIn(f"tlsRuntime={runtime}", smoke.validate_smoke_evidence(record, "nonce"))
                with self.assertRaisesRegex(RuntimeError, "tls=PASS"):
                    smoke.validate_smoke_evidence(record.replace("tls=PASS", "tls=FAIL"), "nonce")

    def test_launcher_without_stdout_still_requires_result_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIn(smoke.SUCCESS_PREFIX, self.run_python(PUBLISH, Path(tmp)))

    def test_detached_launcher_can_publish_after_parent_exits(self) -> None:
        child = "import time; time.sleep(0.15); " + PUBLISH
        code = f"import subprocess,sys; subprocess.Popen([sys.executable, '-c', {child!r}])"
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIn(smoke.SUCCESS_PREFIX, self.run_python(code, Path(tmp)))

    def test_nonzero_exit_includes_diagnostic(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "exited 7: missing JNI"):
                self.run_python("import sys; print('missing JNI', file=sys.stderr); sys.exit(7)", Path(tmp))

    def test_zero_exit_without_evidence_times_out(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                self.run_python("pass", Path(tmp), timeout=0.4)

    def test_deadline_includes_running_launcher_and_terminates_children(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp, "escaped-child")
            child = f"import time,pathlib; time.sleep(1.5); pathlib.Path({str(marker)!r}).touch()"
            code = f"import subprocess,sys,time; subprocess.Popen([sys.executable, '-c', {child!r}]); time.sleep(30)"
            started = time.monotonic()
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                self.run_python(code, Path(tmp), timeout=0.5)
            self.assertLess(time.monotonic() - started, 3)
            time.sleep(1.6)
            self.assertFalse(marker.exists(), "timed-out launcher left a live descendant")

    def test_flatpak_uses_app_private_cache_without_permission_overrides(self) -> None:
        with tempfile.TemporaryDirectory() as tmp, patch.object(smoke.Path, "home", return_value=Path(tmp)):
            with patch.object(smoke, "_run", return_value="passed") as run, patch("builtins.print"):
                smoke.run_smoke(Path(tmp), "linux", flatpak_app_id="com.artemchep.keyguard")
                command, directory, *_ = run.call_args.args
                self.assertEqual(["flatpak", "run", "--user", "com.artemchep.keyguard", smoke.SMOKE_ARGUMENT], command)
                self.assertEqual(Path(tmp, ".var/app/com.artemchep.keyguard/cache"), directory.parent)
                self.assertFalse(any("--filesystem" in arg for arg in command))

    def test_flatpak_forwards_nonce_and_result_path_explicitly(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            original = subprocess.Popen
            commands = []

            def launch(command, **kwargs):
                commands.append(command)
                return original([sys.executable, "-c", PUBLISH], **kwargs)

            with patch.object(smoke.subprocess, "Popen", side_effect=launch):
                smoke._run(["flatpak", "run", "--user", "app", smoke.SMOKE_ARGUMENT], Path(tmp), os.environ.copy(), "nonce", 5, "app")
            self.assertIn(f"--env={smoke.RESULT_NONCE_ENV}=nonce", commands[0])
            self.assertIn(f"--env={smoke.RESULT_PATH_ENV}={Path(tmp, 'result.txt')}", commands[0])


if __name__ == "__main__":
    unittest.main()
