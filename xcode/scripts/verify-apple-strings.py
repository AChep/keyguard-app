#!/usr/bin/env python3
"""Fail an Apple build if a compiled localization omits base catalog keys."""
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from xml.parsers.expat import ExpatError


def load_table(table):
    try:
        return plistlib.loads(table.read_bytes())
    except (plistlib.InvalidFileException, ExpatError):
        # Xcode can emit UTF-16 XML tables whose declaration still says UTF-8.
        # Apple's parser accepts these (and legacy OpenStep .strings); normalize
        # through it instead of weakening validation or guessing an encoding.
        data = subprocess.check_output(
            ["/usr/bin/plutil", "-convert", "binary1", "-o", "-", str(table)],
            stderr=subprocess.STDOUT,
        )
        return plistlib.loads(data)


def verify_literal_lookups(root, source_keys):
    # AutoFill uses its own bundle and cannot depend on KeyguardUI's typed L10n.
    # Capture both sides of conditional calls such as L(useA ? "a" : "b").
    keys = set()
    for source in (root / "appleAutofill").glob("*.swift"):
        for call in re.finditer(r"\bL\(([^)]*)\)", source.read_text()):
            keys.update(re.findall(r'"([a-z][a-z0-9_]*)"', call.group(1)))

    biometry = root / "appleUi/Sources/KeyguardUI/Apple/AppleBiometry.swift"
    keys.update(re.findall(r'\bkey\s*=\s*"([a-z][a-z0-9_]*)"', biometry.read_text()))
    missing = keys - source_keys
    if missing:
        raise ValueError("Native literal localization keys are missing from common resources: " +
                         ", ".join(sorted(missing)))


def verify_no_prefixed_references(root):
    patterns = (r"\bL10n\.apple[A-Z0-9]", r"\bRes\.(?:string|plurals)\.apple_")
    stale = []
    for directory in (root / "appleUi/Sources/KeyguardUI", root / "appleApp/src"):
        for suffix in ("*.swift", "*.kt"):
            for source in directory.rglob(suffix):
                if ".build" in source.parts or "build" in source.parts or "Generated" in source.parts:
                    continue
                if any(re.search(pattern, source.read_text()) for pattern in patterns):
                    stale.append(str(source.relative_to(root)))
    if stale:
        raise ValueError("Apple-prefixed localization references remain in: " +
                         ", ".join(sorted(stale)))


def verify(catalog_path, resources, source_dir):
    catalog = json.loads(catalog_path.read_text())
    entries = catalog["strings"]
    base = catalog["sourceLanguage"]
    keys = {key for key, entry in entries.items() if base in entry.get("localizations", {})}
    source_keys = {
        entry.attrib["name"]
        for name in ("strings.xml", "plurals.xml")
        for entry in ET.parse(source_dir / name).getroot()
        if entry.tag in ("string", "plurals")
    }
    retired = {key for key in set(entries) | source_keys if key.startswith("apple_")}
    if retired:
        raise ValueError("Rename Apple-prefixed localization keys by feature: " +
                         ", ".join(sorted(retired)))
    if source_keys - keys:
        raise ValueError("Regenerate the Apple catalog; missing base keys: " +
                         ", ".join(sorted(source_keys - keys)))
    root = catalog_path.parents[4]
    verify_literal_lookups(root, source_keys)
    verify_no_prefixed_references(root)
    locales = {locale for entry in entries.values() for locale in entry.get("localizations", {})}
    # SwiftPM nests its bundle on macOS; the AutoFill catalog lives directly in
    # the extension's resources directory.
    package = resources / "KeyguardUI_KeyguardUI.bundle"
    if package.is_dir():
        resources = package
        if (resources / "Contents/Resources").is_dir():
            resources /= "Contents/Resources"
    failures = []
    for locale in sorted(locales):
        present = set()
        for suffix in ("strings", "stringsdict"):
            table = resources / f"{locale}.lproj/Localizable.{suffix}"
            if table.is_file():
                present.update(load_table(table))
        missing = keys - present
        if missing:
            failures.append(f"{locale}: {len(missing)} missing keys ({', '.join(sorted(missing)[:5])})")
    if failures:
        raise ValueError(f"Incomplete Apple localization in {resources}:\n" + "\n".join(failures))
    print(f"Verified {len(keys)} Apple localization keys across {len(locales)} locales in {resources}")


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    resources = Path(os.environ["BUILT_PRODUCTS_DIR"]) / os.environ["UNLOCALIZED_RESOURCES_FOLDER_PATH"]
    try:
        verify(
            root / "appleUi/Sources/KeyguardUI/Resources/Localizable.xcstrings",
            resources,
            root / "common/src/commonMain/composeResources/values",
        )
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"error: {error}")
