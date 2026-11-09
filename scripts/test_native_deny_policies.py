"""Keep the device sync cargo-deny policy on the native crypto baseline."""

from pathlib import Path
import tomllib
import unittest


GITHUB = Path(__file__).resolve().parents[1] / ".github"


def load_policy(name):
    with (GITHUB / name).open("rb") as file:
        policy = tomllib.load(file)
    # Each graph has its own advisory exceptions and banned crates.
    policy["advisories"].pop("ignore", None)
    policy["bans"].pop("deny", None)
    policy["bans"].pop("features", None)
    return policy


class NativeDenyPoliciesTest(unittest.TestCase):
    def test_device_sync_policy_repeats_the_crypto_baseline(self):
        self.assertEqual(
            load_policy("native-p2p-deny.toml"),
            load_policy("native-crypto-deny.toml"),
        )


if __name__ == "__main__":
    unittest.main()
