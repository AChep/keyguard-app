#!/usr/bin/env python3
"""Inspect final packages for retired Bouncy Castle and SSHJ content."""

from __future__ import annotations

import argparse
import re
import struct
import sys
import zipfile
from pathlib import Path
from typing import Sequence


BC_ARTIFACT_NAME_MARKER = re.compile(
    r"(?:bouncycastle|bcprov|bcpkix|bcpg|bctls)(?:[-_.]|$)",
    re.IGNORECASE,
)
SSHJ_ARTIFACT_NAME_MARKER = re.compile(
    r"(?:sshj|asn-one)(?:[-_.]|$)",
    re.IGNORECASE,
)
BC_ARTIFACT_CONTENT_MARKERS = (
    b"org/bouncycastle",
    b"org.bouncycastle",
    b"BouncyCastleProvider",
    b"BouncyCastleJsseProvider",
)
SSHJ_ARTIFACT_CONTENT_MARKERS = (
    b"net/schmizz/sshj",
    b"net.schmizz.sshj",
    b"com/hierynomus/sshj",
    b"com.hierynomus.sshj",
    b"com/hierynomus/asn1",
    b"com.hierynomus.asn1",
)
ARTIFACT_CONTENT_MARKERS = BC_ARTIFACT_CONTENT_MARKERS + SSHJ_ARTIFACT_CONTENT_MARKERS
ARTIFACT_NAME_MARKER = re.compile(
    rf"(?:{BC_ARTIFACT_NAME_MARKER.pattern}|{SSHJ_ARTIFACT_NAME_MARKER.pattern})",
    re.IGNORECASE,
)
OKHTTP_JVM_JAR = re.compile(
    r"^okhttp-jvm-5\.4\.0(?:-[0-9a-f]+)?\.jar$",
    re.IGNORECASE,
)
OKHTTP_OPTIONAL_BC_ENTRIES = frozenset(
    {
        "META-INF/MANIFEST.MF",
        "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        "okhttp3/internal/platform/BouncyCastlePlatform.class",
    }
)
OKHTTP_ANDROID_BC_REFERENCE_OWNERS = frozenset(
    {
        b"Lokhttp3/internal/platform/android/BouncyCastleSocketAdapter;",
        b"Lokhttp3/internal/platform/PlatformInitializer;",
    }
)
OKHTTP_ANDROID_ALLOWED_BC_STRINGS = frozenset(
    {
        b"Lorg/bouncycastle/jsse/BCSSLParameters;",
        b"Lorg/bouncycastle/jsse/BCSSLSocket;",
        b"org.bouncycastle.jsse.provider.BouncyCastleJsseProvider",
        b"org/bouncycastle/jsse/BCSSLParameters",
        b"org/bouncycastle/jsse/BCSSLSocket",
        b"org/bouncycastle/jsse/provider/BouncyCastleJsseProvider",
    }
)


class PolicyError(Exception):
    """Raised when the Bouncy Castle boundary is violated."""


def scan_artifact(path: Path) -> list[str]:
    """Return BC/SSHJ markers from a final package file or package tree."""

    return _scan_artifact(path, ARTIFACT_NAME_MARKER, ARTIFACT_CONTENT_MARKERS)


def _scan_artifact(
    path: Path,
    name_marker: re.Pattern[str],
    content_markers: Sequence[bytes],
) -> list[str]:
    """Scan one package using the supplied artifact deny-list."""

    if not path.exists():
        raise PolicyError(f"artifact does not exist: {path}")
    files = (
        [path]
        if path.is_file()
        else sorted(candidate for candidate in path.rglob("*") if candidate.is_file())
    )
    findings: list[str] = []
    for candidate in files:
        relative = candidate.name if path.is_file() else str(candidate.relative_to(path))
        if name_marker.search(candidate.name):
            findings.append(f"{relative} (file name)")
        try:
            marker = _artifact_content_marker(candidate, content_markers)
            archive_findings = _archive_markers(
                candidate,
                relative,
                name_marker,
                content_markers,
            )
        except OSError as error:
            raise PolicyError(f"could not read packaged file {candidate}: {error}") from error
        if marker is not None:
            findings.append(f"{relative} ({marker.decode('ascii')})")
        findings.extend(archive_findings)
    return findings


def _artifact_content_marker(
    path: Path,
    content_markers: Sequence[bytes],
) -> bytes | None:
    """Scan a package incrementally without loading a large image into memory."""

    # Archive entries are inspected individually below. Scanning the ZIP bytes
    # as an opaque blob would prevent the narrowly scoped OkHttp stub exception
    # from identifying which component owns an optional provider reference.
    if zipfile.is_zipfile(path):
        return None
    with path.open("rb") as stream:
        return _stream_content_marker(stream, content_markers)


def _stream_content_marker(
    stream: object,
    content_markers: Sequence[bytes],
) -> bytes | None:
    """Scan a binary stream incrementally for a packaged BC class marker."""

    overlap_size = max(len(marker) for marker in content_markers) - 1
    overlap = b""
    while chunk := stream.read(1024 * 1024):
        searchable = (overlap + chunk).lower()
        marker = next(
            (item for item in content_markers if item.lower() in searchable),
            None,
        )
        if marker is not None:
            return marker
        overlap = searchable[-overlap_size:]
    return None


def _is_allowed_okhttp_jvm_entry(
    path: Path,
    entry_name: str,
    marker: bytes,
) -> bool:
    return (
        marker in BC_ARTIFACT_CONTENT_MARKERS
        and OKHTTP_JVM_JAR.fullmatch(path.name) is not None
        and entry_name in OKHTTP_OPTIONAL_BC_ENTRIES
    )


