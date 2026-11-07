#!/usr/bin/env python3
"""Check the baseline: native and server values, manual lock, unlock and relaunch.

Run after `e2e baseline`, with `e2e driver serve` and the runner active. Does
not create or reset app or server data. Leaves the app unlocked.
"""

import argparse
from pathlib import Path

from lib import bitwarden
from lib.config import BASELINE_LOGIN, LOCAL_PASSWORD, account
from lib.journey import Journey, evidence

# Names created by `bw seed` for the main account.
SEEDED = ("Example Site 1", "Example Site 2", "Example Site 3", "Example Site 4", "Note 1", "Note 2")


class Smoke(Journey):
    def run(self):
        login = BASELINE_LOGIN
        tree = self.tree("start")
        self.unlock(tree)
        tree = self.wait_for(f"label: '{login['name']}'", "vault")
        for name in SEEDED:
            assert f"label: '{name}'" in tree, f"Missing seeded item {name}"
        items = bitwarden.snapshot(account("main"))
        item = next(item for item in items if item["name"] == login["name"])
        assert item["username"] == login["username"]
        assert item["password"] == login["password"]
        assert item["uris"] == [login["uri"]]
        assert item["deletedDate"] is None

        self.tap(login["name"], type="staticText")
        tree = self.wait_for(f"value: {login['username']}", "detail")
        if "label: 'Reveal'" in tree:
            self.tap("Reveal")
        tree = self.wait_for(f"value: {login['password']}", "revealed")
        assert login["uri"] in tree
        self.command("tap", type="button", identifier="BackButton")
        self.tap("More")
        self.command("swipe", type="button", label="Remember sorting method", direction="up")
        self.tap("Lock vault")
        tree = self.wait_for("label: 'Unlock a vault'", "locked")
        assert login["name"] not in tree and login["password"] not in tree

        wrong = "Wrong-Local-2026!"
        self.command("type", type="secureTextField", text=wrong)
        self.tap("Unlock")
        tree = self.wait_for("label: 'Incorrect password'", "wrong-password")
        assert login["name"] not in tree
        self.command("type", type="secureTextField", text="\b" * len(wrong) + LOCAL_PASSWORD)
        self.tap("Unlock")
        self.wait_for(f"label: '{login['name']}'", "unlocked")

        self.command("terminate")
        self.command("launch")
        tree = self.wait_for("label: 'Unlock a vault'", "relaunch-locked")
        assert login["name"] not in tree
        self.unlock(tree)
        tree = self.wait_for(f"label: '{login['name']}'", "relaunch-unlocked")
        assert f"label: '{login['username']}'" in tree
        self.result({
            "passed": True, "serverCipher": item["id"],
            "checks": ["seed sync", "baseline login server values", "native detail reveal",
                       "manual lock", "wrong password", "correct password", "process relaunch",
                       "persisted vault"],
        })
        print("PASS: native and server baseline, manual lock, rejected/valid unlock, process relaunch.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, help="Evidence folder (default: <session>/smoke)")
    args = parser.parse_args()
    Smoke(args.evidence or evidence("smoke")).run()
