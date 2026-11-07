#!/usr/bin/env python3
"""Set or clear the URIs of the leased Watchtower Login.

`configure` writes three github.com URIs with fixed match modes to a Login that
has none; `clear` removes them again. Only the main account's leased Login is
writable. Requests stay on loopback without proxies or redirects; the URI's
website is never contacted. Each write is checked against the exact expected
decrypted item, the stored match modes and every other item.

Lease, written by hand at <session>/watchtower-positive-fixture-state.json after
the Login is created in the app:

    {"ownedItems": [{"kind": "login", "name": "iPhone E2E Watchtower Categories",
                     "uuid": "<server ID>"}],
     "itemCleanupPending": true, "editorOpen": false,
     "uriVariantWritePending": false}

The script records `uriVariantWritePending`, `uriVariantMode`,
`uriVariantBeforeRaw` and `uriVariantHistory`. --before is a vault snapshot
taken before the Login was created (saved `e2e vault` output); the leased ID
must not be in it. Keep password checks off.
"""

import argparse
import copy
import json
import os
from pathlib import Path

from lib import bitwarden, session
from lib.config import account
from oracles.vault import load

NAME = "iPhone E2E Watchtower Categories"
URIS = [
    {"uri": "https://github.com/login", "match": 0},
    {"uri": "https://github.com/settings/security", "match": 0},
    {"uri": "https://github.com/", "match": 1},
]


def owned_id(state, originals):
    matches = [x for x in state["ownedItems"] if x["kind"] == "login"]
    if (len(matches) != 1 or matches[0]["name"] != NAME or
            matches[0]["uuid"] in {x["id"] for x in originals} or
            not state.get("itemCleanupPending") or state.get("editorOpen") or
            state.get("uriVariantWritePending")):
        raise ValueError("No safe active Watchtower Login lease")
    return matches[0]["uuid"]


def stored_uris(raw, key):
    return [{"uri": bitwarden.decrypt(x["uri"], key).decode(), "match": x.get("match")}
            for x in raw["login"].get("uris") or []]


def run(mode, before_path):
    os.umask(0o077)
    lease = session.path("watchtower-positive-fixture-state.json")
    state = session.read_json(lease)
    ident = owned_id(state, load(before_path))
    owner = bitwarden.Session(account("main"))
    raw, key = owner.cipher(ident)
    if (raw["id"] != ident or raw["type"] != 1 or raw.get("organizationId") or
            raw.get("deletedDate") or raw.get("folderId") or raw.get("attachments") or
            bitwarden.decrypt(raw["name"], key).decode() != NAME):
        raise ValueError("Unexpected owned Login identity, type or content")
    if stored_uris(raw, key) != ([] if mode == "configure" else URIS):
        raise ValueError("URI baseline changed; refusing to overwrite")
    desired = URIS if mode == "configure" else []
    before = owner.ciphers()
    baseline = next(x for x in before if x["id"] == ident)
    expected = {**baseline, "uris": [x["uri"] for x in desired]}
    body = copy.deepcopy(raw)
    body["encryptedFor"] = owner.account_id
    body["lastKnownRevisionDate"] = raw["revisionDate"]
    body["login"]["uris"] = [{"uri": bitwarden.encrypt(x["uri"], key), "match": x["match"]}
                             for x in desired]
    state.update(uriVariantWritePending=True, uriVariantMode=mode, uriVariantBeforeRaw=raw)
    session.write_json(lease, state)
    owner.request("/api/ciphers/" + ident, body, method="PUT")
    owner.refresh()
    after = owner.ciphers()
    if next(x for x in after if x["id"] == ident) != expected:
        raise ValueError("Owned Login does not match the exact expected URI-only change")
    if bitwarden.others(before, ident) != bitwarden.others(after, ident):
        raise ValueError("Unexpected change outside the owned Login")
    actual = stored_uris(owner.request("/api/ciphers/" + ident), key)
    if actual != desired:
        raise ValueError("Stored URI match modes differ from the fixture")
    state["uriVariantWritePending"] = False
    state.setdefault("uriVariantHistory", []).append({"mode": mode, "uris": actual,
                                                    "exactOwnedChange": True, "othersExact": True})
    session.write_json(lease, state)
    session.write_json(session.path(f"watchtower-positive-uris-{mode}-server.json"), expected)
    print(json.dumps({"mode": mode, "itemId": ident, "exactOwnedUriChange": True,
                      "otherItemsExact": True}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=("configure", "clear"))
    parser.add_argument("--before", type=Path,
                        help="Vault snapshot from before the Login was created "
                             "(default: <session>/watchtower-positive-source-before.json)")
    args = parser.parse_args()
    run(args.mode, args.before or session.path("watchtower-positive-source-before.json"))
