#!/usr/bin/env python3
"""Verify nativeCrypto Android ABI coverage and 16 KiB ELF compatibility.

The inspector is read-only. It accepts AAR/APK/AAB/ZIP files, directories, or
individual staged shared libraries. Multiple inputs are treated as one package
set, which also supports split APK inspection.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable, Sequence


DEFAULT_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
ABI_ELF = {
    "arm64-v8a": (2, 183),  # ELFCLASS64, EM_AARCH64
    "armeabi-v7a": (1, 40),  # ELFCLASS32, EM_ARM
    "x86": (1, 3),  # ELFCLASS32, EM_386
    "x86_64": (2, 62),  # ELFCLASS64, EM_X86_64
}
ELF_MAGIC = b"\x7fELF"
ET_DYN = 3
PT_LOAD = 1
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
SHT_DYNSYM = 11
SHT_NOBITS = 8
SHN_UNDEF = 0
JNI_EXPORT_PREFIX = (
    "Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_"
)
MAX_LIBRARY_SIZE = 256 * 1024 * 1024


class InspectionError(Exception):
    """Raised when an artifact violates a packaging invariant."""


@dataclass(frozen=True)
class Library:
    """A native library extracted from a package or staging directory."""

    label: str
    abi: str
    data: bytes
    apk_data_offset: int | None = None
    apk_is_stored: bool = False


def _unpack(fmt: str, data: bytes, offset: int, label: str) -> tuple[int, ...]:
    size = struct.calcsize(fmt)
    if offset < 0 or offset + size > len(data):
        raise InspectionError(f"{label}: truncated ELF structure at byte {offset}")
    return struct.unpack_from(fmt, data, offset)


def inspect_elf(library: Library, page_size: int) -> tuple[int, bool]:
    """Validate architecture, alignment, and hardening; return load/BIND_NOW."""

    data = library.data
    if len(data) < 20 or data[:4] != ELF_MAGIC:
        raise InspectionError(f"{library.label}: not an ELF shared library")
    elf_class = data[4]
    byte_order = data[5]
    if byte_order != 1:
        raise InspectionError(f"{library.label}: Android ELF must be little-endian")
    expected_class, expected_machine = ABI_ELF[library.abi]
    if elf_class != expected_class:
        raise InspectionError(
            f"{library.label}: ELF class {elf_class} does not match {library.abi}"
        )

    endian = "<"
    (elf_type,) = _unpack(f"{endian}H", data, 16, library.label)
    (machine,) = _unpack(f"{endian}H", data, 18, library.label)
    if elf_type != ET_DYN:
        raise InspectionError(f"{library.label}: expected ET_DYN, found {elf_type}")
    if machine != expected_machine:
        raise InspectionError(
            f"{library.label}: ELF machine {machine} does not match {library.abi}"
        )

    if elf_class == 1:
        (ph_offset,) = _unpack(f"{endian}I", data, 28, library.label)
        (ph_entry_size,) = _unpack(f"{endian}H", data, 42, library.label)
        (ph_count,) = _unpack(f"{endian}H", data, 44, library.label)
        minimum_entry_size = 32
        offset_field = 4
        virtual_address_field = 8
        file_size_field = 16
        flags_field = 24
        align_field = 28
        word_format = "I"
        dynamic_format = "II"
    elif elf_class == 2:
        (ph_offset,) = _unpack(f"{endian}Q", data, 32, library.label)
        (ph_entry_size,) = _unpack(f"{endian}H", data, 54, library.label)
        (ph_count,) = _unpack(f"{endian}H", data, 56, library.label)
        minimum_entry_size = 56
        offset_field = 8
        virtual_address_field = 16
        file_size_field = 32
        flags_field = 4
        align_field = 48
        word_format = "Q"
        dynamic_format = "QQ"
    else:
        raise InspectionError(f"{library.label}: unsupported ELF class {elf_class}")

    if ph_count == 0 or ph_count == 0xFFFF:
        raise InspectionError(f"{library.label}: unsupported program-header count")
    if ph_entry_size < minimum_entry_size:
        raise InspectionError(f"{library.label}: invalid program-header size")
    if ph_offset + ph_entry_size * ph_count > len(data):
        raise InspectionError(f"{library.label}: program-header table is truncated")

    load_count = 0
    has_non_executable_stack = False
    has_relro = False
    dynamic_range: tuple[int, int] | None = None
    for index in range(ph_count):
        entry = ph_offset + index * ph_entry_size
        (segment_type,) = _unpack(f"{endian}I", data, entry, library.label)
        (segment_flags,) = _unpack(
            f"{endian}I", data, entry + flags_field, library.label
        )
        if segment_type == PT_GNU_STACK:
            if segment_flags & PF_X:
                raise InspectionError(f"{library.label}: GNU_STACK is executable")
            has_non_executable_stack = True
        elif segment_type == PT_GNU_RELRO:
            has_relro = True
        elif segment_type == PT_DYNAMIC:
            (dynamic_offset,) = _unpack(
                f"{endian}{word_format}", data, entry + offset_field, library.label
            )
            (dynamic_size,) = _unpack(
                f"{endian}{word_format}", data, entry + file_size_field, library.label
            )
            dynamic_range = (dynamic_offset, dynamic_size)
        if segment_type != PT_LOAD:
            continue
        load_count += 1
        (file_offset,) = _unpack(
            f"{endian}{word_format}", data, entry + offset_field, library.label
        )
        (virtual_address,) = _unpack(
            f"{endian}{word_format}",
            data,
            entry + virtual_address_field,
            library.label,
        )
        (alignment,) = _unpack(
            f"{endian}{word_format}", data, entry + align_field, library.label
        )
        if alignment < page_size or alignment & (alignment - 1):
            raise InspectionError(
                f"{library.label}: PT_LOAD[{index}] alignment {alignment} "
                f"is not a power of two at least {page_size}"
            )
        if file_offset % page_size != virtual_address % page_size:
            raise InspectionError(
                f"{library.label}: PT_LOAD[{index}] file/virtual offsets are "
                f"not congruent modulo {page_size}"
            )

    if load_count == 0:
        raise InspectionError(f"{library.label}: ELF has no PT_LOAD segments")
    if not has_non_executable_stack:
        raise InspectionError(f"{library.label}: missing non-executable GNU_STACK")
    if not has_relro:
        raise InspectionError(f"{library.label}: missing GNU_RELRO")

    bind_now = False
    if dynamic_range is not None:
        dynamic_offset, dynamic_size = dynamic_range
        dynamic_entry_size = struct.calcsize(f"{endian}{dynamic_format}")
        if (
            dynamic_offset + dynamic_size > len(data)
            or dynamic_size % dynamic_entry_size
        ):
            raise InspectionError(f"{library.label}: dynamic table is malformed")
        for offset in range(
            dynamic_offset,
            dynamic_offset + dynamic_size,
            dynamic_entry_size,
        ):
            tag, value = _unpack(
                f"{endian}{dynamic_format}", data, offset, library.label
            )
            if tag == DT_NULL:
                break
            bind_now = bind_now or tag == DT_BIND_NOW
            bind_now = bind_now or tag == DT_FLAGS and bool(value & DF_BIND_NOW)
            bind_now = bind_now or tag == DT_FLAGS_1 and bool(value & DF_1_NOW)

    if library.apk_is_stored:
        if library.apk_data_offset is None:
            raise InspectionError(f"{library.label}: missing APK data offset")
        if library.apk_data_offset % page_size:
            raise InspectionError(
                f"{library.label}: uncompressed APK entry starts at "
                f"{library.apk_data_offset}, not a {page_size}-byte boundary"
            )
    return load_count, bind_now


def read_elf_exports(library: Library) -> set[str]:
    """Read globally visible dynamic symbols from an Android ELF image."""

    data = library.data
    elf_class = data[4]
    endian = "<"
    if elf_class == 1:
        (section_offset,) = _unpack(f"{endian}I", data, 32, library.label)
        (section_entry_size,) = _unpack(f"{endian}H", data, 46, library.label)
        (section_count,) = _unpack(f"{endian}H", data, 48, library.label)
        minimum_section_size = 40
        section_file_offset = 16
        section_size_offset = 20
        section_link_offset = 24
        section_entry_offset = 36
        section_word = "I"
        minimum_symbol_size = 16
    elif elf_class == 2:
        (section_offset,) = _unpack(f"{endian}Q", data, 40, library.label)
        (section_entry_size,) = _unpack(f"{endian}H", data, 58, library.label)
        (section_count,) = _unpack(f"{endian}H", data, 60, library.label)
        minimum_section_size = 64
        section_file_offset = 24
        section_size_offset = 32
        section_link_offset = 40
        section_entry_offset = 56
        section_word = "Q"
        minimum_symbol_size = 24
    else:
        raise InspectionError(f"{library.label}: unsupported ELF class {elf_class}")

    if section_offset == 0 or section_count in {0, 0xFFFF}:
        raise InspectionError(f"{library.label}: ELF section table is unavailable")
    if section_entry_size < minimum_section_size:
        raise InspectionError(f"{library.label}: invalid section-header size")
    if section_offset + section_entry_size * section_count > len(data):
        raise InspectionError(f"{library.label}: section-header table is truncated")

    sections: list[tuple[int, int, int, int, int]] = []
    for index in range(section_count):
        entry = section_offset + index * section_entry_size
        (section_type,) = _unpack(f"{endian}I", data, entry + 4, library.label)
        (file_offset,) = _unpack(
            f"{endian}{section_word}",
            data,
            entry + section_file_offset,
            library.label,
        )
        (size,) = _unpack(
            f"{endian}{section_word}",
            data,
            entry + section_size_offset,
            library.label,
        )
        (link,) = _unpack(f"{endian}I", data, entry + section_link_offset, library.label)
        (entry_size,) = _unpack(
            f"{endian}{section_word}",
            data,
            entry + section_entry_offset,
            library.label,
        )
        if section_type != SHT_NOBITS and file_offset + size > len(data):
            raise InspectionError(f"{library.label}: section {index} is truncated")
        sections.append((section_type, file_offset, size, link, entry_size))

    exports: set[str] = set()
    for index, (section_type, offset, size, link, entry_size) in enumerate(sections):
        if section_type != SHT_DYNSYM:
            continue
        if entry_size < minimum_symbol_size or size % entry_size:
            raise InspectionError(f"{library.label}: invalid DYNSYM section {index}")
        if link >= len(sections):
            raise InspectionError(f"{library.label}: invalid DYNSYM string-table link")
        _, strings_offset, strings_size, _, _ = sections[link]
        strings = data[strings_offset : strings_offset + strings_size]
        for symbol_offset in range(offset, offset + size, entry_size):
            (name_offset,) = _unpack(f"{endian}I", data, symbol_offset, library.label)
            if elf_class == 1:
                info = data[symbol_offset + 12]
                visibility = data[symbol_offset + 13] & 0x03
                (section_index,) = _unpack(
                    f"{endian}H", data, symbol_offset + 14, library.label
                )
            else:
                info = data[symbol_offset + 4]
                visibility = data[symbol_offset + 5] & 0x03
                (section_index,) = _unpack(
                    f"{endian}H", data, symbol_offset + 6, library.label
                )
            binding = info >> 4
            if section_index == SHN_UNDEF or binding not in {1, 2} or visibility not in {0, 3}:
                continue
            if name_offset >= len(strings):
                raise InspectionError(f"{library.label}: invalid dynamic-symbol name")
            end = strings.find(b"\0", name_offset)
            if end < 0:
                raise InspectionError(f"{library.label}: unterminated dynamic-symbol name")
            try:
                name = strings[name_offset:end].decode("ascii")
            except UnicodeDecodeError as error:
                raise InspectionError(
                    f"{library.label}: non-ASCII dynamic-symbol name"
                ) from error
            if name:
                exports.add(name)
    if not exports:
        raise InspectionError(f"{library.label}: ELF has no dynamic exports")
    return exports


def load_export_policy(path: Path) -> frozenset[str]:
    """Read the exact JNI export policy."""

    if not path.is_file():
        raise InspectionError(f"export policy does not exist: {path}")
    exports = frozenset(
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    )
    if not exports:
        raise InspectionError(f"export policy is empty: {path}")
    return exports


def inspect_exports(library: Library, required: frozenset[str]) -> int:
    """Validate the exact JNI export family and return its symbol count."""

    exports = read_elf_exports(library)
    missing = sorted(required - exports)
    unexpected = sorted(
        symbol
        for symbol in exports
        if symbol.startswith(JNI_EXPORT_PREFIX) and symbol not in required
    )
    if missing:
        raise InspectionError(f"{library.label}: missing JNI exports: {', '.join(missing)}")
    if unexpected:
        raise InspectionError(
            f"{library.label}: unreviewed JNI exports: {', '.join(unexpected)}"
        )
    return len(required)


def infer_abi(parts: Iterable[str], label: str) -> str:
    """Infer the Android ABI from path components."""

    matches = [part for part in parts if part in ABI_ELF]
    if len(set(matches)) != 1:
        raise InspectionError(f"{label}: cannot infer one Android ABI from its path")
    return matches[0]


def _read_binary(path: Path) -> bytes:
    size = path.stat().st_size
    if size > MAX_LIBRARY_SIZE:
        raise InspectionError(f"{path}: native library exceeds {MAX_LIBRARY_SIZE} bytes")
    return path.read_bytes()


def libraries_from_directory(directory: Path, library_name: str) -> list[Library]:
    """Collect matching staged libraries below a directory."""

    libraries: list[Library] = []
    for path in sorted(directory.rglob(library_name)):
        if not path.is_file():
            continue
        # The shared build directory also contains the Desktop host library.
        # Only paths carrying an Android ABI component belong to this gate.
        if not set(path.parts).intersection(ABI_ELF):
            continue
        abi = infer_abi(path.parts, str(path))
        libraries.append(Library(str(path), abi, _read_binary(path)))
    return libraries


def _zip_entry_data_offset(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    if archive.fp is None:
        raise InspectionError(f"{archive.filename}: ZIP file is closed")
    archive.fp.seek(info.header_offset)
    header = archive.fp.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise InspectionError(f"{archive.filename}: malformed local ZIP header")
    file_name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + file_name_length + extra_length


def libraries_from_archive(path: Path, library_name: str) -> list[Library]:
    """Collect matching libraries from an AAR, APK, AAB, or ZIP file."""

    libraries: list[Library] = []
    try:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                member = PurePosixPath(info.filename)
                if info.is_dir() or member.name != library_name:
                    continue
                abi = infer_abi(member.parts, f"{path}!{info.filename}")
                if info.file_size > MAX_LIBRARY_SIZE:
                    raise InspectionError(
                        f"{path}!{info.filename}: native library exceeds "
                        f"{MAX_LIBRARY_SIZE} bytes"
                    )
                is_apk = path.suffix.lower() == ".apk"
                if is_apk and info.compress_type != zipfile.ZIP_STORED:
                    raise InspectionError(
                        f"{path}!{info.filename}: APK JNI library is compressed"
                    )
                is_stored = is_apk and info.compress_type == zipfile.ZIP_STORED
                data_offset = _zip_entry_data_offset(archive, info) if is_stored else None
                libraries.append(
                    Library(
                        f"{path}!{info.filename}",
                        abi,
                        archive.read(info),
                        data_offset,
                        is_stored,
                    )
                )
    except zipfile.BadZipFile as error:
        raise InspectionError(f"{path}: invalid ZIP package") from error
    return libraries


def collect_libraries(paths: Sequence[Path], library_name: str) -> list[Library]:
    """Collect libraries from all supported input types."""

    libraries: list[Library] = []
    for path in paths:
        if not path.exists():
            raise InspectionError(f"artifact does not exist: {path}")
        if path.is_dir():
            libraries.extend(libraries_from_directory(path, library_name))
        elif path.name == library_name:
            abi = infer_abi(path.parts, str(path))
            libraries.append(Library(str(path), abi, _read_binary(path)))
        elif path.suffix.lower() in {".aar", ".apk", ".aab", ".zip"}:
            libraries.extend(libraries_from_archive(path, library_name))
        else:
            raise InspectionError(f"unsupported artifact type: {path}")
    return libraries


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="*", type=Path)
    parser.add_argument(
        "--file-list",
        type=Path,
        help="newline-delimited additional artifacts (for CI discovery)",
    )
    parser.add_argument("--library-name", default="libkeyguard_crypto_jni.so")
    parser.add_argument(
        "--export-policy",
        type=Path,
        default=Path(".github/native-crypto-jni-exports.txt"),
    )
    parser.add_argument(
        "--expected-abi",
        action="append",
        dest="expected_abis",
        choices=DEFAULT_ABIS,
        help="required ABI; repeat to override the four-ABI default",
    )
    parser.add_argument("--page-size", type=int, default=16 * 1024)
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
        print("ERROR: no Android artifacts supplied", file=sys.stderr)
        return 2
    if args.page_size <= 0 or args.page_size & (args.page_size - 1):
        print("ERROR: page size must be a positive power of two", file=sys.stderr)
        return 2

    expected = set(args.expected_abis or DEFAULT_ABIS)
    try:
        required_exports = load_export_policy(args.export_policy)
        libraries = collect_libraries(paths, args.library_name)
        if not libraries:
            raise InspectionError(f"no {args.library_name} libraries found")
        found = {library.abi for library in libraries}
        missing = sorted(expected - found)
        unexpected = sorted(found - expected)
        if missing:
            raise InspectionError(f"missing Android ABIs: {', '.join(missing)}")
        if unexpected:
            raise InspectionError(f"unexpected Android ABIs: {', '.join(unexpected)}")
        for library in libraries:
            segment_count, bind_now = inspect_elf(library, args.page_size)
            export_count = inspect_exports(library, required_exports)
            binding = "BIND_NOW" if bind_now else "lazy binding"
            print(
                f"OK {library.abi}: {library.label} "
                f"({segment_count} load segments, {export_count} JNI exports, "
                f"{args.page_size}-byte aligned, NX stack, GNU_RELRO, {binding})"
            )
    except (InspectionError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(f"Validated {len(libraries)} native libraries across {len(found)} ABIs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
