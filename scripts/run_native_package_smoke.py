#!/usr/bin/env python3
"""Exercise an installed/extracted Desktop package with bounded, nonce-bound evidence."""

from __future__ import annotations

import argparse
import contextlib
import os
import platform
import secrets
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Iterable

SMOKE_ARGUMENT = "--native-packaged-smoke"
SUCCESS_PREFIX = "native packaged smoke passed:"
REQUIRED_MARKERS = (
    "crypto=PASS", "io=PASS", "zxcvbn=PASS", "instance=PASS",
    "desktopBridge=PASS", "sshHelper=PASS", "gpgHelper=PASS", "koin=PASS", "tls=PASS",
)
RESULT_PATH_ENV = "KEYGUARD_NATIVE_SMOKE_RESULT_PATH"
RESULT_NONCE_ENV = "KEYGUARD_NATIVE_SMOKE_NONCE"
RESULT_TIMEOUT_SECONDS = 60.0


def platform_name(value: str) -> str:
    if value != "auto":
        return value
    return {"darwin": "macos", "windows": "windows"}.get(platform.system().lower(), "linux")


def launcher_candidates(root: Path, target_platform: str) -> Iterable[Path]:
    if target_platform == "macos":
        yield from root.rglob("Keyguard.app/Contents/MacOS/Keyguard")
    elif target_platform == "windows":
        yield from root.rglob("Keyguard.exe")
    else:
        for candidate in root.rglob("Keyguard"):
            if candidate.parent.name == "bin":
                yield candidate


def find_launcher(root: Path, target_platform: str) -> Path:
    candidates = sorted({candidate.resolve() for candidate in launcher_candidates(root, target_platform) if candidate.is_file()})
    if len(candidates) != 1:
        formatted = ", ".join(map(str, candidates)) or "none"
        raise RuntimeError(f"Expected one {target_platform} Keyguard launcher under {root}; found {formatted}")
    return candidates[0]


def validate_smoke_evidence(evidence: str, nonce: str) -> str:
    lines = evidence.splitlines()
    if not lines or not secrets.compare_digest(lines[0].encode("utf-8"), nonce.encode("utf-8")):
        raise RuntimeError("Packaged native smoke evidence has the wrong nonce")
    if len(lines) != 2 or not lines[1].startswith(SUCCESS_PREFIX):
        raise RuntimeError("Packaged native smoke evidence has no completion record")
    markers = set(lines[1][len(SUCCESS_PREFIX):].split())
    missing = [marker for marker in REQUIRED_MARKERS if marker not in markers]
    if missing:
        raise RuntimeError("Packaged native smoke evidence is missing marker(s): " + ", ".join(missing))
    return lines[1]


