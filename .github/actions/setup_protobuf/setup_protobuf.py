"""Install a checksum-pinned protoc for the GitHub Actions runner host."""

import hashlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile


VERSION = "36.2"
# SHA-256 digests published with the official release assets:
# https://github.com/protocolbuffers/protobuf/releases/tag/v36.2
ARCHIVES = {
    ("Linux", "X64"): ("linux-x86_64", "121f6c7afe1d4d0e3ea6aab9432038599250134cbf4474cb1167d2c7decd4278"),
    ("Linux", "ARM64"): ("linux-aarch_64", "8b8f18bd2b30346efbc698dd5a73dd7c805f3ef8380f6dfc95c768f3f1852f6a"),
    ("macOS", "X64"): ("osx-x86_64", "228cc7add4616cc14ca5e80dee83209d44449a7aee95a914ae748fa374efb078"),
    ("macOS", "ARM64"): ("osx-aarch_64", "9cd98a532c5c5e0c4161314de0225de27e4c8a323917b6ea7b1b714d3ae23466"),
    ("Windows", "X64"): ("win64", "f0c128dc0d8492eceece83bb459a4c0e316764b929ffbf1aa416357fd644edd3"),
}


def install(runner_os: str, runner_arch: str, temporary_directory: Path) -> Path:
    # Upstream has no Windows ARM64 archive. The Windows 11 ARM runner can run
    # the x64 host compiler; generated code is independent of its architecture.
    archive_arch = "X64" if runner_os == "Windows" and runner_arch == "ARM64" else runner_arch
    try:
        platform, expected_checksum = ARCHIVES[runner_os, archive_arch]
    except KeyError:
        raise ValueError(f"Unsupported protoc host: {runner_os}/{runner_arch}") from None

    archive_name = f"protoc-{VERSION}-{platform}.zip"
    url = f"https://github.com/protocolbuffers/protobuf/releases/download/v{VERSION}/{archive_name}"
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected_checksum:
        raise ValueError(f"SHA-256 mismatch for {archive_name}")

    directory = Path(tempfile.mkdtemp(prefix=f"protoc-{VERSION}-", dir=temporary_directory)).resolve()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        archive.extractall(directory)
    executable = directory / "bin" / ("protoc.exe" if runner_os == "Windows" else "protoc")
    if runner_os != "Windows":
        executable.chmod(executable.stat().st_mode | 0o111)
    expected_version = f"libprotoc {VERSION}"
    version = subprocess.check_output([str(executable), "--version"], text=True).strip()
    if version != expected_version:
        raise ValueError(f"Expected {expected_version}; found {version}")
    print(f"Installed {version} ({platform})")
    return executable


def main() -> None:
    executable = install(os.environ["RUNNER_OS"], os.environ["RUNNER_ARCH"], Path(os.environ["RUNNER_TEMP"]))
    with Path(os.environ["GITHUB_PATH"]).open("a", encoding="utf-8") as output:
        output.write(f"{executable.parent}\n")
    # Pin Prost's explicit compiler too, even if the runner already sets PROTOC.
    with Path(os.environ["GITHUB_ENV"]).open("a", encoding="utf-8") as output:
        output.write(f"PROTOC={executable}\n")
        output.write(f"PROTOC_INCLUDE={executable.parent.parent / 'include'}\n")


if __name__ == "__main__":
    main()
