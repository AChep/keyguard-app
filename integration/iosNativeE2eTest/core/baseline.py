#!/usr/bin/env python3
"""Create the smoke baseline: local vault, main account, forced premium and login.

Run on the dedicated simulator after `e2e bootstrap`, with `e2e driver serve`
and the runner active. Every step checks the app or server state first and
skips work that is already done, so a failed run can be repeated. Creates the
baseline login on the main account's server through the app; never edits or
deletes server items. Leaves the app unlocked on the vault.
"""

import argparse
import re
import time
import urllib.parse
from pathlib import Path

from core.smoke import SEEDED
from lib import bitwarden
from lib.config import BASELINE_LOGIN, LOCAL_PASSWORD, account
from lib.journey import Journey, evidence, send

FRAME = re.compile(r"\{\{(-?[\d.]+), (-?[\d.]+)\}, \{([\d.]+), ([\d.]+)\}\}")
# Bitwarden cipher type of a login.
LOGIN_TYPE = 1


def frame(line):
    x, y, width, height = map(float, FRAME.search(line).groups())
    return x, y, width, height


def find_line(tree, *parts, after=None):
    """The first tree line containing every part, optionally after a line containing `after`."""
    lines = tree.splitlines()
    if after is not None:
        start = next((i for i, line in enumerate(lines) if after in line), None)
        if start is None:
            return None
        lines = lines[start + 1:]
    return next((line for line in lines if all(part in line for part in parts)), None)


def visible_bottom(tree):
    """Top of the software keyboard, or the bottom of the app area above the tab bar."""
    tops = [frame(line)[1] for line in tree.splitlines()
            if "identifier: 'inputView'" in line or line.lstrip().startswith("Keyboard,")]
    return min(tops, default=770)


def mismatches(item, login):
    expected = {"type": LOGIN_TYPE, "username": login["username"],
                "password": login["password"], "uris": [login["uri"]]}
    return [field for field, value in expected.items() if item[field] != value]


