"""Client of the `e2e driver serve` broker, and a base class for scripted journeys.

All app interaction goes through XCTest. A journey saves each UI tree it reads
under its evidence folder; trees can contain synthetic vault content.
"""

import json
import time
import urllib.request

from lib import session
from lib.config import LOCAL_PASSWORD, PORTS

ADDRESS = f"http://127.0.0.1:{PORTS['driver']}"


def send(command):
    """Run one driver command and return its result."""
    request = urllib.request.Request(ADDRESS + "/command", json.dumps(command).encode(),
                                     {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=100) as response:
        return json.load(response)


class Journey:
    def __init__(self, evidence):
        self.evidence = evidence
        evidence.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.sequence = 0

    def command(self, action, **kwargs):
        result = send({"action": action, **kwargs})
        assert result.get("ok"), result
        return result

    def tap(self, label, type="button", **kwargs):
        return self.command("tap", type=type, label=label, **kwargs)

    def tree(self, name):
        tree = self.command("tree")["tree"]
        self.sequence += 1
        (self.evidence / f"{self.sequence:02}-{name}.txt").write_text(tree)
        return tree

    def wait_for(self, text, name, timeout=35):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            tree = self.tree(name)
            if text in tree:
                return tree
            time.sleep(0.5)
        raise AssertionError(f"Native UI did not show {text!r}")

    def unlock(self, tree, password=LOCAL_PASSWORD):
        """Unlock the local vault if `tree` shows the unlock screen; returns whether it did."""
        if "label: 'Unlock a vault'" not in tree:
            return False
        self.command("type", type="secureTextField", text=password)
        self.tap("Unlock")
        return True

    def result(self, value):
        session.write_json(self.evidence / "result.json", value)


def evidence(name):
    """Default evidence folder of a journey inside the session."""
    return session.directory() / name
