import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

from get_release_commits import collect_release_commits, published_release_tags, render_markdown

SCRIPTS = Path(__file__).resolve().parent


class PublishedReleasesTest(unittest.TestCase):
    def test_lists_published_releases_newest_first(self):
        releases = [
            {"tag_name": "r20261030", "draft": True},
            {"tag_name": "r20261029", "prerelease": True},
            {"tag_name": "r20261028"},
            {"tag_name": "r20261027", "draft": False, "prerelease": False},
        ]
        response = io.BytesIO(json.dumps(releases).encode())
        with patch("get_release_commits.urllib.request.urlopen", return_value=response) as request:
            self.assertEqual(published_release_tags("AChep/keyguard-app", "test-token"), ["r20261028", "r20261027"])
        args, kwargs = request.call_args
        self.assertEqual(args[0].full_url, "https://api.github.com/repos/AChep/keyguard-app/releases?per_page=30")
        self.assertEqual(args[0].get_header("Authorization"), "Bearer test-token")
        self.assertEqual(kwargs["timeout"], 10)

    def test_api_errors_do_not_fall_back_to_a_tag_or_all_history(self):
        for code in (403, 404, 500):
            with self.subTest(code=code):
                error = urllib.error.HTTPError("https://api.github.com", code, "error", {}, None)
                with patch("get_release_commits.urllib.request.urlopen", side_effect=error):
                    with self.assertRaisesRegex(ValueError, f"HTTP {code}.*--base-ref"):
                        published_release_tags("AChep/keyguard-app", None)

    def test_releases_without_a_tag_are_skipped(self):
        response = io.BytesIO(json.dumps([{}, {"tag_name": ""}, {"tag_name": None}, None]).encode())
        with patch("get_release_commits.urllib.request.urlopen", return_value=response):
            self.assertEqual(published_release_tags("AChep/keyguard-app", None), [])

    def test_unexpected_response_fails(self):
        response = io.BytesIO(json.dumps({"tag_name": "r20261028"}).encode())
        with patch("get_release_commits.urllib.request.urlopen", return_value=response):
            with self.assertRaisesRegex(ValueError, "--base-ref"):
                published_release_tags("AChep/keyguard-app", None)


class ReleaseCommitsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "release@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "tag.gpgsign", "false")
        self.git("config", "core.hooksPath", os.devnull)
        self.base_sha = self.commit("Initial release")
        self.git("tag", "-a", "r20261027", "-m", "Published release")
        # Tests must never contact GitHub; individual tests opt into API selection.
        releases = patch("get_release_commits.published_release_tags")
        self.releases = releases.start()
        self.addCleanup(releases.stop)
        self.releases.return_value = ["r20261027"]

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.directory, check=True,
            capture_output=True, encoding="utf-8",
        ).stdout.strip()

    def commit(self, subject):
        self.git("commit", "--allow-empty", "-m", subject)
        return self.git("rev-parse", "HEAD")

    def collect(self, base="r20261027", head="HEAD", directory=None):
        return collect_release_commits("AChep/keyguard-app", base, head, directory=directory or self.directory)

    def test_explicit_range_excludes_base_and_commits_after_head(self):
        first = self.commit("fix: First fix")
        head = self.commit("feat: Second change")
        self.commit("Not in this release")
        changelog = self.collect(head=head)
        self.assertEqual(changelog["base"]["sha"], self.base_sha)
        self.assertEqual(changelog["head"]["sha"], head)
        self.assertEqual(changelog["commits"], [
            {"sha": head, "subject": "feat: Second change"},
            {"sha": first, "subject": "fix: First fix"},
        ])
        self.releases.assert_not_called()

    def test_default_base_is_api_release_tag_even_with_newer_tags_and_same_named_branch(self):
        first = self.commit("First change")
        self.git("tag", "unpublished-tag")
        self.git("branch", "r20261027")
        head = self.commit("Second change")
        changelog = self.collect(base=None)
        self.assertEqual(changelog["base"], {"ref": "refs/tags/r20261027", "sha": self.base_sha})
        self.assertEqual([commit["sha"] for commit in changelog["commits"]], [head, first])

    def test_includes_commits_from_merged_branches(self):
        self.git("checkout", "-b", "feature")
        feature = self.commit("feat: Branch change")
        self.git("checkout", "main")
        main = self.commit("fix: Main change")
        self.git("merge", "--no-ff", "feature", "-m", "Merge pull request #123 from AChep/feature")
        merge = self.git("rev-parse", "HEAD")
        commits = self.collect()["commits"]
        self.assertEqual(commits[0]["sha"], merge)
        self.assertEqual({commit["sha"] for commit in commits}, {feature, main, merge})

    def test_rejects_non_ancestor_base(self):
        self.git("checkout", "-b", "other-release")
        other = self.commit("Other release line")
        self.git("checkout", "main")
        self.commit("Current release")
        with self.assertRaisesRegex(ValueError, "not an ancestor.*--base-ref"):
            self.collect(base=other)

    def test_automatic_base_skips_the_head_release_and_newer_releases(self):
        # A rerun after publication, or a release created together with its tag.
        released = self.commit("fix: Released change")
        self.git("tag", "r20261028")
        self.commit("feat: Newer release")
        self.git("tag", "r20261029")
        self.releases.return_value = ["r20261029", "r20261028", "r20261027"]
        changelog = self.collect(base=None, head="r20261028")
        self.assertEqual(changelog["base"], {"ref": "refs/tags/r20261027", "sha": self.base_sha})
        self.assertEqual([commit["sha"] for commit in changelog["commits"]], [released])

    def test_automatic_base_skips_releases_whose_tag_is_missing(self):
        head = self.commit("Current release")
        self.releases.return_value = ["r20261028", "r20261027"]
        with self.assertLogs("get_release_commits", level="WARNING") as logs:
            changelog = self.collect(base=None)
        self.assertEqual(changelog["base"]["sha"], self.base_sha)
        self.assertEqual([commit["sha"] for commit in changelog["commits"]], [head])
        self.assertIn("r20261028", logs.output[0])

    def test_automatic_base_follows_the_release_line_of_a_hotfix(self):
        self.commit("feat: Newer release")
        self.git("tag", "r20261028")
        self.git("checkout", "-b", "hotfix", "r20261027")
        hotfix = self.commit("fix: Hotfix")
        self.releases.return_value = ["r20261028", "r20261027"]
        changelog = self.collect(base=None)
        self.assertEqual(changelog["base"]["sha"], self.base_sha)
        self.assertEqual([commit["sha"] for commit in changelog["commits"]], [hotfix])

    def test_automatic_base_fails_when_no_release_precedes_the_head(self):
        # The head's own release does not count, so the first release of a line needs --base-ref.
        for releases in ([], ["r20261027"]):
            with self.subTest(releases=releases):
                self.releases.return_value = releases
                with self.assertRaisesRegex(ValueError, "precede.*--base-ref"):
                    self.collect(base=None)

    def test_rerun_reproduces_the_release_after_newer_releases(self):
        head = self.commit("fix: Released change")
        self.git("tag", "r20261028")
        self.releases.return_value = ["r20261028", "r20261027"]
        original = self.collect(base=None, head=head)
        self.commit("feat: Newer release")
        self.git("tag", "r20261029")
        self.releases.return_value = ["r20261029", "r20261028", "r20261027"]
        self.assertEqual(self.collect(base=None, head=head), original)
        self.releases.reset_mock()
        rerun = self.collect(base=original["base"]["sha"], head=head)
        self.assertEqual(rerun["commits"], original["commits"])
        self.releases.assert_not_called()

    def test_explicit_empty_range_is_valid(self):
        changelog = self.collect()
        self.assertEqual(changelog["commits"], [])
        self.assertEqual(render_markdown(changelog), "No Changes.\n")

    def test_missing_and_option_like_refs_fail(self):
        for ref in ("missing-tag", "--all", "$(touch injected)"):
            with self.subTest(ref=ref), self.assertRaisesRegex(ValueError, "rev-parse failed"):
                self.collect(base=ref)
        self.assertFalse((self.directory / "injected").exists())

    def test_empty_refs_fail(self):
        for base, head in (("", "HEAD"), ("r20261027", "")):
            with self.subTest(base=base, head=head), self.assertRaisesRegex(ValueError, "must not be empty"):
                self.collect(base=base, head=head)

    def test_shallow_checkout_fails_with_fetch_instructions(self):
        self.commit("New release")
        clone = self.directory / "shallow"
        self.git("clone", "--depth=1", self.directory.as_uri(), str(clone))
        with self.assertRaisesRegex(ValueError, "fetch-depth: 0"):
            self.collect(base=None, directory=clone)
        self.releases.assert_not_called()

    def test_subjects_are_data_and_control_characters_are_normalized(self):
        subject = "fix: `code` and $(touch injected)\twith ‮text next 🗝️ (#42)"
        sha = self.commit(subject)
        changelog = self.collect()
        expected = "fix: `code` and $(touch injected) with  text next 🗝️ (#42)"
        self.assertEqual(changelog["commits"], [{"sha": sha, "subject": expected}])
        self.assertFalse((self.directory / "injected").exists())
        self.assertEqual(render_markdown(changelog),
                         f"- [{sha[:9]}](https://github.com/AChep/keyguard-app/commit/{sha}) - {expected}\n")

    def test_cli_generates_files_consumed_by_summarizer(self):
        self.commit("fix: Preserve `literal` text")
        result = self.run_cli("r20261027")
        self.assertEqual(result.returncode, 0, result.stderr)
        changelog = json.loads((self.directory / "commits.json").read_text())
        self.assertEqual(changelog["commits"][0]["subject"], "fix: Preserve `literal` text")
        self.assertIn(") - fix: Preserve `literal` text\n", (self.directory / "raw.md").read_text())
        prompt = subprocess.run([
            sys.executable, str(SCRIPTS / "get_release_changelog_summary.py"),
            "commits.json", "--repo", "AChep/keyguard-app", "--print-prompt",
        ], cwd=self.directory, capture_output=True, encoding="utf-8")
        self.assertEqual(prompt.returncode, 0, prompt.stderr)
        self.assertIn("- fix: Preserve `literal` text", prompt.stdout)

    def test_cli_fails_without_writing_changelog_on_invalid_range(self):
        result = self.run_cli("missing-tag")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Could not generate release commits", result.stderr)
        self.assertFalse((self.directory / "commits.json").exists())
        self.assertFalse((self.directory / "raw.md").exists())

    def run_cli(self, base):
        return subprocess.run([
            sys.executable, str(SCRIPTS / "get_release_commits.py"),
            "--repo", "AChep/keyguard-app", "--base-ref", base, "--head-ref", "HEAD",
            "--output", "commits.json", "--markdown-output", "raw.md",
        ], cwd=self.directory, capture_output=True, encoding="utf-8")


class MarkdownTest(unittest.TestCase):
    def test_renders_the_shape_of_the_historical_versions_json_entries(self):
        subject = "fix: [link](url) `code` @someone #42 <b>text</b>"
        changelog = {"repository": "AChep/keyguard-app", "commits": [{"sha": "a" * 40, "subject": subject}]}
        self.assertEqual(render_markdown(changelog),
                         f"- [aaaaaaaaa](https://github.com/AChep/keyguard-app/commit/{'a' * 40}) - {subject}\n")


if __name__ == "__main__":
    unittest.main()
