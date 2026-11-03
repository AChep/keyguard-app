"""Exercise the pinned compiler installer without downloading or running binaries."""

import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "setup_protobuf", ROOT / ".github/actions/setup_protobuf/setup_protobuf.py",
)
setup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(setup)


def archive_bytes(windows=False):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        archive.writestr("bin/protoc.exe" if windows else "bin/protoc", b"compiler")
        archive.writestr("include/google/protobuf/descriptor.proto", b"schema")
    return data.getvalue()


class SetupProtobufTest(unittest.TestCase):
    def test_installs_verified_compiler_and_headers_for_every_runner(self):
        hosts = {
            ("Linux", "X64"): "linux-x86_64",
            ("Linux", "ARM64"): "linux-aarch_64",
            ("macOS", "X64"): "osx-x86_64",
            ("macOS", "ARM64"): "osx-aarch_64",
            ("Windows", "X64"): "win64",
            ("Windows", "ARM64"): "win64",
        }
        for host, platform in hosts.items():
            with self.subTest(host=host), tempfile.TemporaryDirectory(prefix="protoc test ") as temporary:
                windows = host[0] == "Windows"
                data = archive_bytes(windows)
                key = (host[0], "X64") if windows else host
                with patch.dict(setup.ARCHIVES, {key: (platform, hashlib.sha256(data).hexdigest())}), \
                        patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(data)) as download, \
                        patch.object(setup.subprocess, "check_output", return_value=f"libprotoc {setup.VERSION}\r\n") as run:
                    executable = setup.install(*host, Path(temporary))
                self.assertEqual(executable.read_bytes(), b"compiler")
                self.assertEqual(executable.name, "protoc.exe" if windows else "protoc")
                self.assertTrue((executable.parent.parent / "include/google/protobuf/descriptor.proto").is_file())
                if not windows:
                    self.assertTrue(executable.stat().st_mode & 0o111)
                download.assert_called_once_with(
                    f"https://github.com/protocolbuffers/protobuf/releases/download/v{setup.VERSION}/protoc-{setup.VERSION}-{platform}.zip",
                    timeout=60,
                )
                run.assert_called_once_with([str(executable), "--version"], text=True)

    def test_rejects_checksum_mismatch_before_extracting_or_running(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(archive_bytes())), \
                patch.object(setup.subprocess, "check_output") as run:
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                setup.install("Linux", "X64", Path(temporary))
            self.assertEqual(list(Path(temporary).iterdir()), [])
            run.assert_not_called()

    def test_rejects_unsupported_host_before_downloading(self):
        with patch.object(setup.urllib.request, "urlopen") as download:
            for host in (("Linux", "ARM"), ("Windows", "X86"), ("unknown", "X64")):
                with self.subTest(host=host), self.assertRaisesRegex(ValueError, "Unsupported protoc host"):
                    setup.install(*host, Path("unused"))
            download.assert_not_called()

    def test_wrong_version_does_not_publish_compiler(self):
        data = archive_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            environment = {
                "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64", "RUNNER_TEMP": temporary,
                "GITHUB_PATH": str(Path(temporary) / "path"), "GITHUB_ENV": str(Path(temporary) / "env"),
            }
            with patch.dict(os.environ, environment), \
                    patch.dict(setup.ARCHIVES, {("Linux", "X64"): ("linux-x86_64", hashlib.sha256(data).hexdigest())}), \
                    patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(data)), \
                    patch.object(setup.subprocess, "check_output", return_value="libprotoc 3.12.4\n"):
                with self.assertRaisesRegex(ValueError, "Expected libprotoc"):
                    setup.main()
            self.assertFalse(Path(environment["GITHUB_PATH"]).exists())
            self.assertFalse(Path(environment["GITHUB_ENV"]).exists())

    def test_publishes_pinned_path_compiler_and_headers(self):
        with tempfile.TemporaryDirectory(prefix="protoc test ") as temporary:
            directory = Path(temporary)
            executable = directory / "bin/protoc"
            path_file, env_file = directory / "path", directory / "env"
            path_file.write_text("existing-path\n", encoding="utf-8")
            env_file.write_text("EXISTING=value\n", encoding="utf-8")
            with patch.dict(os.environ, {
                "RUNNER_OS": "macOS", "RUNNER_ARCH": "ARM64", "RUNNER_TEMP": temporary,
                "GITHUB_PATH": str(path_file), "GITHUB_ENV": str(env_file),
            }), patch.object(setup, "install", return_value=executable):
                setup.main()
            self.assertEqual(path_file.read_text(), f"existing-path\n{executable.parent}\n")
            self.assertEqual(env_file.read_text(), f"EXISTING=value\nPROTOC={executable}\nPROTOC_INCLUDE={directory / 'include'}\n")


if __name__ == "__main__":
    unittest.main()
