import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from update_versions_json import main


class UpdateVersionsJsonTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.path = self.directory / "versions.json"
        self.latest = {
            "date": "2026-10-28",
            "version": {"semantic": "3.6.1", "tag": "r20261028"},
            "changelog": {"summary": "Old summary", "raw": "- old"},
            "url": "https://github.com/AChep/keyguard-app/releases/tag/r20261028",
        }
        self.older = {
            "date": "2026-10-27",
            "version": {"semantic": "3.6.0", "tag": "r20261027"},
            "changelog": {"summary": "Older summary", "raw": "- older"},
            "url": "https://github.com/AChep/keyguard-app/releases/tag/r20261027",
        }
        self.path.write_text(json.dumps([self.latest, self.older]), encoding="utf-8")
        (self.directory / "summary.md").write_text("New summary\n", encoding="utf-8")
        (self.directory / "raw.md").write_text("- new\n", encoding="utf-8")
        (self.directory / "platforms").mkdir()
        (self.directory / "platforms" / "android.md").write_text("Android summary\n", encoding="utf-8")
        self.changelog = {"summary": "New summary", "summaries": {"android": "Android summary"}, "raw": "- new"}

    def run_main(self, *args):
        with patch("sys.argv", [
            "update", str(self.path), *args,
            "--changelog_summary_file", str(self.directory / "summary.md"),
            "--changelog_summaries_dir", str(self.directory / "platforms"),
            "--changelog_raw_file", str(self.directory / "raw.md"),
        ]):
            main()

    def items(self):
        return json.loads(self.path.read_text(encoding="utf-8"))

    def test_adds_a_missing_entry_first(self):
        self.run_main("--date", "2026-10-29", "--semantic_version", "3.6.2", "--tag_version", "r20261029")
        items = self.items()
        self.assertEqual([item["version"]["tag"] for item in items], ["r20261029", "r20261028", "r20261027"])
        self.assertEqual(items[0]["date"], "2026-10-29")
        self.assertEqual(items[0]["changelog"], self.changelog)

    def test_leaves_an_existing_entry_alone(self):
        self.run_main("--date", "2026-10-28", "--semantic_version", "3.6.1", "--tag_version", "r20261028")
        self.assertEqual(self.items(), [self.latest, self.older])

    def test_replaces_only_the_changelog_of_the_tag(self):
        self.run_main("--replace_changelog", "--tag_version", "r20261028")
        self.assertEqual(self.items(), [{**self.latest, "changelog": self.changelog}, self.older])

    def test_replace_fails_without_an_entry_for_the_tag(self):
        with self.assertRaises(SystemExit) as error:
            self.run_main("--replace_changelog", "--tag_version", "r20261030")
        self.assertEqual(error.exception.code, 1)
        self.assertEqual(self.items(), [self.latest, self.older])


if __name__ == "__main__":
    unittest.main()
