import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from get_flathub_listing_releases import convert_versions_to_xml
from get_release_changelog_summary import (
    ALL_PLATFORMS,
    FALLBACK_SUMMARY,
    NO_CHANGES_SENTINEL,
    bullet_platforms,
    filter_notes_for_platform,
    main,
    prepare_commits,
    read_commit_subjects,
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


class PrepareCommitsTest(unittest.TestCase):
    def test_enriches_trailing_references_and_preserves_literal_backticks(self):
        with patch("get_release_changelog_summary.describe_reference", return_value="Issue metadata") as lookup:
            text, updates = prepare_commits([
                "fix: Preserve `code` (#12) #13 (#12)",
                "fix: Read entry #1 twice",
            ], "AChep/keyguard-app", "token")
        self.assertEqual([call.args[2] for call in lookup.call_args_list], [12, 13])
        self.assertIn("- fix: Preserve `code` (#12) #13 (#12)\n  - Issue metadata", text)
        self.assertIn("- fix: Read entry #1 twice", text)
        self.assertEqual(updates, [])

    def test_filters_own_merges_cla_and_collects_data_updates(self):
        with patch("get_release_changelog_summary.describe_reference") as lookup:
            text, updates = prepare_commits([
                "[AUTO] Update localization library",
                "[AUTO] Update localization library (#12)",
                "[AUTO] Update passkeys library",
                "Merge pull request #13 from achep/feature",
                "Contributor has signed the CLA in #14",
            ], "AChep/keyguard-app", None)
        self.assertEqual(text, "")
        self.assertEqual(updates, ["localization library", "passkeys library"])
        lookup.assert_not_called()

    def test_external_merge_is_enriched(self):
        description = "#42 pull request by @contributor: Fix startup"
        subject = "Merge pull request #42 from contributor/fix"
        with patch("get_release_changelog_summary.describe_reference", return_value=description) as lookup:
            text, _ = prepare_commits([subject], "AChep/keyguard-app", None)
        lookup.assert_called_once_with("AChep/keyguard-app", None, 42)
        self.assertEqual(text, f"- {subject}\n  - {description}")


class CommitInputTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.path = self.directory / "commits.json"

    def write(self, commits, repo="AChep/keyguard-app"):
        self.path.write_text(json.dumps({"repository": repo, "commits": commits}), encoding="utf-8")

    def test_reads_subjects_without_markdown_parsing(self):
        self.write([{"sha": "a" * 40, "subject": "fix: [link](url) and `code` (#12)"}])
        self.assertEqual(read_commit_subjects(self.path, "AChep/keyguard-app"), ["fix: [link](url) and `code` (#12)"])

    def test_rejects_other_repository(self):
        self.write([], repo="someone/else")
        with self.assertRaisesRegex(ValueError, "must belong"):
            read_commit_subjects(self.path, "AChep/keyguard-app")

    def test_accepts_the_repository_in_any_case(self):
        self.write([{"sha": "a" * 40, "subject": "fix: Case"}], repo="achep/KEYGUARD-app")
        self.assertEqual(read_commit_subjects(self.path, "AChep/keyguard-app"), ["fix: Case"])

    def test_rejects_malformed_commits(self):
        for commits in (None, {}, [None], [{}], [{"subject": 123}]):
            with self.subTest(commits=commits):
                self.write(commits)
                with self.assertRaisesRegex(ValueError, "commits array"):
                    read_commit_subjects(self.path, "AChep/keyguard-app")

    def test_cli_rejects_missing_empty_and_non_json_input(self):
        for content in (None, "", "No Changes."):
            with self.subTest(content=content):
                if content is not None:
                    self.path.write_text(content)
                with patch("sys.argv", ["summarize", str(self.path), "--repo", "AChep/keyguard-app", "--print-prompt"]):
                    with self.assertRaises(SystemExit) as error:
                        main()
                self.assertEqual(error.exception.code, 1)

    def test_empty_commit_list_writes_all_outputs_without_calling_gemini(self):
        self.write([])
        self.assertEqual(self.run_main_without_gemini(), NO_CHANGES_SENTINEL)

    def test_data_only_release_writes_all_outputs_without_calling_gemini(self):
        self.write([
            {"sha": "a" * 40, "subject": "[AUTO] Update localization library (#12)"},
            {"sha": "b" * 40, "subject": "Merge pull request #13 from AChep/feature"},
        ])
        self.assertEqual(self.run_main_without_gemini(), "### Improvements\n- Data: Updated translations.")

    def run_main_without_gemini(self) -> str:
        """Runs the CLI on the written commits, checks the fallback summaries, and returns the full notes."""
        output = self.directory / "summary.md"
        full = self.directory / "full.md"
        platforms = self.directory / "platforms"
        with patch("sys.argv", [
            "summarize", str(self.path), "--repo", "AChep/keyguard-app", "--token", "unused",
            "--output", str(output), "--full-output", str(full), "--platform-output-dir", str(platforms),
        ]), patch("get_release_changelog_summary.GeminiSummarizer") as gemini:
            main()
        gemini.assert_not_called()
        self.assertEqual(output.read_text().strip(), FALLBACK_SUMMARY)
        self.assertEqual({path.stem for path in platforms.iterdir()}, ALL_PLATFORMS)
        for path in platforms.iterdir():
            self.assertEqual(path.read_text().strip(), FALLBACK_SUMMARY)
        return full.read_text().strip()


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
