#!/usr/bin/env python3
"""Check iOS privacy resource packaging, not the completeness of declarations.

Without --app, check the source manifests. With --app, also compare both
executable bundles with source and verify the SQLCipher vendor manifest survives.
An .xcarchive's Products/Applications/Keyguard.app can be passed directly.
"""

import argparse
from pathlib import Path
import plistlib
import sys


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    Path("PrivacyInfo.xcprivacy"): ROOT / "iosApp/iosApp/PrivacyInfo.xcprivacy",
    Path("PlugIns/KeyguardAutofill.appex/PrivacyInfo.xcprivacy"):
        ROOT / "iosApp/KeyguardAutofill/PrivacyInfo.xcprivacy",
}


def read_manifest(path):
    with path.open("rb") as stream:
        manifest = plistlib.load(stream)
    if not isinstance(manifest, dict):
        raise ValueError(f"{path}: expected a property-list dictionary")
    categories = set()
    entries = manifest.get("NSPrivacyAccessedAPITypes", [])
    if not isinstance(entries, list):
        raise ValueError(f"{path}: API declarations must be an array")
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError(f"{path}: API declaration must be a dictionary")
        category = entry.get("NSPrivacyAccessedAPIType")
        reasons = entry.get("NSPrivacyAccessedAPITypeReasons")
        if not isinstance(category, str) or not category:
            raise ValueError(f"{path}: missing API category")
        if category in categories:
            raise ValueError(f"{path}: duplicate API category {category}")
        categories.add(category)
        if not isinstance(reasons, list) or not reasons or any(
            not isinstance(reason, str) or not reason for reason in reasons
        ):
            raise ValueError(f"{path}: {category} needs nonempty reason strings")
    return manifest


def verify(app=None):
    for relative, source in SOURCES.items():
        expected = read_manifest(source)
        if app is not None and read_manifest(app / relative) != expected:
            raise ValueError(f"{app / relative}: packaged manifest differs from source")
    if app is not None:
        vendor = app / "Frameworks/SQLCipher.framework/PrivacyInfo.xcprivacy"
        read_manifest(vendor)
        for manifest in app.rglob("PrivacyInfo.xcprivacy"):
            read_manifest(manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, help="built or archived Keyguard.app")
    args = parser.parse_args()
    try:
        verify(args.app)
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print("iOS privacy manifests: packaging checks passed (not a compliance assessment).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
