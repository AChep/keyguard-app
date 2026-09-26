"""Synthetic package fixtures exercise omissions without building native code."""

from __future__ import annotations

import os
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts import verify_native_bundle as verify
from scripts import native_bundle_android as android


def elf(abi: str = "arm64-v8a", exports: frozenset[str] = frozenset(), alignment: int = 16384) -> bytes:
    elf_class, machine = android.ABI_ELF[abi]
    strings = b"\0" + b"".join(symbol.encode() + b"\0" for symbol in sorted(exports))
    string_offset = 448
    symbol_offset = (string_offset + len(strings) + 7) // 8 * 8
    symbol_count = len(exports) + 1
    symbol_size = 24 if elf_class == 2 else 16
    data = bytearray(symbol_offset + symbol_count * symbol_size)
    data[:16] = b"\x7fELF" + bytes((elf_class, 1, 1)) + bytes(9)
    if elf_class == 2:
        struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, machine, 1, 0, 64, 256, 0, 64, 56, 3, 64, 3, 0)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
        struct.pack_into("<IIQQQQQQ", data, 120, android.PT_GNU_STACK, 6, 0, 0, 0, 0, 0, 16)
        struct.pack_into("<IIQQQQQQ", data, 176, android.PT_GNU_RELRO, 4, 0, 0, 0, 0, 0, 1)
        struct.pack_into("<IIQQQQIIQQ", data, 320, 0, 3, 0, 0, string_offset, len(strings), 0, 0, 1, 0)
        struct.pack_into("<IIQQQQIIQQ", data, 384, 0, 11, 0, 0, symbol_offset, symbol_count * 24, 1, 0, 8, 24)
    else:
        struct.pack_into("<HHIIIIIHHHHHH", data, 16, 3, machine, 1, 0, 52, 256, 0, 52, 32, 3, 40, 3, 0)
        struct.pack_into("<IIIIIIII", data, 52, 1, 0, 0, 0, len(data), len(data), 5, alignment)
        struct.pack_into("<IIIIIIII", data, 84, android.PT_GNU_STACK, 0, 0, 0, 0, 0, 6, 16)
        struct.pack_into("<IIIIIIII", data, 116, android.PT_GNU_RELRO, 0, 0, 0, 0, 0, 4, 1)
        struct.pack_into("<IIIIIIIIII", data, 296, 0, 3, 0, 0, string_offset, len(strings), 0, 0, 1, 0)
        struct.pack_into("<IIIIIIIIII", data, 336, 0, 11, 0, 0, symbol_offset, symbol_count * 16, 1, 0, 4, 16)
    data[string_offset:string_offset + len(strings)] = strings
    name_offset = 1
    for index, symbol in enumerate(sorted(exports), 1):
        if elf_class == 2:
            struct.pack_into("<IBBHQQ", data, symbol_offset + index * 24, name_offset, 0x12, 0, 1, 1, 1)
        else:
            struct.pack_into("<IIIBBH", data, symbol_offset + index * 16, name_offset, 1, 1, 0x12, 0, 1)
        name_offset += len(symbol.encode()) + 1
    return bytes(data)


def macho(arch: str = "aarch64", file_type: int = 6) -> bytes:
    cpu = {"aarch64": 0x0100000C, "x86_64": 0x01000007}[arch]
    header = struct.pack("<IiiIIIII", 0xFEEDFACF, cpu, 0, file_type, 1, 72, 0x84, 0)
    segment = struct.pack("<II16sQQQQiiII", 0x19, 72, b"__TEXT", 0, 0, 0, 0, 5, 5, 0, 0)
    return header + segment


def archive(member: bytes) -> bytes:
    header = b"member.o/".ljust(16) + b"0".ljust(12) + b"0".ljust(6) + b"0".ljust(6) + b"100644".ljust(8) + str(len(member)).encode().ljust(10) + b"`\n"
    return b"!<arch>\n" + header + member + (b"\n" if len(member) % 2 else b"")


