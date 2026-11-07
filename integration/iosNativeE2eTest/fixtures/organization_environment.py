#!/usr/bin/env python3
"""Lease one organization environment and switch the member's Hide passwords flag.

`setup` creates a synthetic environment in the Default collection that references
the organization's shared Login, then clears org-member's Hide passwords flag.
`hide` sets the flag again; `cleanup` sets it and deletes only the leased
environment, then requires the exact owner/member baselines. The member's role,
read-only flag, groups and Default-only scope never change. No invitation or
mail is sent.

Requires `e2e organization --prepare-restricted-member` first. Sign the member
out of the app before each mode: the modes refuse to run while either state file
has `nativeMemberSignedIn` or `nativeMemberCleanupPending` set to true. Write
those flags by hand into <session>/organization-fixture-state.json around native
member sign-in and sign-out.

Private state, including the decrypted owner, member and main-account baselines,
is kept in <session>/organization-environment-fixture-state.json (mode 600) and
is written before every server change. Every mode checks that the main account
and the organization's other data are unchanged.
"""

import argparse
import hashlib
import json

from lib import bitwarden, session
from lib.config import account
from oracles import organization as org
from oracles.environment import decode_environment

NAME = "iPhone E2E Org Readonly Environment"
PREFIX = "keyguard.environment.v1."


def state_path():
    return session.path("organization-environment-fixture-state.json")


def save(value):
    session.write_json(state_path(), value)


def document(shared_login):
    return {"version": 1, "variables": [
        {"name": "LITERAL", "source": {"kind": "literal", "value": "org-env-literal-E2E"}},
        {"name": "PASSWORD_REF", "source": {"kind": "cipherField", "cipherId": shared_login,
                                            "field": "password"}},
    ]}


def permission_body(default, hidden):
    return {"type": 2, "groups": [], "permissions": {}, "collections": [
        {"id": default, "readOnly": True, "hidePasswords": hidden, "manage": False},
    ]}


def main_snapshot():
    return sorted(bitwarden.snapshot(account("main")), key=lambda x: x["id"])


def assert_main(state):
    if main_snapshot() != state["mainBaseline"]:
        raise ValueError("Main account changed")


def read_environment(owner, organization_id, state):
    item_id = state["itemId"]
    raw, key = owner.cipher(item_id)
    if (raw["id"] != item_id or raw["organizationId"] != organization_id or
            raw["type"] != 2 or raw["collectionIds"] != [state["defaultCollectionId"]] or
            bitwarden.decrypt(raw["name"], key).decode() != NAME):
        raise ValueError("Unexpected organization environment identity or scope")
    fields = [{"name": bitwarden.decrypt(f["name"], key).decode(),
               "value": bitwarden.decrypt(f["value"], key).decode(),
               "type": f["type"], "linkedId": f.get("linkedId")}
              for f in raw["fields"]]
    if decode_environment({"fields": fields}) != document(state["sharedLoginId"]):
        raise ValueError("Organization environment definition changed")
    return raw


def membership_path(owner, organization_id, email):
    members = owner.request(f"/api/organizations/{organization_id}/users")["data"]
    matches = [item["id"] for item in members if item["email"].lower() == email]
    if len(matches) != 1:
        raise ValueError("Expected one fixture member membership")
    return f"/api/organizations/{organization_id}/users/{matches[0]}"


def member_details(owner, path, member_id, default):
    value = owner.request(path + "?includeCollections=true&includeGroups=true")
    if (value["userId"] != member_id or value["type"] != 2 or
            value["status"] != 2 or value["groups"] or len(value["collections"]) != 1):
        raise ValueError("Unexpected fixture member identity, role, status or groups")
    collection = value["collections"][0]
    if (collection["id"] != default or collection["readOnly"] is not True or
            collection["manage"] is not False):
        raise ValueError("Unexpected fixture member collection scope")
    return value


