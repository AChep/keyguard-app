"""Exercise the actual CI prefetch script with a recording Cargo executable."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / ".github/actions/prefetch_native/prefetch.sh"


class PrefetchNativeTest(unittest.TestCase):
    def run_prefetch(self, profile="", manifests=""):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            cargo = directory / "cargo"
            cargo.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> "$PREFETCH_TEST_TRACE"\n')
            cargo.chmod(0o755)
            trace = directory / "calls"
            result = subprocess.run(
                ["bash", str(SCRIPT)], cwd=ROOT, capture_output=True, text=True,
                env={**os.environ, "PATH": f"{directory}{os.pathsep}{os.environ['PATH']}",
                     "PREFETCH_PROFILE": profile, "PREFETCH_MANIFESTS": manifests,
                     "PREFETCH_TEST_TRACE": str(trace)},
            )
            return result, trace.read_text().splitlines() if trace.exists() else []

    def test_profiles_fetch_all_shipped_native_graphs_locked(self):
        manifests = {"Cargo.toml", "util/crypto/rust/Cargo.toml"}
        for profile in ("desktop", "android", "apple"):
            with self.subTest(profile=profile):
                result, calls = self.run_prefetch(profile)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(set(calls), {f"fetch --manifest-path {path} --locked" for path in manifests})

    def test_explicit_manifests_extend_profile_without_duplicate_fetches(self):
        result, calls = self.run_prefetch("desktop", "Cargo.toml\r\nthirdParty/rust/Cargo.toml\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls.count("fetch --manifest-path Cargo.toml --locked"), 1)
        self.assertIn("fetch --manifest-path thirdParty/rust/Cargo.toml --locked", calls)

    def test_missing_manifest_fails_before_any_fetch(self):
        result, calls = self.run_prefetch("desktop", "missing/Cargo.toml")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])

    def test_empty_and_unknown_profiles_fail(self):
        for profile in ("", "unknown"):
            with self.subTest(profile=profile):
                result, calls = self.run_prefetch(profile)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