def pe_exports(
    symbols: tuple[str, ...] = ("exportedFunction",),
    machine: int = 0x8664,
) -> bytes:
    """PE32+ DLL with real exports and no COFF symbols (a stripped release)."""
    data = bytearray(1536)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3C, 64)
    data[64:68] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", data, 68, machine, 1, 0, 0, 0, 240, 0x2000)
    struct.pack_into("<H", data, 88, 0x20B)
    struct.pack_into("<I", data, 88 + 60, 512)
    struct.pack_into("<H", data, 88 + 70, 0x140)
    struct.pack_into("<I", data, 88 + 108, 1)
    struct.pack_into("<II", data, 88 + 112, 0x1000, 512)
    struct.pack_into("<IIII", data, 328 + 8, 1024, 0x1000, 1024, 512)
    struct.pack_into("<IIHHIIIIIII", data, 512, 0, 0, 0, 0, 0, 1, len(symbols), len(symbols), 0x1040, 0x1080, 0x10C0)
    offset = 256
    for index, symbol in enumerate(symbols):
        struct.pack_into("<I", data, 512 + 64 + index * 4, 0x1300 + index)
        struct.pack_into("<I", data, 512 + 128 + index * 4, 0x1000 + offset)
        struct.pack_into("<H", data, 512 + 192 + index * 2, index)
        encoded = symbol.encode() + b"\0"
        data[512 + offset:512 + offset + len(encoded)] = encoded
        offset += len(encoded)
    return bytes(data)


def apk(path: Path, abis: tuple[str, ...] = ("arm64-v8a",),
        omit: str | None = None, compressed: bool = False, aligned: bool = True) -> None:
    with zipfile.ZipFile(path, "w") as package:
        for abi in abis:
            for name in verify.ANDROID_MODULES:
                if name == omit:
                    continue
                info = zipfile.ZipInfo(f"lib/{abi}/libkeyguard_{name}_jni.so")
                info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
                if aligned:
                    offset = package.fp.tell() + 30 + len(info.filename.encode())
                    padding = (-offset) % 16384
                    if padding < 4:
                        padding += 16384
                    info.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
                package.writestr(info, elf(abi, verify.MODULES[name].exports("jni")))


