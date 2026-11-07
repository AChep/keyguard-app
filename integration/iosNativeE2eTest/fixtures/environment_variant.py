#!/usr/bin/env python3
"""Write, then restore, a future-format document on the leased environment item.

`future-field` adds an unknown top-level key; `future-source` adds a variable
with an unknown source kind next to a known literal. `restore` writes back the
recorded original fields. Only the main account's leased environment item is
writable. Requests stay on loopback without proxies or redirects; tokens and
keys stay in memory.

Lease, written by hand at <session>/environment-advanced-fixture-state.json
after the item is created in the app (with an empty document):

    {"itemId": "<server ID>", "name": "iPhone E2E Environment Advanced",
     "itemCleanupPending": true, "editorOpen": false,
     "newOwnershipDraftOpen": false, "referenceSourceEditorOpen": false}

Close every native environment draft and set the open flags to false first.
A variant mode also needs the exact decrypted empty-document item (--baseline),
for example the saved output of `e2e vault`. Restoration data goes to
<session>/environment-server-variant-state.json (mode 600) before each write.
Run `restore` after every variant; it refuses to overwrite an unexpected edit.
"""

import argparse
import copy
import hashlib
import json
from pathlib import Path

from lib import bitwarden, session
from lib.config import account

ITEM_NAME = "iPhone E2E Environment Advanced"
PREFIX = "keyguard.environment.v1."


def read_item(owner, item_id):
    item, key = owner.cipher(item_id)
    if (item["id"] != item_id or item["type"] != 2 or item.get("organizationId") or
            item.get("deletedDate") or item.get("folderId") or item.get("attachments") or
            bitwarden.decrypt(item["name"], key).decode() != ITEM_NAME):
        raise ValueError("Unexpected leased environment identity or ownership")
    fields = item.get("fields") or []
    if len(fields) != 2 or any(f["type"] != 1 or f.get("linkedId") is not None for f in fields):
        raise ValueError("Expected two hidden fixture transport fields")
    decoded = {bitwarden.decrypt(f["name"], key).decode(): bitwarden.decrypt(f["value"], key).decode()
               for f in fields}
    if set(decoded) != {PREFIX + "part.1", PREFIX + "sha256"}:
        raise ValueError("Unexpected transport names")
    document = decoded[PREFIX + "part.1"]
    if digest(document) != decoded[PREFIX + "sha256"]:
        raise ValueError("Transport checksum mismatch")
    return item, key, document


def digest(document):
    return hashlib.sha256(document.encode()).hexdigest()


def load_baseline(path, item_id):
    value = session.read_json(path)
    if isinstance(value, dict) and "items" in value:
        value = value["items"]
    if isinstance(value, list):
        matches = [item for item in value if item["id"] == item_id]
        if len(matches) != 1:
            raise ValueError("Baseline does not contain the leased item")
        value = matches[0]
    return value


def run(mode, baseline_path):
    lease = session.read_json(session.path("environment-advanced-fixture-state.json"))
    item_id = lease.get("itemId")
    if (not item_id or lease.get("name") != ITEM_NAME or
            not lease.get("itemCleanupPending") or any(lease.get(k) for k in
            ("editorOpen", "newOwnershipDraftOpen", "referenceSourceEditorOpen"))):
        raise ValueError("Fixture lease absent, cleanup complete, or native draft still open")
    owner = bitwarden.Session(account("main"))
    raw, key, current = read_item(owner, item_id)
    before = owner.ciphers()
    state_path = session.path("environment-server-variant-state.json")
    state = session.read_json(state_path) if state_path.exists() else {}
    if mode == "restore":
        if not state.get("cleanupPending") or state.get("itemId") != item_id:
            raise ValueError("No fixture variant requires restoration")
        if digest(current) not in (state["baselineHash"], state["variantHash"]):
            raise ValueError("Unexpected intervening document edit; refusing to overwrite")
        if raw.get("key") != state["baselineRaw"].get("key"):
            raise ValueError("Fixture cipher key changed")
        fields = state["baselineRaw"]["fields"]
        expected = state["baselineDecrypted"]
    else:
        if state.get("cleanupPending"):
            raise ValueError("Restore the previous fixture variant before another mutation")
        baseline = load_baseline(baseline_path, item_id)
        if next(x for x in before if x["id"] == item_id) != baseline:
            raise ValueError("Leased item is not the exact empty environment baseline")
        document = {"version": 1, "variables": []}
        if mode == "future-field":
            document["origin"] = "native-e2e-future-control"
        else:
            document["variables"] = [
                {"name": "UNKNOWN", "source": {"kind": "nativeE2eFuture", "fixture": "synthetic"}},
                {"name": "KNOWN", "source": {"kind": "literal", "value": "known-value"}},
            ]
        text = json.dumps(document, separators=(",", ":"))
        fields = [{"name": bitwarden.encrypt(PREFIX + suffix, key),
                   "value": bitwarden.encrypt(value, key), "type": 1, "linkedId": None}
                  for suffix, value in (("part.1", text), ("sha256", digest(text)))]
        state = {"itemId": item_id, "mode": mode, "cleanupPending": True,
                 "baselineRaw": raw, "baselineDecrypted": baseline,
                 "baselineHash": digest(current), "variantHash": digest(text),
                 "history": state.get("history", [])}
        session.write_json(state_path, state)
        expected = {**baseline, "fields": [
            {"name": PREFIX + suffix, "value": value, "type": 1, "linkedId": None}
            for suffix, value in (("part.1", text), ("sha256", digest(text)))
        ]}
    body = copy.deepcopy(raw)
    body["encryptedFor"] = owner.account_id
    body["fields"] = fields
    body["lastKnownRevisionDate"] = raw["revisionDate"]
    owner.request("/api/ciphers/" + item_id, body, method="PUT")
    owner.refresh()
    after = owner.ciphers()
    if next(x for x in after if x["id"] == item_id) != expected:
        raise ValueError("Variant write did not match the exact expected decrypted item")
    if bitwarden.others(after, item_id) != bitwarden.others(before, item_id):
        raise ValueError("Unexpected change outside leased environment")
    state["history"].append({"mode": mode, "exactWriteVerified": True})
    if mode == "restore":
        state["cleanupPending"] = False
    session.write_json(state_path, state)
    session.write_json(session.path(f"environment-server-variant-{mode}-server.json"), expected)
    print(json.dumps({"mode": mode, "itemId": item_id, "exactWriteVerified": True,
                      "otherItemsUnchanged": True, "cleanupPending": state["cleanupPending"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=("future-field", "future-source", "restore"))
    parser.add_argument("--baseline", type=Path,
                        help="Decrypted leased item with an empty document, or a snapshot list "
                             "containing it (default: "
                             "<session>/environment-empty-document-server.json)")
    args = parser.parse_args()
    run(args.mode, args.baseline or session.path("environment-empty-document-server.json"))
