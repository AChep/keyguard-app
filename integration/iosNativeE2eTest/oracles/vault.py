#!/usr/bin/env python3
"""Print an account's independently decrypted items or Sends as JSON.

Reads the account's loopback server through lib.bitwarden; never uses Keyguard
code. The output contains decrypted synthetic secrets: keep it in the session.
Redirect it to a file to capture a baseline for the fixtures and oracles.
"""

import argparse
import json
from pathlib import Path

from lib import bitwarden
from lib.config import ACCOUNTS, account


def load(path):
    """Items of a saved snapshot: a list, or an object with an `items` list."""
    value = json.loads(Path(path).read_text())
    return value["items"] if isinstance(value, dict) else value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", default="main", choices=ACCOUNTS,
                        help="Account to read (default: main)")
    parser.add_argument("--sends", action="store_true", help="Print Sends instead of items")
    args = parser.parse_args()
    target = account(args.account)
    value = bitwarden.send_snapshot(target) if args.sends else bitwarden.snapshot(target)
    print(json.dumps(value, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
