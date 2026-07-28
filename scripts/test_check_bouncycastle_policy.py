"""Tests for the packaged Bouncy Castle and SSHJ boundary policy."""

from __future__ import annotations

import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

from scripts.check_bouncycastle_policy import (
    main,
    scan_artifact,
)


class BouncyCastlePolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write(self, relative: str, content: str) -> Path:
        path = self.repository / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    @staticmethod
    def dex(strings: list[bytes], class_descriptors: list[bytes]) -> bytes:
        ordered_strings = list(dict.fromkeys(strings + class_descriptors))
        string_indices = {value: index for index, value in enumerate(ordered_strings)}
        string_ids_offset = 0x70
        type_ids_offset = string_ids_offset + len(ordered_strings) * 4
        class_defs_offset = type_ids_offset + len(class_descriptors) * 4
        string_data_offset = class_defs_offset + len(class_descriptors) * 32
        data = bytearray(string_data_offset)
        data[:8] = b"dex\n035\0"
        struct.pack_into("<II", data, 0x38, len(ordered_strings), string_ids_offset)
        struct.pack_into("<II", data, 0x40, len(class_descriptors), type_ids_offset)
        struct.pack_into("<II", data, 0x60, len(class_descriptors), class_defs_offset)
        for index, value in enumerate(ordered_strings):
            if len(value) >= 0x80:
                raise ValueError("test DEX helper only supports short strings")
            struct.pack_into("<I", data, string_ids_offset + index * 4, len(data))
            data.extend(bytes((len(value),)) + value + b"\0")
        for index, descriptor in enumerate(class_descriptors):
            struct.pack_into(
                "<I",
                data,
                type_ids_offset + index * 4,
                string_indices[descriptor],
            )
            struct.pack_into("<I", data, class_defs_offset + index * 32, index)
        return bytes(data)

    def test_artifact_scan_rejects_names_and_class_markers(self) -> None:
        package = self.repository / "package"
        package.mkdir()
        self.write("package/lib/bcprov-jdk18on.jar", "not even a valid jar")
        self.write("package/lib/sshj-0.40.0.jar", "not even a valid jar")
        self.write("package/lib/asn-one-0.6.0.jar", "not even a valid jar")
        self.write("package/classes.dex", "Lorg/bouncycastle/jce/provider/Foo;")
        self.write("package/ssh-classes.dex", "Lnet/schmizz/sshj/SSHClient;")

        findings = scan_artifact(package)

        self.assertGreaterEqual(sum("file name" in item for item in findings), 3)
        self.assertTrue(any("org/bouncycastle" in item for item in findings))
        self.assertTrue(any("net/schmizz/sshj" in item for item in findings))

    def test_artifact_scan_accepts_native_only_package(self) -> None:
        package = self.repository / "package"
        package.mkdir()
        self.write("package/lib/keyguard_crypto_jni.so", "native bytes")

        self.assertEqual([], scan_artifact(package))

    def test_cli_resolves_relative_artifacts_against_repository(self) -> None:
        package = self.repository / "package"
        package.mkdir()
        self.write("package/lib/keyguard_crypto_jni.so", "native bytes")

        with redirect_stdout(StringIO()):
            result = main(
                [
                    "--repository",
                    str(self.repository),
                    "--artifact",
                    "package",
                ],
            )

        self.assertEqual(0, result)

    def test_artifact_scan_allows_only_exact_okhttp_optional_bc_entries(self) -> None:
        package = self.repository / "okhttp-jvm-5.4.0-deadbeef.jar"
        with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF",
                b"Import-Package: org.bouncycastle.jsse;resolution:=optional",
            )
            archive.writestr(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                b"Import-Package: org.bouncycastle.jsse;resolution:=optional",
            )
            archive.writestr(
                "okhttp3/internal/platform/BouncyCastlePlatform.class",
                b"org/bouncycastle/jsse/BCSSLSocket",
            )

        self.assertEqual([], scan_artifact(package))

    def test_okhttp_exception_still_rejects_real_shaded_and_registered_bc(self) -> None:
        package = self.repository / "okhttp-jvm-5.4.0-deadbeef.jar"
        with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "okhttp3/internal/platform/BouncyCastlePlatform.class",
                b"org/bouncycastle/jsse/BCSSLSocket",
            )
            archive.writestr(
                "org/bouncycastle/jce/provider/Foo.class",
                b"real provider",
            )
            archive.writestr(
                "com/example/ShadedProvider.class",
                b"delegates to org/bouncycastle/jce/provider/Foo",
            )
            archive.writestr(
                "META-INF/services/java.security.Provider",
                b"org.bouncycastle.jce.provider.BouncyCastleProvider",
            )

        findings = scan_artifact(package)

        self.assertTrue(any("org/bouncycastle/jce/provider/Foo.class" in item for item in findings))
        self.assertTrue(any("ShadedProvider.class" in item for item in findings))
        self.assertTrue(any("java.security.Provider" in item for item in findings))

    def test_android_dex_allows_okhttp_references_but_rejects_bc_definitions(self) -> None:
        package = self.repository / "application.apk"
        adapter = b"Lokhttp3/internal/platform/android/BouncyCastleSocketAdapter;"
        socket = b"Lorg/bouncycastle/jsse/BCSSLSocket;"
        parameters = b"Lorg/bouncycastle/jsse/BCSSLParameters;"
        provider = b"org.bouncycastle.jsse.provider.BouncyCastleJsseProvider"
        with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "classes.dex",
                self.dex([socket, parameters, provider], [adapter]),
            )

        self.assertEqual([], scan_artifact(package))

        with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "classes.dex",
                self.dex([parameters, provider], [adapter, socket]),
            )
        findings = scan_artifact(package)
        self.assertTrue(any("classes.dex" in item for item in findings))

    def test_minified_okhttp_dex_requires_the_exact_platform_initializer_owner(self) -> None:
        package = self.repository / "application.apk"
        references = [
            b"Lorg/bouncycastle/jsse/BCSSLSocket;",
            b"Lorg/bouncycastle/jsse/BCSSLParameters;",
            b"org.bouncycastle.jsse.provider.BouncyCastleJsseProvider",
        ]
        initializer = b"Lokhttp3/internal/platform/PlatformInitializer;"
        with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes2.dex", self.dex(references, [initializer]))
        self.assertEqual([], scan_artifact(package))

        unrelated = b"Lcom/example/UnrelatedOwner;"
        with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes2.dex", self.dex(references, [unrelated]))
        findings = scan_artifact(package)
        self.assertTrue(any("classes2.dex" in item for item in findings))

    def test_artifact_scan_opens_compressed_package_entries(self) -> None:
        package = self.repository / "application.apk"
        with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "classes.dex",
                b"Lorg/bouncycastle/jce/provider/BouncyCastleProvider;",
            )

        findings = scan_artifact(package)

        self.assertTrue(any("classes.dex" in item for item in findings))


if __name__ == "__main__":
    unittest.main()
