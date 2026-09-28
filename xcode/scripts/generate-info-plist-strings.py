#!/usr/bin/env python3
"""Derive the iOS permission catalog from the Crowdin-managed shared strings."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = root / "appleUi/Sources/KeyguardUI/Resources/Localizable.xcstrings"
destination = root / "iosApp/Resources/InfoPlist.xcstrings"
catalog = json.loads(source.read_text(encoding="utf-8"))
source_key = "unlock_biometric_auth_confirm_text"
if source_key not in catalog["strings"]:
    raise ValueError(f"Missing permission description source: {source_key}")

result = {
    "sourceLanguage": catalog["sourceLanguage"],
    "strings": {
        "NSFaceIDUsageDescription": {
            "extractionState": "manual",
            "localizations": catalog["strings"][source_key]["localizations"],
        },
    },
    "version": "1.0",
}
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(
    json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
