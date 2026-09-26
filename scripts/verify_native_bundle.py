#!/usr/bin/env python3
"""Verify final native bundles using one platform-aware packaging contract."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

if __package__:
    from . import check_bouncycastle_policy as dependencies
    from . import native_bundle_android as android
    from . import native_bundle_binary as binary
    from .run_native_package_smoke import run_smoke
else:
    import check_bouncycastle_policy as dependencies
    import native_bundle_android as android
    import native_bundle_binary as binary
    from run_native_package_smoke import run_smoke

REPOSITORY = Path(__file__).resolve().parent.parent
InspectionError = binary.InspectionError
LIBRARY_SUFFIX = {"linux": ".so", "macos": ".dylib", "windows": ".dll"}


@dataclass(frozen=True)
class NativeModule:
    name: str
    jni_class: str | None
    jni_methods: tuple[str, ...]
    c_methods: tuple[str, ...]

    @property
    def jni_prefix(self) -> str:
        return f"Java_{self.jni_class}_"

    def exports(self, interface: str) -> frozenset[str]:
        if self.name == "crypto":
            suffix = "jni" if interface == "jni" else "apple"
            return binary.load_policy(REPOSITORY / f".github/native-crypto-{suffix}-exports.txt").exact
        prefix = self.jni_prefix if interface == "jni" else f"keyguard_{self.name}_"
        methods = self.jni_methods if interface == "jni" else self.c_methods
        return frozenset(prefix + method for method in methods)


# These are reviewed ABI contracts, not symbols inferred from the artifact being
# checked. ZIP is native on Apple only; instance is desktop/macOS only.
MODULES = {
    "crypto": NativeModule("crypto", "com_artemchep_keyguard_nativecrypto_NativeCryptoJni", (), ()),
    "io": NativeModule("io", "com_artemchep_keyguard_util_io_NativeIoJni", (
        "abiVersion", "directoryOpen", "directoryClose", "txnBegin", "txnBeginAtDirectory",
        "txnWrite", "txnCommit", "txnAbort", "scratchOpen", "scratchWrite", "scratchSeal",
        "scratchLength", "scratchReadAt", "scratchClose", "sweepOrphans",
    ), (
        "abi_version", "directory_open", "directory_close", "txn_begin", "txn_begin_at_directory",
        "txn_write", "txn_commit", "txn_abort", "scratch_open", "scratch_write", "scratch_seal",
        "scratch_length", "scratch_read_at", "scratch_close", "sweep_orphans",
    )),
    "zxcvbn": NativeModule("zxcvbn", "com_artemchep_keyguard_util_zxcvbn_NativeZxcvbnJni",
                          ("abiVersion", "estimate"), ("abi_version", "estimate")),
    "instance": NativeModule("instance", "com_artemchep_keyguard_util_instance_NativeInstanceJni",
                            ("abiVersion", "acquireOrActivate", "waitEvent", "stop", "close", "lastError"),
                            ("abi_version", "acquire_or_activate", "wait_event", "stop", "close", "last_error", "clear_error")),
    "zip": NativeModule("zip", None, (), (
        "abi_version", "writer_open", "writer_begin_entry", "writer_write", "writer_end_entry",
        "writer_finish", "writer_abort", "reader_open", "reader_next_entry", "reader_read", "reader_close",
    )),
}
DESKTOP_MODULES = ("crypto", "io", "zxcvbn", "instance")
ANDROID_MODULES = ("crypto", "io", "zxcvbn")
APPLE_APP_MODULES = ("crypto", "io", "zxcvbn", "zip")
BRIDGE_EXPORTS = frozenset((
    "autoType", "getSystemAccentColor", "biometricsIsSupported", "biometricsVerify",
    "biometricsPrepareEnrollment", "biometricsDeleteCredential", "biometricsTransformSecret",
    "keychainAddPassword",
    "keychainGetPassword", "keychainDeletePassword", "keychainContainsPassword",
    "postNotification", "registerNativeGlobalHotKey", "unregisterNativeGlobalHotKey", "freePointer",
))


def require_architecture(path: Path, data: bytes, target_platform: str, arch: str) -> None:
    if target_platform == "linux":
        if len(data) < 20 or data[:6] != b"\x7fELF\x02\x01":
            raise InspectionError(f"{path}: expected a little-endian ELF64 image")
        expected = {"x86_64": 62, "aarch64": 183}[arch]
        actual = binary.unpack("<H", data, 18, str(path))[0]
        if binary.unpack("<H", data, 16, str(path))[0] not in {2, 3}:
            raise InspectionError(f"{path}: expected an ELF executable/shared library")
    elif target_platform == "macos":
        if len(data) < 8 or data[:4] != b"\xcf\xfa\xed\xfe":
            raise InspectionError(f"{path}: expected a thin little-endian Mach-O 64 image")
        expected = {"x86_64": 0x01000007, "aarch64": 0x0100000C}[arch]
        actual = binary.unpack("<I", data, 4, str(path))[0]
    else:
        if len(data) < 64 or data[:2] != b"MZ":
            raise InspectionError(f"{path}: expected a PE image")
        offset = binary.unpack("<I", data, 0x3C, str(path))[0]
        if data[offset:offset + 4] != b"PE\0\0":
            raise InspectionError(f"{path}: missing PE signature")
        expected = {"x86_64": 0x8664, "aarch64": 0xAA64}[arch]
        actual = binary.unpack("<H", data, offset + 4, str(path))[0]
    if actual != expected:
        raise InspectionError(f"{path}: wrong architecture for {arch}: {actual:#x}, expected {expected:#x}")


def require_exports(label: str, symbols: set[str], required: frozenset[str], prefix: str | None) -> None:
    missing = sorted(required - symbols)
    unexpected = sorted(symbol for symbol in symbols if prefix and symbol.startswith(prefix) and symbol not in required)
    if missing:
        raise InspectionError(f"{label}: missing exports: {', '.join(missing)}")
    if unexpected:
        raise InspectionError(f"{label}: unreviewed API exports: {', '.join(unexpected)}")


def read_exports(path: Path, suffix: str, macos: bool = False) -> set[str]:
    if suffix == ".dll":
        return binary.read_pe_exports(path, path.read_bytes())
    reader, output = binary.read_symbol_output(path, suffix)
    symbols = binary.parse_symbols(output, reader)
    # Mach-O C symbols carry an underscore, including the older desktop bridge.
    if macos:
        symbols = {symbol[1:] if symbol.startswith("_") and symbol[1:] in BRIDGE_EXPORTS else symbol for symbol in symbols}
    return symbols


def library_filename(module: str, target_platform: str) -> str:
    prefix = "" if target_platform == "windows" else "lib"
    return f"{prefix}keyguard_{module}_jni{LIBRARY_SUFFIX[target_platform]}"


def find_resources(root: Path, target_platform: str) -> Path:
    name = library_filename("crypto", target_platform)
    candidates = sorted({path.parent.resolve() for path in root.rglob(name) if path.is_file()})
    if len(candidates) != 1:
        raise InspectionError(f"{root}: expected one bundled native resource directory; found {len(candidates)}")
    return candidates[0]


def inspect_desktop(root: Path, target_platform: str, arch: str) -> None:
    if not root.is_dir():
        raise InspectionError(f"{root}: expected an extracted/installed package directory")
    resources = find_resources(root, target_platform)
    suffix = LIBRARY_SUFFIX[target_platform]
    libraries = [(library_filename(name, target_platform), MODULES[name].exports("jni"), MODULES[name].jni_prefix) for name in DESKTOP_MODULES]
    libraries.append(("keyguard-lib.dll" if target_platform == "windows" else "keyguard-lib", BRIDGE_EXPORTS, None))
    for name, exports, prefix in libraries:
        path = resources / name
        if not path.is_file():
            raise InspectionError(f"{root}: missing bundled library {name}")
        data = path.read_bytes()
        require_architecture(path, data, target_platform, arch)
        require_exports(str(path), read_exports(path, suffix, target_platform == "macos"), exports, prefix)
        # Preserve the existing format-specific crypto hardening policy. The
        # other libraries previously had no package hardening gate.
        hardening = binary.inspect_hardening(path) if name == library_filename("crypto", target_platform) else "exports and architecture"
        print(f"OK {path} ({hardening})")
    for name in ("keyguard-ssh-agent", "keyguard-gpg-agent"):
        path = resources / (name + (".exe" if target_platform == "windows" else ""))
        if not path.is_file():
            raise InspectionError(f"{root}: missing bundled helper {path.name}")
        require_architecture(path, path.read_bytes(), target_platform, arch)
        if target_platform != "windows" and not os.access(path, os.X_OK):
            raise InspectionError(f"{path}: bundled helper is not executable")
        print(f"OK {path} (helper architecture and executable permissions)")


def inspect_android(paths: Sequence[Path], expected_abis: Sequence[str], split_package: bool = False) -> None:
    if split_package and any(path.suffix.lower() != ".apk" for path in paths):
        raise InspectionError("--split-package requires APK inputs from one package")
    groups = [paths] if split_package else [[path] for path in paths]
    for group in groups:
        for name in ANDROID_MODULES:
            module = MODULES[name]
            libraries = android.collect_libraries(group, f"libkeyguard_{name}_jni.so")
            found = {library.abi for library in libraries}
            expected = set(expected_abis)
            if found != expected:
                raise InspectionError(f"{', '.join(map(str, group))}: {name} ABI coverage differs: missing={sorted(expected-found)}, unexpected={sorted(found-expected)}")
            # Native files for the same ABI may not be spread over two splits.
            if split_package and len(libraries) != len(found):
                raise InspectionError(f"{name}: duplicate native ABI in split package")
            for library in libraries:
                segments, bind_now = android.inspect_elf(library, 16 * 1024)
                count = android.inspect_exports(library, module.exports("jni"), module.jni_prefix)
                print(f"OK {library.label} ({segments} segments, {count} exports, 16 KiB aligned, {'BIND_NOW' if bind_now else 'lazy binding'})")


def archive_members(path: Path, data: bytes) -> list[bytes]:
    if not data.startswith(b"!<arch>\n"):
        raise InspectionError(f"{path}: expected an ar static archive")
    members = []
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        if len(header) != 60 or header[58:] != b"`\n":
            raise InspectionError(f"{path}: malformed archive header")
        try:
            size = int(header[48:58])
        except ValueError as error:
            raise InspectionError(f"{path}: invalid archive member size") from error
        offset += 60
        if size < 0 or offset + size > len(data):
            raise InspectionError(f"{path}: truncated archive member")
        member = data[offset:offset + size]
        name = header[:16].rstrip()
        if name.startswith(b"#1/"):
            try:
                name_size = int(name[3:])
            except ValueError as error:
                raise InspectionError(f"{path}: invalid extended member name") from error
            if name_size > len(member):
                raise InspectionError(f"{path}: truncated extended member name")
            name, member = member[:name_size].rstrip(b"\0"), member[name_size:]
        if name not in {b"/", b"//", b"/SYM64/"} and not name.startswith(b"__.SYMDEF"):
            members.append(member)
        offset += size + size % 2
    if not members:
        raise InspectionError(f"{path}: empty static archive")
    return members


def inspect_apple(paths: Sequence[Path], arch: str, require_crypto_exports: bool) -> None:
    for path in paths:
        data = path.read_bytes()
        if path.suffix == ".a":
            module = next((module for name, module in MODULES.items() if path.name.startswith(f"libkeyguard_{name}_")), None)
            if module is None:
                raise InspectionError(f"{path}: unrecognized Keyguard static library")
            for member in archive_members(path, data):
                require_architecture(path, member, "macos", arch)
            require_exports(str(path), read_exports(path, ".a", True), module.exports("c"), f"keyguard_{module.name}_")
        else:
            require_architecture(path, data, "macos", arch)
            binary.inspect_macho_hardening(path, data, frozenset({binary.MH_EXECUTE}))
            symbols = read_exports(path, ".dylib", True)
            if require_crypto_exports:
                require_exports(str(path), symbols, MODULES["crypto"].exports("c"), "keyguard_crypto_")
            else:
                required = frozenset(f"keyguard_{name}_abi_version" for name in APPLE_APP_MODULES)
                require_exports(str(path), symbols, required, None)
        print(f"OK {path} (Apple arm64 final-link/static-archive contract)")


def inspect_dependency_policy(paths: Sequence[Path]) -> None:
    for path in paths:
        violations = dependencies.scan_artifact(path)
        if violations:
            raise InspectionError("Forbidden packaged dependencies:\n" + "\n".join(violations))
        print(f"OK {path} (packaged dependency policy)")


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    desktop = modes.add_parser("desktop", help="inspect and execute a final Desktop package")
    desktop.add_argument("root", type=Path)
    desktop.add_argument("--platform", choices=tuple(LIBRARY_SUFFIX), required=True)
    desktop.add_argument("--arch", choices=("x86_64", "aarch64"), required=True)
    launch = desktop.add_mutually_exclusive_group()
    launch.add_argument("--launcher", type=Path)
    launch.add_argument("--flatpak-app-id")
    mobile = modes.add_parser("android", help="inspect each final Android/Wear package independently")
    mobile.add_argument("artifacts", type=Path, nargs="+")
    mobile.add_argument("--expected-abi", choices=android.DEFAULT_ABIS, action="append")
    mobile.add_argument("--split-package", action="store_true", help="treat APK inputs as splits of one package")
    apple = modes.add_parser("apple", help="inspect linked Apple app tests or native static archives")
    apple.add_argument("artifacts", type=Path, nargs="+")
    apple.add_argument("--arch", choices=("aarch64",), required=True)
    apple.add_argument("--require-crypto-exports", action="store_true", help="require the complete crypto C ABI in a crypto test executable")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_arguments(sys.argv[1:] if argv is None else argv)
    try:
        if args.mode == "desktop":
            inspect_desktop(args.root, args.platform, args.arch)
            inspect_dependency_policy([args.root])
            run_smoke(args.root, args.platform, args.launcher, args.flatpak_app_id)
        elif args.mode == "android":
            inspect_android(args.artifacts, args.expected_abi or android.DEFAULT_ABIS, args.split_package)
            inspect_dependency_policy(args.artifacts)
        else:
            inspect_apple(args.artifacts, args.arch, args.require_crypto_exports)
    except (InspectionError, android.InspectionError, dependencies.PolicyError, OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
