#!/usr/bin/env python3
"""Read or set up the synthetic organization of the org and org-member accounts.

Decrypts the organization key, collection names and shared ciphers with
lib.bitwarden (Python HMAC/PBKDF2 and OpenSSL AES/RSA), never Keyguard code.
Requests stay on the account's loopback server without proxies or redirects;
tokens and keys stay in memory. The default mode is read-only. Each setup mode
runs once: it records its attempt in <session>/organization-fixture-state.json
before writing and refuses to run again.
"""

import argparse
import base64
from pathlib import Path

from lib import bitwarden, session
from lib.config import account

OWNER = "org"
MEMBER = "org-member"
ORGANIZATION = account(OWNER).organization


def state_path():
    return session.path("organization-fixture-state.json")


def read_state():
    path = state_path()
    return session.read_json(path) if path.exists() else {}


def login(member=False):
    """A session of the owner or member, and the fixture organization's ID."""
    oracle = bitwarden.Session(account(MEMBER if member else OWNER))
    organizations = oracle.profile.get("organizations") or []
    if [item.get("name") for item in organizations] != [ORGANIZATION]:
        raise ValueError("Unexpected fixture organization membership")
    return oracle, organizations[0]["id"]


def snapshot(member=False):
    oracle, _ = login(member)
    items = []
    for item in oracle.sync["ciphers"]:
        key = oracle.cipher_key(item)
        text = lambda value: bitwarden.decrypt(value, key).decode() if value else None
        credentials = item.get("login") or {}
        items.append({
            "id": item["id"], "type": item["type"], "name": text(item.get("name")),
            "organizationId": item.get("organizationId"),
            "collectionIds": sorted(item.get("collectionIds") or []),
            "folderId": item.get("folderId"), "deletedDate": item.get("deletedDate"),
            "username": text(credentials.get("username")),
            "password": text(credentials.get("password")),
            "totp": text(credentials.get("totp")), "notes": text(item.get("notes")),
            "uris": [text(uri.get("uri")) for uri in credentials.get("uris") or []],
        })
    collections = [{
        "id": item["id"], "organizationId": item["organizationId"],
        "name": bitwarden.decrypt(item["name"], oracle.org_keys[item["organizationId"]]).decode(),
        "readOnly": item.get("readOnly"), "hidePasswords": item.get("hidePasswords"),
        "manage": item.get("manage"),
    } for item in oracle.sync.get("collections") or []]
    organizations = [{key: item.get(key) for key in ("id", "name", "type", "status")}
                     for item in oracle.profile["organizations"]]
    return {"accountId": oracle.account_id, "organizations": organizations,
            "collections": collections, "ciphers": items}


def add_secondary_collection():
    state = read_state()
    if state.get("secondaryCollectionCreateAttempted"):
        raise ValueError("Collection setup already attempted; inspect private state before retrying")
    baseline = snapshot()
    if len(baseline["ciphers"]) != 1 or [x["name"] for x in baseline["collections"]] != ["Default"]:
        raise ValueError("Organization fixture is not at its expected baseline")
    owner, organization_id = login()
    state["secondaryCollectionCreateAttempted"] = True
    session.write_json(state_path(), state)
    result = owner.request(f"/api/organizations/{organization_id}/collections", {
        "name": bitwarden.encrypt("Secondary", owner.org_keys[organization_id]),
        "groups": [], "users": [], "externalId": None,
    })
    state["secondaryCollectionId"] = result["id"]
    state["secondaryCollectionCreated"] = True
    session.write_json(state_path(), state)
    after = snapshot()
    if sorted(x["name"] for x in after["collections"]) != ["Default", "Secondary"]:
        raise ValueError("Second collection did not decrypt as expected")
    if after["ciphers"] != baseline["ciphers"]:
        raise ValueError("Collection setup unexpectedly changed the fixture cipher")
    return after


def prepare_restricted_member():
    state = read_state()
    if state.get("memberInviteAttempted"):
        raise ValueError("Member setup already attempted; inspect private state before retrying")
    member_email = account(MEMBER).email
    member_id = bitwarden.Session(account(MEMBER)).account_id
    baseline = snapshot()
    default = next(item for item in baseline["collections"] if item["name"] == "Default")
    if len(baseline["collections"]) != 2 or len(baseline["ciphers"]) != 1:
        raise ValueError("Unexpected organization fixture baseline")
    owner, organization_id = login()
    members_path = f"/api/organizations/{organization_id}/users"
    members = owner.request(members_path)["data"]
    if len(members) != 1 or members[0]["userId"] != owner.account_id:
        raise ValueError("Unexpected existing organization members")
    state["memberAccountId"] = member_id
    state["memberInviteAttempted"] = True
    session.write_json(state_path(), state)
    owner.request(members_path + "/invite", {
        "emails": [member_email], "type": 2, "groups": [], "accessAll": False,
        "collections": [{"id": default["id"], "readOnly": True,
                         "hidePasswords": True, "manage": False}],
    })
    members = owner.request(members_path)["data"]
    member = next(item for item in members if item["email"].lower() == member_email)
    if member["userId"] != member_id or member["status"] != 1:
        raise ValueError("Registered local invite was not accepted as expected")
    state["memberMembershipId"] = member["id"]
    session.write_json(state_path(), state)
    public_key = base64.b64decode(owner.request(f"/api/users/{member_id}/public-key")["publicKey"])
    owner.request(members_path + f"/{member['id']}/confirm", {
        "key": bitwarden.rsa_wrap(owner.org_keys[organization_id], public_key),
    })
    members = owner.request(members_path)["data"]
    confirmed = next(item for item in members if item["id"] == member["id"])
    if confirmed["status"] != 2:
        raise ValueError("Member was not confirmed")
    result = snapshot(member=True)
    if len(result["collections"]) != 1 or len(result["ciphers"]) != 1:
        raise ValueError("Restricted member can see unexpected collections/ciphers")
    collection = result["collections"][0]
    if (collection["id"] != default["id"] or collection["readOnly"] is not True
            or collection["hidePasswords"] is not True):
        raise ValueError("Member permission flags do not match")
    if result["ciphers"] != baseline["ciphers"] or snapshot() != baseline:
        raise ValueError("Member setup changed the shared fixture data")
    state["restrictedMemberPrepared"] = True
    state["memberDefaultCollectionReadOnly"] = True
    state["memberDefaultCollectionHidePasswords"] = True
    state["memberSecondaryCollectionVisible"] = False
    session.write_json(state_path(), state)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--add-secondary-collection", action="store_true",
                        help="Once: add the Secondary collection to the one-collection baseline")
    parser.add_argument("--prepare-restricted-member", action="store_true",
                        help="Once: invite and confirm org-member to Default, read-only with "
                             "hidden passwords")
    parser.add_argument("--member", action="store_true", help="Read as org-member")
    parser.add_argument("--output", type=Path, required=True, help="Snapshot JSON to write")
    args = parser.parse_args()
    if sum((args.add_secondary_collection, args.prepare_restricted_member, args.member)) > 1:
        parser.error("Choose only one fixture setup/read mode")
    if args.prepare_restricted_member:
        value = prepare_restricted_member()
    elif args.add_secondary_collection:
        value = add_secondary_collection()
    else:
        value = snapshot(member=args.member)
    session.write_json(args.output, value)
    print(f"Verified local organization: {len(value['collections'])} collections, "
          f"{len(value['ciphers'])} ciphers")


if __name__ == "__main__":
    main()
