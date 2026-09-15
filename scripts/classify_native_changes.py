#!/usr/bin/env python3
"""Select native CI work from changed paths; unknown Git history runs all checks."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


DESKTOP_HOSTS = [
    dict(name="Linux x64", os="ubuntu-24.04", platform="linux", arch="x86_64", target="x86_64-unknown-linux-gnu", gradle="./gradlew"),
    dict(name="macOS arm64", os="macos-15", platform="macos", arch="aarch64", target="aarch64-apple-darwin", gradle="./gradlew"),
    dict(name="Windows x64", os="windows-2025", platform="windows", arch="x86_64", target="x86_64-pc-windows-msvc", gradle="./gradlew.bat"),
]
EXTRA_DESKTOP_HOSTS = [
    dict(name="Linux arm64", os="ubuntu-24.04-arm", platform="linux", arch="aarch64", target="aarch64-unknown-linux-gnu", gradle="./gradlew"),
    dict(name="macOS x64", os="macos-15-intel", platform="macos", arch="x86_64", target="x86_64-apple-darwin", gradle="./gradlew"),
]
FLAGS = (
    "desktop", "desktop_regressions", "android", "android_runtime",
    "apple", "apple_regressions", "io", "instance", "crypto", "fuzz",
    "native_quality", "desktop_native", "wear",
)


def is_rust_test_path(path):
    """Recognize test trees and the repository's cfg(test) module filenames."""
    parts = path.split("/")
    filename = parts[-1]
    return (
        "tests" in parts[:-1]
        or "benches" in parts[:-1]
        or filename == "tests.rs"
        or filename.endswith("_tests.rs")
    )


