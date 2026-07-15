#!/usr/bin/env python3
"""Execute the nativeCrypto KAT through an extracted Desktop package launcher."""

from __future__ import annotations

import argparse
import os
import platform
import secrets
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Iterable


SMOKE_ARGUMENT = "--native-crypto-packaged-smoke"
SUCCESS_PREFIX = "nativeCrypto packaged smoke passed:"
TLS_SUCCESS_MARKER = "tls=OkHttp/SunJSSE/JDK21"
RESULT_PATH_ENV = "KEYGUARD_NATIVE_CRYPTO_SMOKE_RESULT_PATH"
RESULT_NONCE_ENV = "KEYGUARD_NATIVE_CRYPTO_SMOKE_NONCE"
RESULT_TIMEOUT_SECONDS = 60.0


def platform_name(value: str) -> str:
    if value != "auto":
        return value
    system = platform.system().lower()
    if system == "darwin":
        return "macos"
    if system == "windows":
        return "windows"
    return "linux"


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
    candidates = sorted(
        candidate.resolve()
        for candidate in launcher_candidates(root, target_platform)
        if candidate.is_file()
    )
    if len(candidates) != 1:
        formatted = ", ".join(str(candidate) for candidate in candidates) or "none"
        raise RuntimeError(
            f"Expected one {target_platform} Keyguard launcher under {root}; found {formatted}",
        )
    return candidates[0]


def validate_smoke_evidence(evidence: str, nonce: str) -> str:
    lines = evidence.splitlines()
    if not lines or not secrets.compare_digest(lines[0], nonce):
        raise RuntimeError("Packaged nativeCrypto smoke evidence has the wrong nonce")
    output = "\n".join(lines[1:])
    missing_markers = tuple(
        marker
        for marker in (SUCCESS_PREFIX, TLS_SUCCESS_MARKER)
        if marker not in output
    )
    if missing_markers:
        raise RuntimeError(
            "Packaged nativeCrypto smoke evidence is missing marker(s): "
            + ", ".join(missing_markers),
        )
    return output


def await_smoke_evidence(result_path: Path, nonce: str) -> str:
    deadline = time.monotonic() + RESULT_TIMEOUT_SECONDS
    last_error: RuntimeError | None = None
    while time.monotonic() < deadline:
        try:
            evidence = result_path.read_text(encoding="utf-8")
        except FileNotFoundError:
            time.sleep(0.1)
            continue
        try:
            return validate_smoke_evidence(evidence, nonce)
        except RuntimeError as error:
            last_error = error
            time.sleep(0.1)
    if last_error is not None:
        raise last_error
    raise RuntimeError("Packaged nativeCrypto smoke did not publish result evidence")


def run_smoke(root: Path, target_platform: str) -> None:
    launcher = find_launcher(root, target_platform)
    with tempfile.TemporaryDirectory(prefix="keyguard-native-crypto-smoke-") as directory:
        result_path = Path(directory, "result.txt")
        nonce = secrets.token_hex(32)
        environment = os.environ.copy()
        environment[RESULT_PATH_ENV] = str(result_path)
        environment[RESULT_NONCE_ENV] = nonce
        result = subprocess.run(
            [str(launcher), SMOKE_ARGUMENT],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"Packaged nativeCrypto smoke exited {result.returncode}: "
                f"{result.stderr.strip() or 'no diagnostic'}",
            )
        evidence = await_smoke_evidence(result_path, nonce)
        print(result.stdout.strip() or evidence)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run the nativeCrypto ABI/capability/SHA KAT from an extracted Desktop package.",
    )
    parser.add_argument("package_root", type=Path)
    parser.add_argument(
        "--platform",
        choices=("auto", "linux", "macos", "windows"),
        default="auto",
    )
    args = parser.parse_args()

    run_smoke(args.package_root.resolve(), platform_name(args.platform))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
