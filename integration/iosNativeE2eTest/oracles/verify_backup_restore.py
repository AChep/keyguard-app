#!/usr/bin/env python3
"""Compare the backup account's server values with a restored backup snapshot.

Read-only. INSPECTION is an `e2e verify_backup_repository --output` folder; its
snapshot's *-vault.json is the source and must hold as many items as the backup
account, which the native restore filled. Compares normalized values (names,
notes, fields and tags, credentials, URIs and match modes, passkeys, password
history, card/identity/SSH fields, folders, archive and trash flags) as a
multiset, and downloads every attachment through the account's TLS proxy to
compare its decrypted bytes with proof.json. Native tags become Tag fields;
empty and null linked-field values, and absent and null optional values,
compare equal; item IDs and timestamps are recreated.

Writes <session>/backup-restore-value-differences.json and
backup-restore-roundtrip-proof.json. Exits nonzero when current values differ,
or when password history was not fully restored (a partial proof is kept).
"""

import argparse
import base64
import collections
import hashlib
import json
from pathlib import Path

from lib import bitwarden, session
from lib.config import account
from oracles.verify_export_json import fields, history, nonempty
from oracles.verify_import_corpus import credentials, download


def canonical(item, folders):
    login = item.get("login") or {}
    custom = fields(item.get("fields")) + [
        dict(name="Tag", value=tag, type=0, linkedId=None) for tag in item.get("tags") or []]
    for field in custom:
        if field["type"] == 3 and field["value"] == "":
            field["value"] = None
    return json.dumps(dict(
        name=item["name"], type=item["type"], notes=item.get("notes") or "",
        favorite=bool(item.get("favorite")), reprompt=item.get("reprompt") or 0,
        folder=folders.get(item.get("folderId")), archived=bool(item.get("archivedDate")),
        trashed=bool(item.get("deletedDate")), fields=custom,
        username=login.get("username"), password=login.get("password"), totp=login.get("totp"),
        uris=[dict(uri=value["uri"], match=value.get("match")) for value in login.get("uris") or []],
        passkeys=[nonempty(value) for value in credentials(login.get("fido2Credentials"))],
        history=history(item.get("passwordHistory")),
        card=nonempty(item.get("card") or {}), identity=nonempty(item.get("identity") or {}),
        sshKey=nonempty(item.get("sshKey") or {})), sort_keys=True, default=str)


def verify(inspection):
    target = account("backup")
    oracle = bitwarden.Session(target)
    raw, user_key = oracle.sync, oracle.user_key
    source = json.loads(next(inspection.glob("*-vault.json")).read_text())
    assert len(raw["ciphers"]) == len(source["items"]), "Restored item count differs"
    folders = {folder["id"]: bitwarden.decrypt(folder["name"], user_key).decode()
               for folder in raw["folders"]}
    source_folders = {folder["id"]: folder["name"] for folder in source["folders"]}
    expected_items = {item["id"]: canonical(item, source_folders) for item in source["items"]}
    actual_items = {}
    attachment_values = []
    for cipher in raw["ciphers"]:
        key = oracle.cipher_key(cipher)
        text = lambda value: bitwarden.decrypt(value, key).decode() if value else None
        item = {name: cipher.get(name) for name in [
            "type", "favorite", "folderId", "reprompt", "archivedDate", "deletedDate"]}
        item.update(name=text(cipher["name"]), notes=text(cipher.get("notes")))
        item["fields"] = [dict(name=text(value.get("name")), value=text(value.get("value")),
                               type=value.get("type"), linkedId=value.get("linkedId"))
                          for value in cipher.get("fields") or []]
        for subtype in ["card", "identity", "sshKey"]:
            item[subtype] = {name: text(value) for name, value in (cipher.get(subtype) or {}).items()
                             if isinstance(value, str)}
        login = cipher.get("login") or {}
        item["login"] = {name: text(login.get(name)) for name in ["username", "password", "totp"]}
        item["login"]["uris"] = [dict(uri=text(value.get("uri")), match=value.get("match"))
                                   for value in login.get("uris") or []]
        item["login"]["fido2Credentials"] = [
            {name: value if name == "creationDate" else text(value) for name, value in credential.items()}
            for credential in login.get("fido2Credentials") or []]
        item["passwordHistory"] = [dict(password=text(value["password"]), lastUsedDate=value["lastUsedDate"])
                                    for value in cipher.get("passwordHistory") or []]
        normalized = canonical(item, folders)
        actual_items[cipher["id"]] = normalized
        for attachment in cipher.get("attachments") or []:
            encrypted = download(attachment["url"], target.server)
            assert encrypted[0] == 2 and len(encrypted) > 49
            encoded = "2." + "|".join(base64.b64encode(value).decode() for value in [
                encrypted[1:17], encrypted[49:], encrypted[17:49]])
            data = bitwarden.decrypt(encoded, bitwarden.decrypt(attachment["key"], key))
            attachment_values.append((normalized, text(attachment["fileName"]),
                                      len(data), hashlib.sha256(data).hexdigest()))
    expected = collections.Counter(expected_items.values())
    actual = collections.Counter(actual_items.values())
    failures = dict(missing=list((expected - actual).elements()), added=list((actual - expected).elements()))
    session.write_json(session.path("backup-restore-value-differences.json"), failures)
    def without_history(value):
        return json.dumps({key: item for key, item in json.loads(value).items() if key != "history"},
                          sort_keys=True)
    assert collections.Counter(map(without_history, expected_items.values())) == collections.Counter(
        map(without_history, actual_items.values())), "Restored current values differ; inspect private differences"
    proof = json.loads((inspection / "proof.json").read_text())
    expected_attachments = [(without_history(expected_items[value["remoteCipherId"]]), value["fileName"],
                             value["bytes"], value["sha256"]) for value in proof["snapshots"][0]["attachments"]]
    assert collections.Counter((without_history(item), name, size, digest)
                               for item, name, size, digest in attachment_values) == collections.Counter(
                                   expected_attachments)
    lost_history = sum(len(json.loads(item)["history"]) for item in expected_items.values()) - sum(
        len(json.loads(item)["history"]) for item in actual_items.values())
    result = dict(items=len(raw["ciphers"]), folders=len(folders), attachments=len(attachment_values),
                  currentCredentialsAndCustomFieldsExact=True, passkeysExact=True,
                  passwordHistoryExact=expected == actual, lostPasswordHistoryEntries=lost_history,
                  roundtripComplete=expected == actual,
                  folderNamesExact=True, archiveAndTrashFlagsExact=True,
                  allAttachmentPlaintextBytesExact=True,
                  normalization="Native tags become Tag fields; linked-field empty values and nulls compare equally; optional nulls and absent values compare equally; item timestamps/IDs are recreated")
    session.write_json(session.path("backup-restore-roundtrip-proof.json"), result)
    print(json.dumps(result))
    assert expected == actual, "Password history was not restored; partial roundtrip proof retained"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inspection", type=Path, help="verify_backup_repository output folder")
    verify(parser.parse_args().inspection)
