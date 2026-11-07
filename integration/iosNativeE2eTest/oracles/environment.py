#!/usr/bin/env python3
"""Decode an environment item's transport fields from a saved vault snapshot.

Reconstructs the ordered hidden `keyguard.environment.v1.part.N` chunks, checks
their SHA-256 and parses the version-1 document. Uses JSON and SHA-256 only; no
Keyguard codecs or network requests. The CLI prints variable names, not values.
Other scripts import decode_environment(item).
"""

import argparse
import hashlib
import json
import re
from pathlib import Path

PREFIX = "keyguard.environment.v1."


def decode_environment(item):
    fields = [x for x in item["fields"] if (x["name"] or "").startswith(PREFIX)]
    if not fields or any(x["type"] != 1 or x.get("linkedId") is not None for x in fields):
        raise ValueError("Expected hidden environment transport fields")
    if len({x["name"] for x in fields}) != len(fields):
        raise ValueError("Duplicate environment transport field")
    parts = {}
    checksum = None
    for field in fields:
        suffix = field["name"][len(PREFIX):]
        if suffix == "sha256":
            checksum = field["value"]
        elif re.fullmatch(r"part\.[1-9][0-9]*", suffix):
            parts[int(suffix[5:])] = field["value"]
        else:
            raise ValueError("Unknown environment transport field")
    if not parts or sorted(parts) != list(range(1, len(parts) + 1)):
        raise ValueError("Missing environment chunk")
    text = "".join(parts[i] for i in sorted(parts))
    if hashlib.sha256(text.encode()).hexdigest() != checksum:
        raise ValueError("Environment checksum mismatch")
    document = json.loads(text)
    if document.get("version") != 1 or not isinstance(document.get("variables"), list):
        raise ValueError("Unexpected environment document")
    return document


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path,
                        help="Decrypted item or item list, e.g. saved `e2e vault` output")
    parser.add_argument("--name", default="iOS E2E Environment",
                        help="Name of the environment item (default: %(default)s)")
    args = parser.parse_args()
    items = json.loads(args.snapshot.read_text())
    if isinstance(items, dict):
        items = [items]
    matches = [x for x in items if x["name"] == args.name]
    if len(matches) != 1:
        raise ValueError("Expected exactly one named fixture")
    document = decode_environment(matches[0])
    print(json.dumps({"version": document["version"], "checksumVerified": True,
                      "variableNames": [x["name"] for x in document["variables"]]}))
