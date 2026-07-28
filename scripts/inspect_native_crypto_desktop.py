#!/usr/bin/env python3
"""Verify nativeCrypto JNI or Apple C export surfaces.

The inspector is read-only and supports ELF, Mach-O, PE, and static archives
through the platform's `nm`, `llvm-nm`, or `dumpbin` tool.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


KEYGUARD_C_PREFIX = "keyguard_crypto_"
KEYGUARD_JNI_PREFIX = (
    "Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_"
)
DEFAULT_JNI_SUFFIXES = frozenset({".so", ".dylib", ".dll"})
DUMPBIN_EXPORT = re.compile(
    r"^\s*\d+\s+[0-9A-Fa-f]+\s+[0-9A-Fa-f]+\s+(\S+)\s*$"
)
ELF_MAGIC = b"\x7fELF"
PT_DYNAMIC = 2
PT_GNU_STACK = 0x6474E551
PT_GNU_RELRO = 0x6474E552
PF_X = 0x1
DT_NULL = 0
DT_BIND_NOW = 24
DT_FLAGS = 30
DT_FLAGS_1 = 0x6FFFFFFB
DF_BIND_NOW = 0x8
DF_1_NOW = 0x1
MH_DYLIB = 0x6
MH_EXECUTE = 0x2
MH_DYLDLINK = 0x4
MH_TWOLEVEL = 0x80
MH_ALLOW_STACK_EXECUTION = 0x20000
LC_SEGMENT = 0x1
LC_SEGMENT_64 = 0x19
VM_PROT_WRITE = 0x2
VM_PROT_EXECUTE = 0x4
IMAGE_FILE_DLL = 0x2000
IMAGE_DLLCHARACTERISTICS_DYNAMIC_BASE = 0x40
IMAGE_DLLCHARACTERISTICS_NX_COMPAT = 0x100


class InspectionError(Exception):
    """Raised when an artifact violates its export contract."""


@dataclass(frozen=True)
class ExportPolicy:
    """Required exact symbols and symbol prefixes."""

    exact: frozenset[str]
    prefixes: tuple[str, ...]


def unpack(fmt: str, data: bytes, offset: int, label: str) -> tuple[int, ...]:
    """Unpack one checked binary structure."""

    size = struct.calcsize(fmt)
    if offset < 0 or offset + size > len(data):
        raise InspectionError(f"{label}: truncated binary structure at byte {offset}")
    return struct.unpack_from(fmt, data, offset)


def inspect_elf_hardening(path: Path, data: bytes) -> str:
    """Require an NX stack and GNU_RELRO in a Linux shared object."""

    if len(data) < 20 or data[:4] != ELF_MAGIC:
        raise InspectionError(f"{path}: not an ELF image")
    elf_class = data[4]
    byte_order = data[5]
    if byte_order != 1:
        raise InspectionError(f"{path}: expected little-endian ELF")
    if elf_class == 1:
        (header_offset,) = unpack("<I", data, 28, str(path))
        (entry_size,) = unpack("<H", data, 42, str(path))
        (header_count,) = unpack("<H", data, 44, str(path))
        minimum_entry_size = 32
        offset_field = 4
        size_field = 16
        flags_field = 24
        word_format = "I"
        dynamic_format = "II"
    elif elf_class == 2:
        (header_offset,) = unpack("<Q", data, 32, str(path))
        (entry_size,) = unpack("<H", data, 54, str(path))
        (header_count,) = unpack("<H", data, 56, str(path))
        minimum_entry_size = 56
        offset_field = 8
        size_field = 32
        flags_field = 4
        word_format = "Q"
        dynamic_format = "QQ"
    else:
        raise InspectionError(f"{path}: unsupported ELF class {elf_class}")
    if header_count in {0, 0xFFFF} or entry_size < minimum_entry_size:
        raise InspectionError(f"{path}: invalid ELF program-header table")
    if header_offset + entry_size * header_count > len(data):
        raise InspectionError(f"{path}: truncated ELF program-header table")

    has_nx_stack = False
    has_relro = False
    dynamic_range: tuple[int, int] | None = None
    for index in range(header_count):
        entry = header_offset + index * entry_size
        (segment_type,) = unpack("<I", data, entry, str(path))
        (flags,) = unpack("<I", data, entry + flags_field, str(path))
        if segment_type == PT_GNU_STACK:
            if flags & PF_X:
                raise InspectionError(f"{path}: GNU_STACK is executable")
            has_nx_stack = True
        elif segment_type == PT_GNU_RELRO:
            has_relro = True
        elif segment_type == PT_DYNAMIC:
            (offset,) = unpack(
                f"<{word_format}", data, entry + offset_field, str(path)
            )
            (size,) = unpack(f"<{word_format}", data, entry + size_field, str(path))
            dynamic_range = (offset, size)
    if not has_nx_stack:
        raise InspectionError(f"{path}: missing non-executable GNU_STACK")
    if not has_relro:
        raise InspectionError(f"{path}: missing GNU_RELRO")

    bind_now = False
    if dynamic_range is not None:
        offset, size = dynamic_range
        item_size = struct.calcsize(f"<{dynamic_format}")
        if offset + size > len(data) or size % item_size:
            raise InspectionError(f"{path}: malformed ELF dynamic table")
        for item in range(offset, offset + size, item_size):
            tag, value = unpack(f"<{dynamic_format}", data, item, str(path))
            if tag == DT_NULL:
                break
            bind_now = bind_now or tag == DT_BIND_NOW
            bind_now = bind_now or (tag == DT_FLAGS and bool(value & DF_BIND_NOW))
            bind_now = bind_now or (tag == DT_FLAGS_1 and bool(value & DF_1_NOW))
    binding = "BIND_NOW" if bind_now else "lazy binding (reported, not required)"
    return f"ELF NX stack, GNU_RELRO, {binding}"


def inspect_macho_hardening(
    path: Path,
    data: bytes,
    expected_file_types: frozenset[int] = frozenset({MH_DYLIB}),
) -> str:
    """Reject unsafe flags/segments in an expected Mach-O final image."""

    if len(data) < 4:
        raise InspectionError(f"{path}: truncated Mach-O header")
    magic = int.from_bytes(data[:4], "little")
    if magic == 0xFEEDFACE:
        header_format = "<IiiIIII"
        segment_command = LC_SEGMENT
        init_protection_offset = 44
    elif magic == 0xFEEDFACF:
        header_format = "<IiiIIIII"
        segment_command = LC_SEGMENT_64
        init_protection_offset = 60
    else:
        raise InspectionError(f"{path}: expected a thin little-endian Mach-O image")
    header = unpack(header_format, data, 0, str(path))
    file_type, command_count, command_bytes, flags = header[3:7]
    if file_type not in expected_file_types:
        expected = ", ".join(hex(value) for value in sorted(expected_file_types))
        raise InspectionError(
            f"{path}: Mach-O file type {hex(file_type)} is not one of {expected}"
        )
    if flags & MH_ALLOW_STACK_EXECUTION:
        raise InspectionError(f"{path}: MH_ALLOW_STACK_EXECUTION is set")
    required_flags = MH_DYLDLINK | MH_TWOLEVEL
    if flags & required_flags != required_flags:
        raise InspectionError(f"{path}: missing MH_DYLDLINK/MH_TWOLEVEL")

    offset = struct.calcsize(header_format)
    end = offset + command_bytes
    if end > len(data):
        raise InspectionError(f"{path}: truncated Mach-O load commands")
    segment_count = 0
    for _ in range(command_count):
        command, size = unpack("<II", data, offset, str(path))
        if size < 8 or offset + size > end:
            raise InspectionError(f"{path}: malformed Mach-O load command")
        if command == segment_command:
            segment_count += 1
            (protection,) = unpack(
                "<i", data, offset + init_protection_offset, str(path)
            )
            if protection & VM_PROT_WRITE and protection & VM_PROT_EXECUTE:
                raise InspectionError(f"{path}: writable/executable Mach-O segment")
        offset += size
    if offset != end or segment_count == 0:
        raise InspectionError(f"{path}: invalid Mach-O load-command extent")
    return "Mach-O no executable-stack flag, no W+X segment, DYLDLINK, TWOLEVEL"


def inspect_pe_hardening(path: Path, data: bytes) -> str:
    """Require ASLR and NX compatibility in a Windows x64 DLL."""

    if len(data) < 64 or data[:2] != b"MZ":
        raise InspectionError(f"{path}: not a PE image")
    (pe_offset,) = unpack("<I", data, 0x3C, str(path))
    if data[pe_offset : pe_offset + 4] != b"PE\0\0":
        raise InspectionError(f"{path}: missing PE signature")
    machine, _, _, _, _, optional_size, characteristics = unpack(
        "<HHIIIHH", data, pe_offset + 4, str(path)
    )
    if machine != 0x8664:
        raise InspectionError(f"{path}: expected Windows x64 machine type")
    if not characteristics & IMAGE_FILE_DLL:
        raise InspectionError(f"{path}: PE image is not marked as a DLL")
    optional_offset = pe_offset + 24
    (magic,) = unpack("<H", data, optional_offset, str(path))
    if magic != 0x20B or optional_size < 72:
        raise InspectionError(f"{path}: expected a complete PE32+ optional header")
    (dll_characteristics,) = unpack(
        "<H", data, optional_offset + 70, str(path)
    )
    required = (
        IMAGE_DLLCHARACTERISTICS_DYNAMIC_BASE
        | IMAGE_DLLCHARACTERISTICS_NX_COMPAT
    )
    if dll_characteristics & required != required:
        raise InspectionError(f"{path}: missing DYNAMIC_BASE or NX_COMPAT")
    return "PE32+ DYNAMIC_BASE, NX_COMPAT"


def inspect_hardening(path: Path) -> str:
    """Inspect final-image hardening where the library format carries it."""

    if path.suffix.lower() == ".a":
        return "static archive; final Mach-O image owns hardening"
    data = path.read_bytes()
    if path.suffix.lower() == ".so":
        return inspect_elf_hardening(path, data)
    if path.suffix.lower() == ".dylib":
        return inspect_macho_hardening(path, data)
    if path.suffix.lower() == ".dll":
        return inspect_pe_hardening(path, data)
    raise InspectionError(f"{path}: unsupported hardening format")


def load_policy(path: Path) -> ExportPolicy:
    """Read exact and `prefix:` entries from the checked-in policy file."""

    if not path.is_file():
        raise InspectionError(f"export policy does not exist: {path}")
    exact: set[str] = set()
    prefixes: list[str] = []
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("prefix:"):
            prefix = line.removeprefix("prefix:").strip()
            if not prefix:
                raise InspectionError(f"{path}:{line_number}: empty export prefix")
            prefixes.append(prefix)
        else:
            exact.add(line)
    if not exact:
        raise InspectionError(f"{path}: policy needs at least one exact export")
    return ExportPolicy(frozenset(exact), tuple(prefixes))


def candidate_commands(path: Path) -> list[list[str]]:
    """Return symbol-reader commands appropriate for a library suffix."""

    suffix = path.suffix.lower()
    if suffix == ".so":
        return [
            ["llvm-nm", "--defined-only", "--extern-only", "--dynamic", str(path)],
            ["nm", "-D", "--defined-only", str(path)],
        ]
    if suffix in {".dylib", ".a"}:
        return [
            ["llvm-nm", "--defined-only", "--extern-only", str(path)],
            ["nm", "-gU", str(path)],
        ]
    if suffix == ".dll":
        return [
            ["llvm-nm", "--defined-only", "--extern-only", str(path)],
            ["dumpbin", "/nologo", "/exports", str(path)],
        ]
    raise InspectionError(f"unsupported desktop library type: {path}")


def read_symbol_output(path: Path) -> tuple[str, str]:
    """Run the first available symbol reader and return its name/output."""

    failures: list[str] = []
    environment = dict(os.environ)
    environment["LC_ALL"] = "C"
    for command in candidate_commands(path):
        executable = shutil.which(command[0])
        if executable is None:
            continue
        command[0] = executable
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )
        if result.returncode == 0:
            return Path(executable).name, result.stdout
        failures.append(f"{Path(executable).name}: {result.stderr.strip()}")
    detail = "; ".join(failures) if failures else "no supported symbol reader found"
    raise InspectionError(f"{path}: could not read exports ({detail})")


def normalize_symbol(symbol: str) -> str:
    """Normalize Mach-O and 32-bit Windows C symbol decoration."""

    if symbol.startswith("__imp_"):
        symbol = symbol.removeprefix("__imp_")
    if symbol.startswith("_") and symbol[1:].startswith(
        (KEYGUARD_C_PREFIX, "Java_", "JNI_")
    ):
        symbol = symbol[1:]
    return re.sub(r"@\d+$", "", symbol)


def parse_symbols(output: str, reader: str = "nm") -> set[str]:
    """Extract normalized symbol-like tokens from nm or dumpbin output."""

    symbols: set[str] = set()
    for line in output.splitlines():
        if reader.lower().startswith("dumpbin"):
            match = DUMPBIN_EXPORT.match(line)
            if match:
                symbols.add(normalize_symbol(match.group(1)))
            continue
        fields = line.split()
        if len(fields) >= 3 and len(fields[-2]) == 1 and fields[-2].isalpha():
            symbols.add(normalize_symbol(fields[-1]))
    return symbols


def inspect_library(
    path: Path,
    policy: ExportPolicy,
    allowed_suffixes: frozenset[str],
    api_prefix: str,
) -> tuple[str, set[str], str]:
    """Inspect one library and return the reader and relevant exports."""

    if not path.is_file():
        raise InspectionError(f"desktop library does not exist: {path}")
    if path.suffix.lower() not in allowed_suffixes:
        raise InspectionError(f"unsupported native library type: {path}")
    if path.stat().st_size == 0:
        raise InspectionError(f"desktop library is empty: {path}")

    reader, output = read_symbol_output(path)
    symbols = parse_symbols(output, reader)
    missing_exact = sorted(policy.exact - symbols)
    missing_prefixes = [
        prefix for prefix in policy.prefixes if not any(s.startswith(prefix) for s in symbols)
    ]
    if missing_exact:
        raise InspectionError(f"{path}: missing exports: {', '.join(missing_exact)}")
    if missing_prefixes:
        raise InspectionError(
            f"{path}: no export matches prefixes: {', '.join(missing_prefixes)}"
        )

    unexpected_api_exports = sorted(
        symbol
        for symbol in symbols
        if symbol.startswith(api_prefix) and symbol not in policy.exact
    )
    if unexpected_api_exports:
        raise InspectionError(
            f"{path}: unreviewed API exports: {', '.join(unexpected_api_exports)}"
        )

    relevant = {
        symbol
        for symbol in symbols
        if symbol in policy.exact
        or any(symbol.startswith(prefix) for prefix in policy.prefixes)
    }
    return reader, relevant, inspect_hardening(path)


def collect_binaries(
    paths: Sequence[Path], allowed_suffixes: frozenset[str]
) -> list[Path]:
    """Resolve files and recursively discover native libraries in directories."""

    binaries: list[Path] = []
    for path in paths:
        if not path.exists():
            raise InspectionError(f"artifact does not exist: {path}")
        if path.is_dir():
            binaries.extend(
                candidate
                for candidate in sorted(path.rglob("*"))
                if candidate.is_file()
                and candidate.suffix.lower() in allowed_suffixes
                and "keyguard_crypto" in candidate.name
                and not set(candidate.parts).intersection(
                    {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
                )
                and "jniLibs" not in candidate.parts
                and "android" not in candidate.parts
            )
        else:
            binaries.append(path)
    unique = list(dict.fromkeys(path.resolve() for path in binaries))
    if not unique:
        raise InspectionError("no nativeCrypto desktop libraries found")
    return unique


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="*", type=Path)
    parser.add_argument(
        "--file-list",
        type=Path,
        help="newline-delimited additional libraries (for CI discovery)",
    )
    parser.add_argument(
        "--export-policy",
        type=Path,
        default=Path(".github/native-crypto-jni-exports.txt"),
    )
    parser.add_argument(
        "--api-prefix",
        default=KEYGUARD_JNI_PREFIX,
        help="reject unreviewed exported symbols in this API family",
    )
    parser.add_argument(
        "--suffix",
        action="append",
        dest="suffixes",
        help="library suffix to discover; repeat to override JNI defaults",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_arguments(sys.argv[1:] if argv is None else argv)
    paths = list(args.artifacts)
    if args.file_list is not None:
        if not args.file_list.is_file():
            print(f"ERROR: file list does not exist: {args.file_list}", file=sys.stderr)
            return 2
        paths.extend(
            Path(line.strip())
            for line in args.file_list.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        )
    if not paths:
        print("ERROR: no desktop artifacts supplied", file=sys.stderr)
        return 2

    try:
        policy = load_policy(args.export_policy)
        allowed_suffixes = frozenset(
            suffix if suffix.startswith(".") else f".{suffix}"
            for suffix in (args.suffixes or DEFAULT_JNI_SUFFIXES)
        )
        binaries = collect_binaries(paths, allowed_suffixes)
        for binary in binaries:
            reader, exports, hardening = inspect_library(
                binary,
                policy,
                allowed_suffixes,
                args.api_prefix,
            )
            print(f"OK {binary} ({reader}; {hardening})")
            for symbol in sorted(exports):
                print(f"  {symbol}")
    except (InspectionError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(f"Validated exports and applicable hardening for {len(binaries)} libraries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
