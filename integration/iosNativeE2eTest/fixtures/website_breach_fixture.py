#!/usr/bin/env python3
"""Give the leased Login a breached website URI and an old password-change date.

Sets the URI to https://adobe.com (match mode 0) and the password revision date
to 2010-01-01 on a main-account Login that has no URIs or custom fields, so the
cached public breach list flags the website. Runs once per lease. Requests stay
on loopback without proxies or redirects; HIBP and the website are never
contacted. The write is checked against the exact expected decrypted item, the
stored URI/date and every other item.

Lease, written by hand at <session>/website-breach-fixture-state.json after the
Login is created in the app:

    {"fixtureName": "iPhone E2E Website Breach", "itemId": "<server ID>",
     "itemCleanupPending": true, "editorOpen": false,
     "serverPreparationPending": false, "serverPrepared": false}

--before is a vault snapshot taken before the Login was created (saved
`e2e vault` output); the leased ID must not be in it. Keep password checks off.
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

NAME = "iPhone E2E Website Breach"
URI = "https://adobe.com"
OLD_PASSWORD_DATE = "2010-01-01T00:00:00Z"


def owned_id(state, originals):
    ident = state.get("itemId")
    if (not ident or ident in {x["id"] for x in originals} or
            state.get("fixtureName") != NAME or not state.get("itemCleanupPending") or
            state.get("editorOpen") or state.get("serverPreparationPending") or
            state.get("serverPrepared")):
        raise ValueError("No safe active website-breach fixture lease")
    return ident


def instant(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def prepare(before_path):
    os.umask(0o077)
    lease = session.path("website-breach-fixture-state.json")
    state = session.read_json(lease)
    ident = owned_id(state, load(before_path))
    owner = bitwarden.Session(account("main"))
    raw, key = owner.cipher(ident)
    if (raw["id"] != ident or raw["type"] != 1 or raw.get("organizationId") or
            raw.get("deletedDate") or raw.get("folderId") or raw.get("attachments") or
            raw.get("fields") or raw["login"].get("uris") or
            bitwarden.decrypt(raw["name"], key).decode() != NAME):
        raise ValueError("Unexpected owned fixture identity or contents")
    before = owner.ciphers()
    expected = {**next(x for x in before if x["id"] == ident), "uris": [URI]}
    body = copy.deepcopy(raw)
    body["encryptedFor"] = owner.account_id
    body["lastKnownRevisionDate"] = raw["revisionDate"]
    body["login"]["uris"] = [{"uri": bitwarden.encrypt(URI, key), "match": 0}]
    body["login"]["passwordRevisionDate"] = OLD_PASSWORD_DATE
    state.update(serverPreparationPending=True, beforePreparationRaw=raw)
    session.write_json(lease, state)
    owner.request("/api/ciphers/" + ident, body, method="PUT")
    owner.refresh()
    after = owner.ciphers()
    if next(x for x in after if x["id"] == ident) != expected:
        raise ValueError("Owned fixture did not match the exact expected URI change")
    if bitwarden.others(before, ident) != bitwarden.others(after, ident):
        raise ValueError("Unexpected change outside the owned fixture")
    stored = owner.request("/api/ciphers/" + ident)
    if (instant(stored["login"]["passwordRevisionDate"]) != instant(OLD_PASSWORD_DATE) or
            [{"uri": bitwarden.decrypt(x["uri"], key).decode(), "match": x.get("match")}
             for x in stored["login"]["uris"]] != [{"uri": URI, "match": 0}]):
        raise ValueError("Stored URI/match mode/password date differs from the fixture")
    state.update(serverPreparationPending=False, serverPrepared=True,
                 oldPasswordRevisionDate=OLD_PASSWORD_DATE, uri=URI)
    session.write_json(lease, state)
    session.write_json(session.path("website-breach-prepared-server.json"), expected)
    print(json.dumps({"itemId": ident, "exactOwnedPreparationVerified": True,
                      "otherItemsExact": True, "allRequestsFixedLoopbackOnly": True}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--before", type=Path,
                        help="Vault snapshot from before the Login was created "
                             "(default: <session>/website-breach-source-before.json)")
    args = parser.parse_args()
    prepare(args.before or session.path("website-breach-source-before.json"))