class WindowsJob:
    """Own the launcher and detached GUI descendants until smoke completion."""

    def __init__(self) -> None:
        import ctypes
        from ctypes import wintypes

        class BasicLimits(ctypes.Structure):
            _fields_ = [("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64),
                        ("flags", wintypes.DWORD), ("minimum", ctypes.c_size_t),
                        ("maximum", ctypes.c_size_t), ("active", wintypes.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD),
                        ("scheduling", wintypes.DWORD)]

        class ExtendedLimits(ctypes.Structure):
            _fields_ = [("basic", BasicLimits), ("io", ctypes.c_uint64 * 6),
                        ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                        ("peak_process_memory", ctypes.c_size_t), ("peak_job_memory", ctypes.c_size_t)]

        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.kernel.CreateJobObjectW.restype = wintypes.HANDLE
        self.kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        self.kernel.SetInformationJobObject.restype = wintypes.BOOL
        self.kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.kernel.AssignProcessToJobObject.restype = wintypes.BOOL
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel.CloseHandle.restype = wintypes.BOOL
        self.handle = self.kernel.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = ExtendedLimits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            error = ctypes.WinError(ctypes.get_last_error())
            self.close()
            raise error

    def assign(self, process: subprocess.Popen) -> None:
        import ctypes
        if not self.kernel.AssignProcessToJobObject(self.handle, int(process._handle)):
            raise ctypes.WinError(ctypes.get_last_error())

    def close(self) -> None:
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


def _stop_process_tree(process: subprocess.Popen, job: WindowsJob | None) -> None:
    if job is not None:
        job.close()
    elif os.name != "nt":
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
    if process.poll() is None:
        process.kill()
    if process.stdin is not None:
        process.stdin.close()
    with contextlib.suppress(subprocess.TimeoutExpired):
        process.wait(timeout=5)


def _diagnostic(path: Path) -> str:
    with path.open("rb") as stream:
        stream.seek(max(0, path.stat().st_size - 32 * 1024))
        return stream.read().decode("utf-8", errors="replace").strip()


def _run(command: list[str], directory: Path, environment: dict[str, str], nonce: str,
         timeout: float, flatpak_app_id: str | None) -> str:
    result_path = directory / "result.txt"
    environment[RESULT_PATH_ENV] = str(result_path)
    environment[RESULT_NONCE_ENV] = nonce
    if flatpak_app_id:
        command[2:2] = [f"--env={RESULT_PATH_ENV}={result_path}", f"--env={RESULT_NONCE_ENV}={nonce}"]
    stdout_path, stderr_path = directory / "stdout.txt", directory / "stderr.txt"
    deadline = time.monotonic() + timeout
    job = WindowsJob() if os.name == "nt" else None
    process = None
    try:
        with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
            if job is not None:
                # Assign a waiting parent before it creates the GUI launcher,
                # avoiding a race with launchers that immediately detach.
                command = [sys.executable, "-c", "import subprocess,sys; token=sys.stdin.buffer.read(1); sys.exit(subprocess.call(sys.argv[1:]) if token == b'\\n' else 1)", *command]
            process = subprocess.Popen(command, stdout=stdout, stderr=stderr, env=environment,
                                       stdin=subprocess.PIPE if job else subprocess.DEVNULL,
                                       start_new_session=os.name != "nt")
            if job:
                job.assign(process)
                process.stdin.write(b"\n")
                process.stdin.close()
            last_error = None
            while time.monotonic() < deadline:
                code = process.poll()
                if code is not None and code != 0:
                    raise RuntimeError(f"Packaged native smoke exited {code}: {_diagnostic(stderr_path) or _diagnostic(stdout_path) or 'no diagnostic'}")
                try:
                    evidence = validate_smoke_evidence(result_path.read_text(encoding="utf-8"), nonce)
                except FileNotFoundError:
                    pass
                except (RuntimeError, UnicodeError) as error:
                    last_error = error
                else:
                    if code == 0:
                        return evidence
                time.sleep(min(0.1, max(0, deadline - time.monotonic())))
            detail = str(last_error) if last_error else "launcher did not finish and publish result evidence"
            diagnostic = _diagnostic(stderr_path) or _diagnostic(stdout_path)
            raise RuntimeError(f"Packaged native smoke timed out after {timeout:g}s: {detail}" + (f"; {diagnostic}" if diagnostic else ""))
    finally:
        if process is not None:
            _stop_process_tree(process, job)
        elif job is not None:
            job.close()
        if flatpak_app_id and time.monotonic() >= deadline:
            with contextlib.suppress(OSError, subprocess.TimeoutExpired):
                subprocess.run(["flatpak", "kill", flatpak_app_id], capture_output=True, timeout=5, check=False)


def run_smoke(root: Path, target_platform: str, launcher: Path | None = None,
              flatpak_app_id: str | None = None, timeout: float = RESULT_TIMEOUT_SECONDS) -> None:
    if timeout <= 0:
        raise ValueError("Smoke timeout must be positive")
    if launcher is not None and flatpak_app_id is not None:
        raise ValueError("--launcher and --flatpak-app-id are mutually exclusive")
    parent = None
    if flatpak_app_id:
        if target_platform != "linux" or "/" in flatpak_app_id or flatpak_app_id.startswith("."):
            raise ValueError("Expected a Linux Flatpak application ID")
        # Flatpak exposes this app-private host cache directory without adding
        # filesystem permissions; its absolute path also exists in the sandbox.
        parent = Path.home() / ".var/app" / flatpak_app_id / "cache"
        parent.mkdir(parents=True, exist_ok=True)
        command = ["flatpak", "run", "--user", flatpak_app_id, SMOKE_ARGUMENT]
    else:
        launcher = launcher.resolve() if launcher else find_launcher(root, target_platform)
        if not launcher.is_file():
            raise RuntimeError(f"Packaged Desktop launcher is missing: {launcher}")
        command = [str(launcher), SMOKE_ARGUMENT]
    with tempfile.TemporaryDirectory(prefix="keyguard-native-smoke-", dir=parent) as directory:
        print(_run(command, Path(directory), os.environ.copy(), secrets.token_hex(32), timeout, flatpak_app_id))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package_root", type=Path)
    parser.add_argument("--platform", choices=("auto", "linux", "macos", "windows"), default="auto")
    launch = parser.add_mutually_exclusive_group()
    launch.add_argument("--launcher", type=Path)
    launch.add_argument("--flatpak-app-id")
    args = parser.parse_args()
    run_smoke(args.package_root.resolve(), platform_name(args.platform), args.launcher, args.flatpak_app_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