class Baseline(Journey):
    def __init__(self, evidence):
        super().__init__(evidence)
        self.account = account("main")
        host = urllib.parse.urlparse(self.account.server.domain).hostname
        # Account rows and the editor's Save to button: "<profile name>, <host>".
        self.account_label = f"{self.account.name}, {host}"
        self.performed = []
        self.skipped = []

    def step(self, name, done):
        (self.performed if done else self.skipped).append(name)

    # Waiting ---------------------------------------------------------------------

    def dismiss_save_password(self, tree, system=False):
        """Dismiss the iOS Save Password prompt with Not Now; returns whether it was shown."""
        if "label: 'Save Password?'" in tree:
            self.tap("Not Now")
            return True
        if system and "label: 'Save Password?'" in self.command("systemTree")["tree"]:
            self.command("systemTap", type="button", label="Not Now")
            return True
        return False

    def wait_until(self, check, name, what, timeout=35):
        deadline = time.monotonic() + timeout
        while True:
            tree = self.tree(name)
            if not self.dismiss_save_password(tree) and check(tree):
                return tree
            if time.monotonic() >= deadline:
                raise AssertionError(f"Native UI did not show {what}")
            time.sleep(0.5)

    def wait_text(self, text, name, timeout=35):
        return self.wait_until(lambda tree: text in tree, name, repr(text), timeout)

    def wait_gone(self, text, name, timeout=35):
        return self.wait_until(lambda tree: text not in tree, name, f"no {text!r}", timeout)

    def catch_save_password(self, timeout=5):
        """Give a late Save Password prompt a short time to appear in the app or SpringBoard."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.dismiss_save_password(self.tree("save-password"), system=True):
                return
            time.sleep(0.5)

    # Navigation ------------------------------------------------------------------

    def open_vault(self):
        self.tap("Vault")
        return self.wait_text("label: 'Search vault'", "vault")

    def open_settings(self):
        """Settings root with search closed and no pushed page."""
        tree = self.tree("before-settings")
        # Settings moves into the overflow menu when there are more than five tabs.
        if "identifier: 'navigation.overflow'" in tree:
            self.command("tap", type="button", identifier="navigation.overflow")
        self.tap("Settings")
        for _ in range(5):
            tree = self.wait_until(
                lambda t: "label: 'Search settings'" in t or "identifier: 'BackButton'" in t,
                "settings", "Settings")
            if "label: 'Search settings'" not in tree:
                self.command("tap", type="button", identifier="BackButton")
            elif "label: 'Close'" in tree:
                self.tap("Close")
                self.wait_gone("label: 'Close'", "settings-search-closed")
            else:
                return tree
        raise AssertionError("Could not return to the Settings root")

    # Steps -----------------------------------------------------------------------

    def vault(self):
        self.command("launch")
        tree = self.wait_until(
            lambda t: any(marker in t for marker in (
                "label: 'Create a vault'", "label: 'Unlock a vault'", "label: 'Tab Bar'",
                "identifier: 'navigation.overflow'")),
            "launch", "the create, unlock or unlocked screen")
        if "label: 'Create a vault'" in tree:
            self.command("type", type="secureTextField", text=LOCAL_PASSWORD)
            tree = self.tree("vault-password")
            create = find_line(tree, "Button,", "label: 'Create a vault'")
            assert create and "Disabled" not in create, "Create a vault stayed disabled"
            self.tap("Create a vault")
            self.step("create local vault", True)
            self.step("unlock local vault", False)
        else:
            self.step("create local vault", False)
            unlocked = self.unlock(tree)
            self.step("unlock local vault", unlocked)
        tree = self.wait_until(
            lambda t: "label: 'Incorrect password'" in t or (
                "label: 'Unlock a vault'" not in t
                and ("label: 'Tab Bar'" in t or "identifier: 'navigation.overflow'" in t)),
            "unlocked", "the unlocked vault")
        assert "label: 'Incorrect password'" not in tree, (
            "The existing local vault does not accept LOCAL_PASSWORD; reset the simulator")
        tree = self.open_vault()
        # First-run catalog banner: Learn more opens Features Overview; OK dismisses both.
        if "label: 'Learn more'" in tree and "identifier: 'info.circle'" in tree:
            self.tap("Learn more")
            self.wait_text("label: 'Features Overview'", "features-overview")
            self.tap("OK")
            self.wait_gone("label: 'Features Overview'", "features-overview-closed")
            self.step("dismiss onboarding", True)
        else:
            self.step("dismiss onboarding", False)

    def sign_in(self):
        tree = self.open_settings()
        if f"label: '{self.account_label}'" in tree:
            self.step("add main account", False)
            return
        acct = self.account
        self.tap("Add account")
        self.wait_text("label: 'Bitwarden'", "add-account-menu")
        self.tap("Bitwarden")
        self.wait_text("label: 'Custom (self-hosted)'", "bitwarden-login")
        self.tap("Custom (self-hosted)")
        self.wait_text("placeholderValue: 'Server URL'", "custom-server")
        self.command("type", type="textField", placeholder="Email", text=acct.email)
        self.command("type", type="secureTextField", placeholder="Password", text=acct.password)
        self.command("type", type="textField", placeholder="Server URL", text=acct.server.domain)
        for placeholder, expected in (("Email", acct.email), ("Server URL", acct.server.domain)):
            value = self.command("value", type="textField", placeholder=placeholder)["value"]
            assert value == expected, f"{placeholder} field holds {value!r}"
        # Sign in sits below the custom environment fields.
        self.command("drag", x=10, y=450, toX=10, toY=210)
        for _ in range(3):
            self.command("drag", x=10, y=450, toX=10, toY=210)
            button = find_line(self.tree("sign-in-ready"), "Button,", "label: 'Sign in'")
            assert button and "Disabled" not in button, "Sign in is disabled"
            if send({"action": "tap", "type": "button", "label": "Sign in"}).get("ok"):
                break
        else:
            raise AssertionError("Sign in did not become hittable")
        failure = "label: 'Username or password is incorrect. Try again'"
        tree = self.wait_until(
            lambda t: failure in t or f"label: '{self.account_label}'" in t
            or f"label: '{SEEDED[0]}'" in t,
            "signed-in", "the added account", timeout=90)
        assert failure not in tree, "The server rejected the main account credentials"
        self.catch_save_password()
        tree = self.open_settings()
        assert f"label: '{self.account_label}'" in tree, "Settings does not list the main account"
        self.step("add main account", True)

    def premium(self):
        self.open_settings()
        self.command("type", type="searchField", label="Search settings", text="premium")
        self.wait_text("label: 'Force premium status, Development'", "settings-search-premium")
        self.tap("Force premium status, Development")
        switch = "label: 'Force premium status', value: "
        tree = self.wait_text(switch, "development")
        line = find_line(tree, "Switch,", switch)
        if switch + "1" in line:
            self.step("force premium", False)
        else:
            # The row is the switch; only its trailing control toggles it.
            x, y, width, height = frame(line)
            self.command("tapPoint", x=x + width - 46, y=y + height / 2)
            self.wait_text(switch + "1", "development-premium-on")
            self.step("force premium", True)
        self.command("tap", type="button", identifier="BackButton")
        self.wait_text("label: 'Close'", "settings-search-premium-back")
        self.tap("Close")
        self.wait_gone("label: 'Close'", "settings-search-closed")

    def server_logins(self, session):
        return [item for item in session.ciphers()
                if item["name"] == BASELINE_LOGIN["name"] and not item["deletedDate"]]

    def check_server_login(self, items):
        login = BASELINE_LOGIN
        assert len(items) == 1, f"Expected one live {login['name']!r} on the server, found {len(items)}"
        wrong = mismatches(items[0], login)
        assert not wrong, (
            f"The server already has {login['name']!r} with a different {', '.join(wrong)}; "
            "baseline does not edit it. Fix or delete it on the server, then rerun.")
        return items[0]

    def add_more_uri(self):
        """Open the URIs section's Add more menu; only a tap on its label opens it."""
        for _ in range(3):
            tree = self.tree("add-uri")
            if "label: 'URI'" in tree:
                return
            line = find_line(tree, "StaticText,", "label: 'Add more'", after="label: 'URIs'")
            assert line, "The login editor has no URIs › Add more"
            x, y, width, height = frame(line)
            point = (x + width / 2, y + height / 2)
            if not 140 < point[1] < visible_bottom(tree) - 10:
                self.command("drag", x=201, y=503, toX=201, toY=293)
                continue
            self.command("tapPoint", x=point[0], y=point[1])
            try:
                self.wait_text("label: 'URI'", "uri-menu", timeout=5)
                return
            except AssertionError:
                pass
        raise AssertionError("URIs › Add more did not offer URI")

    def login(self):
        login = BASELINE_LOGIN
        session = bitwarden.Session(self.account)
        items = self.server_logins(session)
        if items:
            item = self.check_server_login(items)
            self.step("create baseline login", False)
            return item
        self.open_vault()
        self.tap("New item")
        tree = self.wait_until(
            lambda t: "identifier: 'person.badge.key', label: 'Login'" in t
            or "identifier: 'star.circle', label: 'Keyguard Premium'" in t,
            "new-item-types", "the New item menu")
        assert "label: 'Keyguard Premium'" not in tree, "New item still offers only Keyguard Premium"
        self.tap("Login")
        tree = self.wait_text("label: 'URIs'", "add-login")
        assert f"label: 'Save to, {self.account_label}'" in tree, (
            "The login editor does not save to the main account")
        self.command("type", type="textField", label="Name", text=login["name"])
        self.command("type", type="textField", label="Username", text=login["username"])
        self.command("type", type="secureTextField", label="Password", text=login["password"])
        self.add_more_uri()
        self.tap("URI")
        self.wait_text("label: 'Website (URI)'", "uri-field")
        self.command("type", type="textField", label="Website (URI)", text=login["uri"])
        for label, expected in (("Name", login["name"]), ("Username", login["username"]),
                                ("Website (URI)", login["uri"])):
            value = self.command("value", type="textField", label=label)["value"]
            assert value == expected, f"{label} field holds {value!r}"
        self.tap("Save")
        self.wait_until(
            lambda t: "identifier: 'New item'" not in t and f"label: '{login['name']}'" in t,
            "created-login", f"{login['name']!r} in the vault")
        self.catch_save_password()
        deadline = time.monotonic() + 60
        while not (items := self.server_logins(session)):
            assert time.monotonic() < deadline, f"The server did not receive {login['name']!r}"
            time.sleep(1)
            session.refresh()
        self.step("create baseline login", True)
        return self.check_server_login(items)

    def run(self):
        self.vault()
        self.sign_in()
        self.premium()
        item = self.login()
        login = BASELINE_LOGIN
        tree = self.open_vault()
        for name in (*SEEDED, login["name"]):
            if f"label: '{name}'" not in tree:
                tree = self.wait_text(f"label: '{name}'", "baseline-vault", timeout=60)
        assert f"label: '{login['username']}'" in tree, "The vault list does not show the login username"
        self.result({"passed": True, "serverCipher": item["id"],
                     "performed": self.performed, "skipped": self.skipped})
        print(f"PASS: baseline ready; performed: {', '.join(self.performed) or 'nothing'}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, help="Evidence folder (default: <session>/baseline)")
    args = parser.parse_args()
    Baseline(args.evidence or evidence("baseline")).run()