def classify(paths, *, full=False, all_checks=False):
    selected = dict.fromkeys(FLAGS, full or all_checks)
    selected["wear"] = full

    def enable(*flags):
        for flag in flags:
            selected[flag] = True

    for path in paths:
        # Test selection is separate from packaging: Check Tests owns ordinary
        # Kotlin/build-plugin suites; only actual platform regressions live here.
        if path.startswith(("buildPlugins/src/test/", "scripts/test_")):
            continue
        if path.startswith("docs/security/native-crypto-"):
            enable("crypto")
            continue
        if path.endswith((".md", ".txt")) and not path.startswith(".github/native-crypto-"):
            continue
        if (
            path in {"build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "rust-toolchain.toml"}
            or path.startswith(("gradle/", "buildPlugins/"))
            or path.startswith((".github/actions/setup_rust/", ".github/actions/setup_gradle/", ".github/actions/prefetch_native/", ".github/actions/setup_protobuf/"))
            or path in {".github/workflows/check_native.yml", ".github/workflows/check_native_crypto.yml", ".github/workflows/check_native_io.yml"}
        ):
            enable(*(flag for flag in FLAGS if flag != "wear"))
            continue
        if path.startswith("scripts/") and ("native" in path or "bouncycastle" in path):
            enable("desktop", "android", "apple")
            continue
        if path.startswith(".github/native-crypto-"):
            enable("crypto", "desktop", "android", "apple")
            continue
        module = re.match(r"util/(crypto|io|zxcvbn|zip|instance)/(.+)", path)
        if module:
            name, relative = module.groups()
            # The Windows instance backend consumes IO's native filesystem core.
            if name == "io" and relative.startswith("rust/"):
                enable("instance", "desktop_regressions")
            source_match = re.match(r"src/([^/]+)/", relative)
            source_set = source_match.group(1) if source_match else ""
            test_source = source_set.endswith("Test")
            rust_test = relative.startswith("rust/") and is_rust_test_path(relative)
            if name == "crypto":
                if relative.startswith("rust/fuzz/"):
                    enable("fuzz")
                    continue
                if relative.startswith(("rust/", "tools/", "schema/")) or relative == "rust-toolchain.toml":
                    enable("crypto")
                    if not rust_test:
                        enable("fuzz")
            if relative.startswith("rust/") and name != "crypto":
                enable("native_quality")
            if test_source or rust_test:
                if name in {"io", "instance"} and (rust_test or source_set in {"commonTest", "desktopTest", "jvmTest"}):
                    enable(name, "desktop_regressions")
                if source_set.startswith(("macos", "apple", "native")) or (name in {"io", "instance"} and source_set == "commonTest"):
                    enable("apple_regressions")
                # Android host and iOS simulator suites already run in Check Tests.
                continue
            # These mappings follow the actual KMP source-set edges. ZIP uses
            # zip4j on JVM; instance exposes Kotlin/Native only on macOS.
            if source_set in {"androidMain", "desktopMain", "jvmMain", "jvmCommonMain"}:
                if name == "zip":
                    continue
                if source_set != "androidMain":
                    enable("desktop")
                    if name in {"io", "instance"}:
                        enable(name, "desktop_regressions")
                if source_set != "desktopMain" and name != "instance":
                    enable("android")
                    if name == "io":
                        enable("io")
                continue
            if source_set.startswith(("apple", "native", "ios", "macos")):
                if name == "instance" or source_set.startswith("macos"):
                    enable("apple_regressions")
                else:
                    enable("apple")
                    if not source_set.startswith("ios"):
                        enable("apple_regressions")
                continue
            if name in {"io", "instance"}:
                enable(name)
            if name == "zip":
                enable("apple", "apple_regressions")
            elif name == "instance":
                enable("desktop", "desktop_regressions", "apple_regressions")
            else:
                enable("desktop", "android", "apple", "apple_regressions")
                if name == "io":
                    enable("desktop_regressions")
            continue
        if path.startswith("thirdParty/rust/"):
            if is_rust_test_path(path):
                enable("crypto")
                continue
            enable("crypto", "fuzz", "desktop", "android", "apple", "apple_regressions")
        if path.startswith(("desktopLibNative/", "desktopSshAgent/", "desktopGpgAgent/", "commonAgent/", "commonSshAgent/", "commonGpgAgent/")):
            if is_rust_test_path(path):
                if path.startswith("desktopLibNative/"):
                    enable("desktop_native")
                # Agent tests are owned by Check SSH Agent / Check GPG Agent.
                continue
            enable("desktop", "desktop_native")
        if re.match(r"androidApp/src/androidTest/.+/(nativebundle|io|crypto)/", path):
            enable("android_runtime")
            if "/test/io/" in path:
                enable("io")
            continue
        if re.match(r"[^/]+/src/[^/]*Test/", path):
            if path.startswith("desktopApp/") and "/instance/" in path:
                enable("instance", "desktop_regressions")
            if path.startswith("common/") and ("PrivateTemporaryStorage" in path or "KeePassDatabaseWindowsSpillTest" in path):
                enable("io", "desktop_regressions")
            # iOS consumer runtime coverage is in Check Tests.
            continue
        if (
            path.startswith(("desktopApp/resources/", "desktopApp/src/jvmMain/resources/", "desktopApp/appimage/", "desktopApp/flatpak/"))
            or path in {"desktopApp/build.gradle.kts", "desktopApp/proguard-rules.pro", "desktopApp/default.entitlements", "desktopApp/icon.png", "desktopApp/icon.ico", "desktopApp/icon.icns", "desktopLibJvm/build.gradle.kts", "common/build.gradle.kts", "common/proguard-rules.pro"}
            or re.match(r"desktopApp/src/[^/]+/kotlin/.+/(Main\.kt|desktop/(instance|services|nativebundle)/.+|Native.+|.*[Ll]oader.*)$", path)
            or (path.startswith("desktopLibJvm/") and path.endswith("DesktopLibJna.kt"))
            or path.startswith((".github/actions/setup_linux_desktop/", ".github/actions/build_desktop_licenses/", ".github/actions/build_appimage/", ".github/actions/build_flatpak/"))
            or path in {".github/workflows/new_appimage.yaml", ".github/workflows/new_flatpak.yaml"}
        ):
            enable("desktop")
        if (
            re.match(r"(androidApp|wearApp)/(build\.gradle\.kts|.*\.pro|src/[^/]+/AndroidManifest\.xml)$", path)
            or path in {"common/build.gradle.kts", "common/proguard-rules.pro", "common/src/androidMain/kotlin/com/artemchep/keyguard/android/BaseApp.kt"}
            or re.match(r"(androidApp|wearApp)/src/main/(java|kotlin)/.+/Main\.kt$", path)
            or path.startswith((".github/actions/setup_android_ndk/", ".github/actions/prepare_kvm/", ".github/actions/prepare_disk_space/", ".github/actions/build_android_licenses/", ".github/actions/build_wearos_licenses/", ".github/actions/build_android_baseline_profiles/"))
            or path in {".github/workflows/new_apk.yaml", ".github/workflows/new_daily_tag_play_store_internal_track.yaml"}
        ):
            enable("android")
        if re.match(r"common/src/[^/]+/.+/(io|keepass)/", path):
            enable("io", "android_runtime", "desktop_regressions")
        if path.startswith("common/src/") and "PrivateTemporaryStorage" in path:
            enable("io", "android_runtime", "desktop_regressions")
        if path.startswith("common/src/") and "/nativebundle/" in path:
            enable("desktop", "android", "apple")
        if path in {"iosApp/build.gradle.kts", "common/build.gradle.kts"} or path.startswith("iosApp/src/nativeInterop/"):
            enable("apple")
        if path == ".github/workflows/new_tag_release.yaml":
            enable("desktop", "android")

    selected["desktop_run"] = any(selected[flag] for flag in ("desktop", "desktop_regressions", "desktop_native", "apple", "apple_regressions"))
    selected["android_run"] = selected["android"] or selected["android_runtime"]
    hosts = DESKTOP_HOSTS if selected["desktop"] or selected["desktop_native"] else DESKTOP_HOSTS[1:] if selected["desktop_regressions"] else [DESKTOP_HOSTS[1]]
    selected["desktop_matrix"] = {"include": DESKTOP_HOSTS + EXTRA_DESKTOP_HOSTS if full else hosts}
    selected["android_matrix"] = {"include": [{"api": 26}] + ([{"api": 30}] if full or selected["io"] else [])}
    return selected


def changed_paths(base, head="HEAD", *, cwd=None):
    """Include both sides of renames; return None if the comparison is unknown."""
    if not base or not base.strip("0"):
        return None
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", "--no-renames", "-z", base, head, "--"],
            cwd=cwd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
    except subprocess.CalledProcessError:
        return None
    return [path for path in result.stdout.decode("utf-8", errors="surrogateescape").split("\0") if path]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", help="Repository-relative paths (for local inspection)")
    parser.add_argument("--base", help="Base Git revision; unavailable history selects full coverage")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--all", action="store_true", help="All checks on representative hosts")
    parser.add_argument("--full", action="store_true", help="All platforms and runtime checks")
    parser.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT"))
    args = parser.parse_args()
    paths = args.paths if args.paths or args.all else changed_paths(args.base, args.head)
    full = args.full or paths is None
    if paths is None and not args.full:
        print("Cannot determine changed paths; selecting full native coverage.", file=sys.stderr)
    result = classify(paths or [], full=full, all_checks=args.all)
    print(json.dumps(result, indent=2))
    if args.github_output:
        with Path(args.github_output).open("a", encoding="utf-8") as output:
            for key, value in result.items():
                output.write(f"{key}={json.dumps(value, separators=(',', ':'))}\n")


if __name__ == "__main__":
    main()
