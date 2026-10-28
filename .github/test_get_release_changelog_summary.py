import json
import tempfile
import unittest
from pathlib import Path

from get_flathub_listing_releases import convert_versions_to_xml
from get_release_changelog_summary import (
    ALL_PLATFORMS,
    NO_CHANGES_SENTINEL,
    bullet_platforms,
    filter_notes_for_platform,
)

FULL_NOTES = """This release adds SSH certificates.

> [!IMPORTANT]
> Windows: The minimum version is now Windows 10.

### New
- SSH agent: Added SSH certificates (#1600).
- Wear OS: Added login without a phone (#1577).

### Improvements
- Windows: The minimum version is now Windows 10.
- Vault: Tags are now suggested when adding a new one (#1535).

### Fixes
- macOS, Windows: Fixed auto-type from the Quick search window.
- Watchtower: Fixed the report of reused passwords."""


class BulletPlatformsTest(unittest.TestCase):
    def test_desktop_applies_to_desktop_platforms(self):
        self.assertEqual(bullet_platforms("- Desktop: Fixed copying."), {"windows", "macos", "linux"})

    def test_two_platforms(self):
        self.assertEqual(bullet_platforms("- macOS, Windows: Fixed copying."), {"macos", "windows"})

    def test_autofill_applies_to_android_only(self):
        self.assertEqual(bullet_platforms("- Autofill: Fixed saving."), {"android"})

    def test_desktop_only_features(self):
        self.assertEqual(bullet_platforms("- Auto-type: Fixed it."), {"windows", "macos", "linux"})

    def test_ssh_agent_applies_to_android_and_desktop(self):
        self.assertEqual(bullet_platforms("- SSH agent: Fixed it."), {"android", "windows", "macos", "linux"})

    def test_shared_core_applies_to_wear_os(self):
        for prefix in ("Vault", "Data", "Send", "Attachments"):
            self.assertEqual(bullet_platforms(f"- {prefix}: Fixed it."), ALL_PLATFORMS)

    def test_other_features_skip_wear_os(self):
        self.assertEqual(bullet_platforms("- Watchtower: Fixed it."), ALL_PLATFORMS - {"wearos"})

    def test_ios_applies_to_no_platform(self):
        self.assertEqual(bullet_platforms("- iOS: Fixed it."), set())

    def test_unknown_prefix_skips_wear_os(self):
        with self.assertLogs(level="WARNING"):
            self.assertEqual(bullet_platforms("- Clipboard: Fixed it."), ALL_PLATFORMS - {"wearos"})


class FilterNotesForPlatformTest(unittest.TestCase):
    def test_android_keeps_ssh_agent_changes(self):
        notes = "### New\n- SSH agent: Added SSH certificates (#1600)."
        self.assertEqual(filter_notes_for_platform(notes, "android"), notes)

    def test_wear_os_keeps_send_and_attachment_changes(self):
        notes = (
            "### Fixes\n"
            "- Send: Fixed opening file Sends.\n"
            "- Attachments: Fixed previewing archives."
        )
        self.assertEqual(filter_notes_for_platform(notes, "wearos"), notes)

    def test_wear_os(self):
        self.assertEqual(
            filter_notes_for_platform(FULL_NOTES, "wearos"),
            "### New\n"
            "- Wear OS: Added login without a phone (#1577).\n"
            "\n"
            "### Improvements\n"
            "- Vault: Tags are now suggested when adding a new one (#1535).",
        )

    def test_windows(self):
        self.assertEqual(
            filter_notes_for_platform(FULL_NOTES, "windows"),
            "### New\n"
            "- SSH agent: Added SSH certificates (#1600).\n"
            "\n"
            "### Improvements\n"
            "- Windows: The minimum version is now Windows 10.\n"
            "- Vault: Tags are now suggested when adding a new one (#1535).\n"
            "\n"
            "### Fixes\n"
            "- macOS, Windows: Fixed auto-type from the Quick search window.\n"
            "- Watchtower: Fixed the report of reused passwords.",
        )

    def test_nothing_applies(self):
        self.assertEqual(filter_notes_for_platform("### Fixes\n- Linux: Fixed copying.", "android"), "")

    def test_no_changes(self):
        self.assertEqual(filter_notes_for_platform(NO_CHANGES_SENTINEL, "android"), "")


class FlathubListingReleasesTest(unittest.TestCase):
    def _convert(self, versions) -> str:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "versions.json"
            path.write_text(json.dumps(versions), encoding="utf-8")
            return convert_versions_to_xml(path, Path(directory) / "releases.xml")

    def test_prefers_linux_summary(self):
        xml = self._convert([{
            "date": "2026-10-28",
            "version": {"semantic": "3.6.0", "tag": "r20261028"},
            "changelog": {"summary": "All platforms.", "summaries": {"linux": "Linux only."}},
        }])
        self.assertIn("<p>Linux only.</p>", xml)
        self.assertNotIn("All platforms.", xml)

    def test_falls_back_to_summary(self):
        xml = self._convert([{
            "date": "2026-10-28",
            "version": {"semantic": "3.6.0", "tag": "r20261028"},
            "changelog": {"summary": "All platforms."},
        }])
        self.assertIn("<p>All platforms.</p>", xml)


if __name__ == "__main__":
    unittest.main()
