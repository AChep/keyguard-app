#!/usr/bin/env python3
"""Derive the iOS permission catalog from the Crowdin-managed shared strings."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = root / "appleUi/Sources/KeyguardUI/Resources/Localizable.xcstrings"
destination = root / "iosApp/Resources/InfoPlist.xcstrings"
catalog = json.loads(source.read_text(encoding="utf-8"))
# Info.plist key -> shared string key. Keep the English fallbacks in Info.plist equal
# to the shared source strings.
permissions = {
    "NSCameraUsageDescription": "pref_item_permission_camera_text",
    "NSFaceIDUsageDescription": "unlock_biometric_auth_confirm_text",
}
missing = [key for key in permissions.values() if key not in catalog["strings"]]
if missing:
    raise ValueError(f"Missing permission description source: {', '.join(missing)}")

result = {
    "sourceLanguage": catalog["sourceLanguage"],
    "strings": {
        plist_key: {
            "extractionState": "manual",
            "localizations": catalog["strings"][source_key]["localizations"],
        }
        for plist_key, source_key in permissions.items()
    },
    "version": "1.0",
}
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(
    json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