def run(mode):
    original_org_state = session.read_json(org.state_path())
    path = state_path()
    state = session.read_json(path) if path.exists() else {}
    if (original_org_state.get("nativeMemberSignedIn") or
            original_org_state.get("nativeMemberCleanupPending") or
            state.get("nativeMemberSignedIn") or state.get("nativeMemberCleanupPending")):
        raise ValueError("Sign out the native fixture member before changing server permissions")
    if mode != "setup":
        assert_main(state)
    member_id = bitwarden.Session(account(org.MEMBER)).account_id
    owner_snapshot = org.snapshot()
    owner, organization_id = org.login()
    key = owner.org_keys[organization_id]
    member_path = membership_path(owner, organization_id, account(org.MEMBER).email)
    if mode == "setup":
        if state.get("createAttempted"):
            raise ValueError("Environment creation already attempted; inspect private state")
        defaults = [x["id"] for x in owner_snapshot["collections"] if x["name"] == "Default"]
        if len(defaults) != 1:
            raise ValueError("Expected one Default collection")
        default = defaults[0]
        details = member_details(owner, member_path, member_id, default)
        member = org.snapshot(member=True)
        shared = owner_snapshot["ciphers"]
        if (len(shared) != 1 or shared[0]["organizationId"] != organization_id or
                len(owner_snapshot["collections"]) != 2 or len(member["ciphers"]) != 1 or
                details["collections"][0]["hidePasswords"] is not True):
            raise ValueError("Organization fixture is not at its original baseline")
        state = {"name": NAME, "createAttempted": True, "itemCleanupPending": True,
                 "permissionsCleanupPending": False, "nativeMemberSignedIn": False,
                 "nativeMemberCleanupPending": False, "defaultCollectionId": default,
                 "sharedLoginId": shared[0]["id"], "ownerBaseline": owner_snapshot,
                 "memberBaseline": member, "baselineMemberDetails": details,
                 "mainBaseline": main_snapshot(), "passwordChecksMustStayOff": True}
        save(state)
        text = json.dumps(document(state["sharedLoginId"]), separators=(",", ":"))
        fields = [{"name": bitwarden.encrypt(PREFIX + suffix, key),
                   "value": bitwarden.encrypt(value, key), "type": 1, "linkedId": None}
                  for suffix, value in (("part.1", text),
                                        ("sha256", hashlib.sha256(text.encode()).hexdigest()))]
        result = owner.request("/api/ciphers/create", {"collectionIds": [default], "cipher": {
            "encryptedFor": owner.account_id, "key": None, "type": 2,
            "organizationId": organization_id, "folderId": None,
            "name": bitwarden.encrypt(NAME, key), "notes": None, "favorite": False,
            "reprompt": 0, "secureNote": {"type": 0}, "fields": fields,
            "login": None, "card": None, "identity": None, "sshKey": None,
            "passwordHistory": [], "lastKnownRevisionDate": None,
        }})
        state["itemId"] = result["id"]
        state["itemCreated"] = True
        save(state)
        read_environment(owner, organization_id, state)
        state["permissionsCleanupPending"] = True
        state["permissionChangeAttempted"] = True
        save(state)
        owner.request(member_path, permission_body(default, False), method="PUT")
        state["memberHidePasswords"] = False
        state["phase"] = "readable-readonly"
    else:
        if not state.get("itemCreated") or not state.get("itemCleanupPending"):
            raise ValueError("No leased organization environment is active")
        default = state["defaultCollectionId"]
        member_details(owner, member_path, member_id, default)
        read_environment(owner, organization_id, state)
        state["permissionsCleanupPending"] = True
        save(state)
        owner.request(member_path, permission_body(default, True), method="PUT")
        state["memberHidePasswords"] = True
        state["permissionsCleanupPending"] = False
        state["phase"] = "hidden-readonly"
        save(state)
        if mode == "cleanup":
            owner.request("/api/ciphers/" + state["itemId"], method="DELETE")
            if (org.snapshot() != state["ownerBaseline"] or
                    org.snapshot(member=True) != state["memberBaseline"]):
                raise ValueError("Organization cleanup did not restore exact baselines")
            state["itemCleanupPending"] = False
            state["phase"] = "clean"
    current_details = member_details(owner, member_path, member_id, default)
    if (current_details["collections"] !=
            permission_body(default, state["memberHidePasswords"])["collections"]):
        raise ValueError("Member permission postcondition failed")
    after = org.snapshot()
    if ([x for x in after["ciphers"] if x["id"] != state["itemId"]] !=
            state["ownerBaseline"]["ciphers"] or
            after["collections"] != state["ownerBaseline"]["collections"]):
        raise ValueError("Original shared data or collection metadata changed")
    assert_main(state)
    save(state)
    print(json.dumps({"mode": mode, "itemId": state["itemId"], "phase": state["phase"],
                      "itemCleanupPending": state["itemCleanupPending"],
                      "permissionsCleanupPending": state["permissionsCleanupPending"],
                      "otherFixturesUnchanged": True}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("setup", "hide", "cleanup"))
    run(parser.parse_args().mode)