def _read_uleb128(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    for _ in range(5):
        if offset >= len(data):
            raise ValueError("truncated ULEB128")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte & 0x80 == 0:
            return value, offset
        shift += 7
    raise ValueError("oversized ULEB128")


def _dex_strings_and_class_descriptors(data: bytes) -> tuple[set[bytes], set[bytes]]:
    """Read the DEX tables needed to distinguish references from class definitions."""

    if len(data) < 0x70 or not data.startswith(b"dex\n"):
        raise ValueError("not a DEX file")

    def u32(offset: int) -> int:
        if offset + 4 > len(data):
            raise ValueError("truncated DEX table")
        return struct.unpack_from("<I", data, offset)[0]

    string_ids_size, string_ids_offset = u32(0x38), u32(0x3C)
    type_ids_size, type_ids_offset = u32(0x40), u32(0x44)
    class_defs_size, class_defs_offset = u32(0x60), u32(0x64)
    if string_ids_size > len(data) // 4 or type_ids_size > len(data) // 4:
        raise ValueError("invalid DEX table size")
    if class_defs_size > len(data) // 32:
        raise ValueError("invalid DEX class table size")

    strings: list[bytes] = []
    for index in range(string_ids_size):
        string_offset = u32(string_ids_offset + index * 4)
        _, payload_offset = _read_uleb128(data, string_offset)
        end = data.find(b"\0", payload_offset)
        if end < 0:
            raise ValueError("unterminated DEX string")
        strings.append(data[payload_offset:end])

    type_descriptors: list[bytes] = []
    for index in range(type_ids_size):
        string_index = u32(type_ids_offset + index * 4)
        if string_index >= len(strings):
            raise ValueError("invalid DEX type string index")
        type_descriptors.append(strings[string_index])

    class_descriptors: set[bytes] = set()
    for index in range(class_defs_size):
        class_index = u32(class_defs_offset + index * 32)
        if class_index >= len(type_descriptors):
            raise ValueError("invalid DEX class type index")
        class_descriptors.add(type_descriptors[class_index])
    return set(strings), class_descriptors


def _is_allowed_okhttp_android_dex(data: bytes) -> bool:
    try:
        strings, class_descriptors = _dex_strings_and_class_descriptors(data)
    except ValueError:
        return False
    bc_strings = {
        value
        for value in strings
        if b"org/bouncycastle" in value.lower()
        or b"org.bouncycastle" in value.lower()
    }
    if not bc_strings or not bc_strings.issubset(OKHTTP_ANDROID_ALLOWED_BC_STRINGS):
        return False
    if any(value.lower().startswith(b"lorg/bouncycastle/") for value in class_descriptors):
        return False
    return bool(OKHTTP_ANDROID_BC_REFERENCE_OWNERS.intersection(class_descriptors))


def _archive_entry_content_marker(
    path: Path,
    entry_name: str,
    data: bytes,
    content_markers: Sequence[bytes],
) -> bytes | None:
    present = tuple(
        marker for marker in content_markers if marker.lower() in data.lower()
    )
    if not present:
        return None
    allow_android_stub = entry_name.lower().endswith(".dex") and (
        _is_allowed_okhttp_android_dex(data)
    )
    for marker in present:
        if _is_allowed_okhttp_jvm_entry(path, entry_name, marker):
            continue
        if marker in BC_ARTIFACT_CONTENT_MARKERS and allow_android_stub:
            continue
        return marker
    return None


def _archive_markers(
    path: Path,
    display_name: str,
    name_marker: re.Pattern[str],
    content_markers: Sequence[bytes],
) -> list[str]:
    """Inspect ZIP/JAR/APK entries, including compressed DEX and class bodies."""

    if not zipfile.is_zipfile(path):
        return []
    findings: list[str] = []
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            entry_name = entry.filename
            encoded_name = entry_name.encode("utf-8", errors="ignore")
            marker = next(
                (
                    item
                    for item in content_markers
                    if item.lower() in encoded_name.lower()
                ),
                None,
            )
            if name_marker.search(Path(entry_name).name):
                findings.append(f"{display_name}!/{entry_name} (file name)")
            elif marker is not None:
                findings.append(
                    f"{display_name}!/{entry_name} ({marker.decode('ascii')})"
                )
            if entry.is_dir():
                continue
            with archive.open(entry) as stream:
                needs_structural_check = entry_name.lower().endswith(".dex") or (
                    OKHTTP_JVM_JAR.fullmatch(path.name) is not None
                    and entry_name in OKHTTP_OPTIONAL_BC_ENTRIES
                )
                if needs_structural_check:
                    marker = _archive_entry_content_marker(
                        path,
                        entry_name,
                        stream.read(),
                        content_markers,
                    )
                else:
                    marker = _stream_content_marker(stream, content_markers)
            if marker is not None:
                findings.append(
                    f"{display_name}!/{entry_name} ({marker.decode('ascii')})"
                )
    return findings


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path("."))
    parser.add_argument(
        "--artifact",
        action="append",
        type=Path,
        default=[],
        help="Reject BC/SSHJ names and classes in a final package or package tree.",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_arguments(sys.argv[1:] if argv is None else argv)
    repository = args.repository.resolve()
    try:
        for artifact in args.artifact:
            artifact_path = artifact if artifact.is_absolute() else repository / artifact
            findings = scan_artifact(artifact_path)
            if findings:
                raise PolicyError(
                    f"Bouncy Castle or SSHJ is present in packaged artifact {artifact_path}: "
                    + ", ".join(findings)
                )
    except (OSError, PolicyError, UnicodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("Packaged Bouncy Castle and SSHJ artifact policy passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
