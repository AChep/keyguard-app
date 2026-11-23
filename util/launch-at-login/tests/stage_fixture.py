#!/usr/bin/env python3
"""Stage separate registration-test packages; never install, sign, or register them."""
from __future__ import annotations

import argparse
import plistlib
import re
import shutil
import struct
import zlib
from pathlib import Path
from xml.sax.saxutils import quoteattr


def png(size: int) -> bytes:
    def chunk(kind: bytes, contents: bytes) -> bytes:
        return struct.pack(">I", len(contents)) + kind + contents + struct.pack(">I", zlib.crc32(kind + contents))
    rows = (b"\0" + b"\x30\x60\x90\xff" * size) * size
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def stage(platform: str, output: Path, identity: str, helper: Path, entry: Path,
          publisher: str | None = None, architecture: str = "x64") -> Path:
    pattern = r"Keyguard\.Test\.[0-9a-fA-F]{32}" if platform == "msix" else r"com\.artemchep\.keyguard\.startuptest\.[0-9a-fA-F]{32}"
    if not re.fullmatch(pattern, identity):
        raise ValueError("a unique startup fixture identity is required")
    if not helper.is_file() or not entry.is_file():
        raise ValueError("build the registration helper and evidence entrypoint before staging")
    if output.exists():
        raise ValueError("output must be a new fixture directory")
    if platform not in {"macos", "msix"}:
        raise ValueError("unsupported fixture package")
    if platform == "msix" and (not publisher or architecture not in {"x64", "arm64"}):
        raise ValueError("MSIX needs the test certificate Publisher and x64/arm64 architecture")
    output.mkdir(parents=True)
    if platform == "macos":
        bundle = output / "StartupFixture.app"
        binaries = bundle / "Contents/MacOS"
        binaries.mkdir(parents=True)
        for source, name in ((helper, "registration_fixture"), (entry, "entrypoint")):
            shutil.copy2(source, binaries / name)
            (binaries / name).chmod(0o755)
        (bundle / "Contents/Info.plist").write_bytes(plistlib.dumps({
            "CFBundleIdentifier": identity, "CFBundleExecutable": "entrypoint",
            "CFBundleName": "Startup Fixture", "CFBundlePackageType": "APPL",
            "CFBundleVersion": "1", "CFBundleShortVersionString": "1.0", "LSMinimumSystemVersion": "13.0",
        }))
        return bundle
    shutil.copy2(helper, output / "registration_fixture.exe")
    shutil.copy2(entry, output / "entrypoint.exe")
    assets = output / "Assets"
    assets.mkdir()
    for size in (44, 50, 150):
        (assets / f"Logo{size}.png").write_bytes(png(size))
    apps = []
    for app_id, executable, extension in (
        ("Entry", "entrypoint.exe", '<Extensions><uap5:Extension Category="windows.startupTask" Executable="entrypoint.exe" EntryPoint="Windows.FullTrustApplication" uap10:Parameters="--launch-at-login"><uap5:StartupTask TaskId="KeyguardStartup" Enabled="false" DisplayName="Startup Fixture" /></uap5:Extension></Extensions>'),
        ("Registration", "registration_fixture.exe", ""),
    ):
        apps.append(f'<Application Id="{app_id}" Executable="{executable}" EntryPoint="Windows.FullTrustApplication"><uap:VisualElements DisplayName="Startup Fixture" Description="Disposable registration test" BackgroundColor="transparent" Square150x150Logo="Assets/Logo150.png" Square44x44Logo="Assets/Logo44.png" />{extension}</Application>')
    manifest = f'''<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10" xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10" xmlns:uap5="http://schemas.microsoft.com/appx/manifest/uap/windows10/5" xmlns:uap10="http://schemas.microsoft.com/appx/manifest/uap/windows10/10" xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities" IgnorableNamespaces="uap uap5 uap10 rescap">
<Identity Name={quoteattr(identity)} Publisher={quoteattr(publisher)} Version="1.0.0.0" ProcessorArchitecture={quoteattr(architecture)} />
<Properties><DisplayName>Startup Fixture</DisplayName><PublisherDisplayName>Startup Fixture</PublisherDisplayName><Logo>Assets/Logo50.png</Logo></Properties>
<Dependencies><TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.18362.0" MaxVersionTested="10.0.26100.0" /></Dependencies>
<Resources><Resource Language="en-us" /></Resources><Applications>{''.join(apps)}</Applications>
<Capabilities><rescap:Capability Name="runFullTrust" /></Capabilities></Package>
'''
    (output / "AppxManifest.xml").write_text(manifest, encoding="utf-8")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("macos", "msix"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--id", required=True)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--entry", type=Path, required=True)
    parser.add_argument("--publisher")
    parser.add_argument("--architecture", choices=("x64", "arm64"), default="x64")
    args = parser.parse_args()
    print(stage(args.platform, args.output, args.id, args.helper, args.entry, args.publisher, args.architecture))


if __name__ == "__main__":
    main()
