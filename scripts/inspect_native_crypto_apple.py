#!/usr/bin/env python3
"""Inspect final linked arm64 Kotlin/Native nativeCrypto executables."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Sequence

from inspect_native_crypto_desktop import (
    KEYGUARD_C_PREFIX,
    MH_EXECUTE,
    InspectionError,
    inspect_macho_hardening,
    load_policy,
    parse_symbols,
)


CPU_TYPE_ARM64 = 0x0100000C


def require_arm64(path: Path, data: bytes) -> None:
    """Require a thin arm64 Mach-O image."""

    if len(data) < 8 or int.from_bytes(data[:4], "little") != 0xFEEDFACF:
        raise InspectionError(f"{path}: expected a thin 64-bit Mach-O image")
    cpu_type = int.from_bytes(data[4:8], "little", signed=False)
    if cpu_type != CPU_TYPE_ARM64:
        raise InspectionError(
            f"{path}: expected arm64 CPU type {hex(CPU_TYPE_ARM64)}, found {hex(cpu_type)}"
        )


def read_defined_symbols(path: Path) -> set[str]:
    """Read externally visible symbols from a final linked Mach-O image."""

    reader = shutil.which("nm") or shutil.which("llvm-nm")
    if reader is None:
        raise InspectionError("no nm/llvm-nm symbol reader is available")
    command = (
        [reader, "-gU", str(path)]
        if Path(reader).name == "nm"
        else [reader, "--defined-only", "--extern-only", str(path)]
    )
    environment = dict(os.environ)
    environment["LC_ALL"] = "C"
    result = subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
        env=environment,
    )
    if result.returncode != 0:
        raise InspectionError(f"{path}: symbol reader failed: {result.stderr.strip()}")
    return parse_symbols(result.stdout, Path(reader).name)


def inspect_executable(path: Path, policy_path: Path) -> None:
    """Inspect one final executable that statically links the Rust C ABI."""

    if not path.is_file() or path.stat().st_size == 0:
        raise InspectionError(f"missing non-empty linked executable: {path}")
    data = path.read_bytes()
    require_arm64(path, data)
    hardening = inspect_macho_hardening(path, data, frozenset({MH_EXECUTE}))

    policy = load_policy(policy_path)
    symbols = read_defined_symbols(path)
    missing = sorted(policy.exact - symbols)
    if missing:
        raise InspectionError(
            f"{path}: final static link omitted C ABI symbols: {', '.join(missing)}"
        )
    unexpected = sorted(
        symbol
        for symbol in symbols
        if symbol.startswith(KEYGUARD_C_PREFIX) and symbol not in policy.exact
    )
    if unexpected:
        raise InspectionError(
            f"{path}: unreviewed C ABI symbols: {', '.join(unexpected)}"
        )

    print(f"OK {path} ({hardening})")
    for symbol in sorted(policy.exact):
        print(f"  {symbol}")


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="+", type=Path)
    parser.add_argument(
        "--export-policy",
        type=Path,
        default=Path(".github/native-crypto-apple-exports.txt"),
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_arguments(sys.argv[1:] if argv is None else argv)
    try:
        for artifact in args.artifacts:
            inspect_executable(artifact, args.export_policy)
    except (InspectionError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"Validated {len(args.artifacts)} final linked arm64 Apple executables.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