class NativeBundleTest(unittest.TestCase):
    def setUp(self) -> None:
        printing = patch("builtins.print")
        printing.start()
        self.addCleanup(printing.stop)

    def test_android_requires_each_module_in_each_package(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            complete, missing = Path(tmp, "complete.apk"), Path(tmp, "missing.apk")
            apk(complete)
            apk(missing, omit="zxcvbn")
            verify.inspect_android([complete], ["arm64-v8a"])
            with self.assertRaisesRegex(verify.InspectionError, "zxcvbn ABI coverage"):
                verify.inspect_android([complete, missing], ["arm64-v8a"])

    def test_android_default_contract_covers_all_four_abis(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "complete.apk")
            apk(path, abis=android.DEFAULT_ABIS)
            self.assertEqual(0, verify.main(["android", str(path)]))

    def test_existing_hardening_requirements_remain_enforced(self) -> None:
        for offset, value, message in ((124, 7, "GNU_STACK is executable"),
                                       (176, 0, "missing GNU_RELRO")):
            data = bytearray(elf())
            struct.pack_into("<I", data, offset, value)
            with self.subTest(message=message), self.assertRaisesRegex(android.InspectionError, message):
                android.inspect_elf(android.Library("test", "arm64-v8a", data), 16384)
            with self.assertRaisesRegex(verify.InspectionError, message):
                verify.binary.inspect_elf_hardening(Path("test"), data)
        data = bytearray(macho())
        struct.pack_into("<I", data, 24, 0x20084)
        with self.assertRaisesRegex(verify.InspectionError, "ALLOW_STACK_EXECUTION"):
            verify.binary.inspect_macho_hardening(Path("test"), data)
        data = bytearray(macho())
        struct.pack_into("<I", data, 32 + 60, 7)
        with self.assertRaisesRegex(verify.InspectionError, "writable/executable"):
            verify.binary.inspect_macho_hardening(Path("test"), data)

    def test_static_archive_readers_avoid_embedded_rust_llvm_bitcode(self) -> None:
        commands = verify.binary.candidate_commands(Path("lib.a"))
        self.assertIn("--no-llvm-bc", commands[0])
        self.assertIn("--no-llvm-bc", commands[1])

    def test_android_split_grouping_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            arm, x86 = Path(tmp, "arm.apk"), Path(tmp, "x86.apk")
            apk(arm)
            apk(x86, abis=("x86_64",))
            with self.assertRaisesRegex(verify.InspectionError, "missing=.*x86_64"):
                verify.inspect_android([arm, x86], ["arm64-v8a", "x86_64"])
            verify.inspect_android([arm, x86], ["arm64-v8a", "x86_64"], split_package=True)
            with self.assertRaisesRegex(verify.InspectionError, "duplicate"):
                verify.inspect_android([arm, arm], ["arm64-v8a"], split_package=True)

    def test_android_rejects_compressed_and_misaligned_packages(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "app.apk")
            apk(path, compressed=True)
            with self.assertRaisesRegex(android.InspectionError, "compressed"):
                verify.inspect_android([path], ["arm64-v8a"])
            apk(path, aligned=False)
            with self.assertRaisesRegex(android.InspectionError, "boundary"):
                verify.inspect_android([path], ["arm64-v8a"])

    def test_android_checks_noncrypto_export_contracts(self) -> None:
        module = verify.MODULES["io"]
        required = module.exports("jni")
        for symbols, message in ((required - {module.jni_prefix + "txnWrite"}, "missing JNI"),
                                 (required | {module.jni_prefix + "unreviewed"}, "unreviewed JNI")):
            library = android.Library("io", "arm64-v8a", elf(exports=symbols))
            with self.subTest(message=message), self.assertRaisesRegex(android.InspectionError, message):
                android.inspect_exports(library, required, module.jni_prefix)

    def test_android_rejects_wrong_elf_architecture_and_alignment(self) -> None:
        with self.assertRaisesRegex(android.InspectionError, "machine"):
            android.inspect_elf(android.Library("wrong", "arm64-v8a", elf("x86_64")), 16384)
        with self.assertRaisesRegex(android.InspectionError, "alignment"):
            android.inspect_elf(android.Library("wrong", "arm64-v8a", elf(alignment=4096)), 16384)

    def test_target_architecture_is_checked_for_elf_macho_and_pe(self) -> None:
        pe = bytearray(128)
        pe[:2] = b"MZ"
        struct.pack_into("<I", pe, 0x3C, 64)
        pe[64:68] = b"PE\0\0"
        struct.pack_into("<H", pe, 68, 0x8664)
        for target, data, good, wrong in (("linux", elf(), "aarch64", "x86_64"),
                                          ("macos", macho(), "aarch64", "x86_64"),
                                          ("windows", bytes(pe), "x86_64", "aarch64")):
            verify.require_architecture(Path("test"), data, target, good)
            with self.subTest(target=target), self.assertRaisesRegex(verify.InspectionError, "wrong architecture"):
                verify.require_architecture(Path("test"), data, target, wrong)
            with self.assertRaises(verify.InspectionError):
                verify.require_architecture(Path("test"), data[:3], target, good)

    def test_pe_export_parser_reads_stripped_dll_without_external_tools(self) -> None:
        symbols = ("exportedFunction", "secondFunction")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "native.dll")
            path.write_bytes(pe_exports(symbols))
            with patch.object(verify.binary, "read_symbol_output", side_effect=AssertionError("external tool")):
                self.assertEqual(set(symbols), verify.read_exports(path, ".dll"))
            verify.binary.inspect_pe_hardening(path, path.read_bytes())

    def test_pe_hardening_is_architecture_agnostic(self) -> None:
        for machine in (0x8664, 0xAA64):
            with self.subTest(machine=machine):
                verify.binary.inspect_pe_hardening(
                    Path("native.dll"),
                    pe_exports(machine=machine),
                )

    def test_pe_coff_symbols_do_not_satisfy_export_contract(self) -> None:
        data = bytearray(pe_exports())
        # Add a defined external COFF symbol that is absent from the export table.
        struct.pack_into("<II", data, 64 + 12, 1400, 1)
        data[1400:1408] = b"private\0"
        struct.pack_into("<IhHBB", data, 1408, 0, 1, 0x20, 2, 0)
        struct.pack_into("<I", data, 1418, 4)
        exports = verify.binary.read_pe_exports(Path("native.dll"), data)
        self.assertEqual({"exportedFunction"}, exports)
        with self.assertRaisesRegex(verify.InspectionError, "missing exports: private"):
            verify.require_exports("native.dll", exports, frozenset({"private"}), None)

    def test_pe_export_parser_handles_missing_and_truncated_tables(self) -> None:
        data = bytearray(pe_exports())
        struct.pack_into("<II", data, 88 + 112, 0, 0)
        self.assertEqual(set(), verify.binary.read_pe_exports(Path("native.dll"), data))
        for offset, value, message in ((88 + 112, 0x2000, "unmapped"),
                                       (512 + 24, 0xFFFFFFFF, "truncated"),
                                       (512 + 32, 0, "missing PE export table"),
                                       (512 + 128, 0x2000, "unmapped")):
            data = bytearray(pe_exports())
            struct.pack_into("<I", data, offset, value)
            with self.subTest(offset=offset), self.assertRaisesRegex(verify.InspectionError, message):
                verify.binary.read_pe_exports(Path("native.dll"), data)
        with self.assertRaisesRegex(verify.InspectionError, "truncated"):
            verify.binary.read_pe_exports(Path("native.dll"), pe_exports()[:400])
        data = bytearray(pe_exports())
        struct.pack_into("<H", data, 512 + 192, 5)
        with self.assertRaisesRegex(verify.InspectionError, "invalid ordinal"):
            verify.binary.read_pe_exports(Path("native.dll"), data)

    def test_desktop_requires_helpers_and_each_library(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            names = [verify.library_filename(name, "macos") for name in verify.DESKTOP_MODULES]
            names += ["keyguard-lib", "keyguard-ssh-agent", "keyguard-gpg-agent"]
            for name in names:
                (root / name).write_bytes(macho())
                (root / name).chmod(0o755)

            def exports(path, *_args):
                if path.name == "keyguard-lib":
                    return set(verify.BRIDGE_EXPORTS)
                return set(next(module.exports("jni") for name, module in verify.MODULES.items() if name in verify.DESKTOP_MODULES and path.name == verify.library_filename(name, "macos")))

            with patch.object(verify, "read_exports", side_effect=exports):
                verify.inspect_desktop(root, "macos", "aarch64")
                for name in names[1:]:
                    path = root / name
                    data = path.read_bytes()
                    path.unlink()
                    with self.subTest(name=name), self.assertRaisesRegex(verify.InspectionError, "missing bundled"):
                        verify.inspect_desktop(root, "macos", "aarch64")
                    path.write_bytes(data)
                    path.chmod(0o755)
                if os.name != "nt":
                    (root / "keyguard-gpg-agent").chmod(0o644)
                    with self.assertRaisesRegex(verify.InspectionError, "not executable"):
                        verify.inspect_desktop(root, "macos", "aarch64")

    def test_apple_archive_checks_members_and_full_module_contract(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "libkeyguard_io_c.a")
            path.write_bytes(archive(macho()))
            with patch.object(verify, "read_exports", return_value=set(verify.MODULES["io"].exports("c"))):
                verify.inspect_apple([path], "aarch64", False)
                path.write_bytes(archive(macho("x86_64")))
                with self.assertRaisesRegex(verify.InspectionError, "wrong architecture"):
                    verify.inspect_apple([path], "aarch64", False)
            with self.assertRaisesRegex(verify.InspectionError, "truncated"):
                verify.archive_members(path, archive(macho())[:-10])

    def test_apple_app_and_crypto_link_contracts_are_distinct(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "test.kexe")
            path.write_bytes(macho(file_type=2))
            sentinels = {f"keyguard_{name}_abi_version" for name in verify.APPLE_APP_MODULES}
            with patch.object(verify, "read_exports", return_value=sentinels):
                verify.inspect_apple([path], "aarch64", False)
                with self.assertRaisesRegex(verify.InspectionError, "missing exports"):
                    verify.inspect_apple([path], "aarch64", True)
            with patch.object(verify, "read_exports", return_value=set(verify.MODULES["crypto"].exports("c"))):
                verify.inspect_apple([path], "aarch64", True)
                with self.assertRaisesRegex(verify.InspectionError, "keyguard_io_abi_version"):
                    verify.inspect_apple([path], "aarch64", False)

    def test_default_cli_runs_dependency_policy_and_packaged_launcher(self) -> None:
        with patch.object(verify, "inspect_desktop") as inspect, patch.object(verify, "inspect_dependency_policy") as policy, patch.object(verify, "run_smoke") as run:
            self.assertEqual(0, verify.main(["desktop", "package", "--platform", "linux", "--arch", "x86_64"]))
            inspect.assert_called_once()
            policy.assert_called_once_with([Path("package")])
            run.assert_called_once_with(Path("package"), "linux", None, None)
        with patch.object(verify, "dependencies") as policy:
            policy.scan_artifact.return_value = ["bcprov.jar"]
            with self.assertRaisesRegex(verify.InspectionError, "bcprov.jar"):
                verify.inspect_dependency_policy([Path("package")])


if __name__ == "__main__":
    unittest.main()
