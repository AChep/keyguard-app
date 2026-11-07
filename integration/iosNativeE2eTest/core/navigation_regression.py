#!/usr/bin/env python3
"""Check that an open vault item survives switching to overflow tabs and back.

Keeps the baseline login open while switching through Settings to Generator
and GPG tools, twice, then checks the detail and its Copy action still work.
Needs the baseline. Does not change items, settings or server data.
"""

import argparse
import subprocess
import time
from pathlib import Path

from lib.config import BASELINE_LOGIN
from lib.journey import Journey, evidence


class NavigationRegression(Journey):
    def security(self):
        self.command("tap", type="button", identifier="navigation.overflow")
        self.tap("Settings")
        tree = self.wait_for("label: 'Security'", "settings")
        if "label: 'Automatically clear clipboard, " not in tree:
            self.tap("Security")
        self.wait_for("label: 'Automatically clear clipboard, ", "security")

    def run(self, device):
        login = BASELINE_LOGIN
        self.command("launch")
        if self.unlock(self.tree("launch")):
            self.wait_for("identifier: 'navigation.overflow'", "unlocked")
        self.tap("Vault")
        tree = self.wait_for(f"label: '{login['name']}'", "vault")
        if f"value: {login['username']}" not in tree:
            self.tap(login["name"], type="staticText")
        self.wait_for(f"value: {login['username']}", "detail")

        for cycle in range(2):
            self.security()
            self.tap("Generator")
            tree = self.tree(f"generator-{cycle}")
            if "label: 'Wordlists'" in tree:
                self.command("tap", type="button", identifier="BackButton")
            self.wait_for("label: 'Regenerate'", f"generator-root-{cycle}")
            self.tap("Regenerate")
            self.security()
            self.tap("GPG tools")
            self.wait_for("label: 'Message'", f"gpg-{cycle}")
            self.tap("Vault")
            tree = self.wait_for(f"value: {login['username']}", f"retained-detail-{cycle}")
            assert login["uri"] in tree
            self.tap("Copy", index=0)
            copied = subprocess.check_output(["xcrun", "simctl", "pbpaste", device], text=True)
            assert copied == login["username"], copied
            time.sleep(0.2)

        self.result({
            "passed": True,
            "cycles": 2,
            "checks": ["retained vault detail", "Settings to Generator", "Settings to GPG tools",
                       "Generator action", "vault detail copy action"],
        })
        print("PASS: two overflow-tab cycles, retained vault detail and live actions.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="Dedicated simulator UDID ($E2E_DEVICE)")
    parser.add_argument("--evidence", type=Path,
                        help="Evidence folder (default: <session>/navigation-regression)")
    args = parser.parse_args()
    NavigationRegression(args.evidence or evidence("navigation-regression")).run(args.device)
