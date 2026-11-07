#!/usr/bin/env python3
"""Apply an independent peer edit to the leased offline-conflict Login.

`capture CASE` records the remote baseline of the leased main-account Login.
`peer CASE` then applies that case's fixed edit as another client would, while
the app holds a queued offline edit. Cases: different-field and same-field edit
notes, password changes the password (and appends history), totp sets a TOTP
secret, hidden removes the only hidden custom field. Each case runs once.

Lease, written by hand at <session>/bitwarden-offline-fixture-state.json after
the Login is created in the app:

    {"fixtureName": "iPhone E2E Bitwarden Offline", "itemId": "<server ID>",
     "itemCleanupPending": true, "editorOpen": false,
     "serviceRestorationPending": false, "appTerminatedForPeer": false,
     "offlineEditPending": false, "peerWritePending": false, "phases": []}

The script appends to `phases` and manages `peerWritePending`. The session sets
the other flags: `editorOpen` while a native editor is open,
`serviceRestorationPending` while the server is unreachable, and
`offlineEditPending` plus `appTerminatedForPeer` once a native edit is queued and
the app is terminated. `peer` requires the last two; set `resolvedVerified` on
the phase once the native resolution is checked, before the next capture.

--before is a vault snapshot taken before the Login was created (saved
`e2e vault` output); the leased ID must not be in it. Requests stay on loopback
without proxies or redirects. Each peer write is checked against the exact
expected decrypted item and every other item.
"""

import argparse
import copy
import datetime
import json
import os
from pathlib import Path

from lib import bitwarden, session
from lib.config import account
from oracles.vault import load

NAME = "iPhone E2E Bitwarden Offline"
CASES = {
    "different-field": {"notes": "BW peer notes — different field"},
    "same-field": {"notes": "BW peer notes — remote winner"},
    "password": {"password": "BW-Remote-Secret-2026!"},
    "totp": {"totp": "KRSXG5DSNFXGOIDB"},
    "hidden": {"removeHiddenField": True},
}


def owned(state, owner, originals):
    ident = state.get("itemId")
    if (not ident or ident in {x["id"] for x in originals} or
            state.get("fixtureName") != NAME or not state.get("itemCleanupPending")):
        raise ValueError("No active owned offline fixture lease")
    raw, key = owner.cipher(ident)
    if (raw["id"] != ident or raw["type"] != 1 or raw.get("organizationId") or
            raw.get("deletedDate") or raw.get("folderId") or raw.get("attachments") or
            bitwarden.decrypt(raw["name"], key).decode() != NAME):
        raise ValueError("Unexpected fixture identity, type, ownership or content")
    return raw, key


def run(action, case, before_path):
    os.umask(0o077)
    lease = session.path("bitwarden-offline-fixture-state.json")
    state = session.read_json(lease)
    if state.get("editorOpen") or state.get("serviceRestorationPending"):
        raise ValueError("Close the native editor and restore the service first")
    if action == "peer" and not (
            state.get("appTerminatedForPeer") and state.get("offlineEditPending")):
        raise ValueError("Peer mutation requires a recorded offline edit and terminated app")
    owner = bitwarden.Session(account("main"))
    raw, key = owned(state, owner, load(before_path))
    before = owner.ciphers()
    baseline = next(x for x in before if x["id"] == state["itemId"])
    phases = state["phases"]
    if action == "capture":
        if state.get("peerWritePending") or state.get("offlineEditPending"):
            raise ValueError("Resolve the previous phase before capturing another")
        if phases and not phases[-1]["resolvedVerified"]:
            raise ValueError("Verify the previous phase before capturing another")
        if any(x["case"] == case for x in phases):
            raise ValueError("Never overwrite existing phase evidence")
        phases.append({"case": case, "baseRaw": raw, "baseDecrypted": baseline,
                       "peerWriteVerified": False, "resolvedVerified": False})
        session.write_json(lease, state)
        print(json.dumps({"captured": case, "itemId": state["itemId"]}))
        return
    phase = phases[-1]
    if (phase["case"] != case or phase["peerWriteVerified"] or
            state.get("peerWritePending") or raw != phase["baseRaw"] or
            baseline != phase["baseDecrypted"]):
        raise ValueError("Remote baseline changed or phase is not writable")
    body = copy.deepcopy(raw)
    body["encryptedFor"] = owner.account_id
    body["lastKnownRevisionDate"] = raw["revisionDate"]
    expected = copy.deepcopy(baseline)
    now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    for field, value in CASES[case].items():
        if field == "removeHiddenField":
            if baseline["fields"] != [{"name": "E2E hidden secret", "value": "BW-Hidden-Base-2026!",
                                       "type": 1, "linkedId": None}]:
                raise ValueError("Expected only the owned hidden-secret field")
            body["fields"] = []
            expected["fields"] = []
            continue
        target = body if field == "notes" else body["login"]
        target[field] = bitwarden.encrypt(value, key)
        expected[field] = value
        if field == "password":
            body["login"]["passwordRevisionDate"] = now
            body["passwordHistory"] = [*(body.get("passwordHistory") or []),
                                       {"password": raw["login"]["password"], "lastUsedDate": now}]
    state["peerWritePending"] = True
    phase["expectedPeerDecrypted"] = expected
    session.write_json(lease, state)
    owner.request("/api/ciphers/" + state["itemId"], body, method="PUT")
    owner.refresh()
    after = owner.ciphers()
    if next(x for x in after if x["id"] == state["itemId"]) != expected:
        raise ValueError("Peer write did not match the exact expected decrypted item")
    if bitwarden.others(before, state["itemId"]) != bitwarden.others(after, state["itemId"]):
        raise ValueError("Unexpected change outside owned fixture")
    phase["peerWriteVerified"] = True
    state["peerWritePending"] = False
    session.write_json(lease, state)
    session.write_json(session.path(f"bitwarden-offline-{case}-peer-server.json"), expected)
    print(json.dumps({"peerWriteVerified": case, "itemId": state["itemId"], "otherItemsExact": True}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=("capture", "peer"))
    parser.add_argument("case", choices=CASES)
    parser.add_argument("--before", type=Path,
                        help="Vault snapshot from before the Login was created "
                             "(default: <session>/bitwarden-offline-source-before.json)")
    args = parser.parse_args()
    run(args.action, args.case, args.before or session.path("bitwarden-offline-source-before.json"))
