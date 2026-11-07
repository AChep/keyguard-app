#!/usr/bin/env python3
"""Check a native import against its source fixture on the import account's server.

Read-only. Logs in as the import account with lib.bitwarden and compares the
items added since --before with the expected values of CASE: the pinned
repository corpus (bitwarden-json, bitwarden-protected-pbkdf2,
bitwarden-protected-argon2id, bitwarden-csv, keepassxc-csv), a decrypted legacy
Keyguard export (keyguard-zip, read from <session>/exports/legacy-keyguard-decrypted/),
or a case of the session manifests written by the import fixtures
(browser-import-fixtures.json, manager-import-fixtures.json,
archive-manager-import-fixtures.json). Prior items must stay unchanged.

Writes <session>/import-CASE-destination-raw.json, -destination-decrypted.json,
-extended.json and -server-proof.json; feed the decrypted and extended captures
to the next import as --before and --before-extended. Failed item checks are
recorded in the proof and exit nonzero. Item timestamps are not compared:
imports create new records. For keyguard-zip, the legacy attachment is
downloaded through the account's TLS proxy, authenticated and decrypted.
"""

import argparse
import csv
import hashlib
import hmac
import json
import ssl
import subprocess
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

from lib import bitwarden, session
from lib.config import account

CORPUS = session.REPO / "common/src/desktopTest/resources/import-corpus"
ACCOUNT = "import"


def nonempty(value):
    return {key: item for key, item in (value or {}).items() if item not in (None, "")}


def fields(value):
    return [{key: item.get(key) for key in ("name", "value", "type", "linkedId")}
            for item in value or []]


def credentials(value):
    return [dict(item, creationDate=datetime.fromisoformat(item["creationDate"].replace("Z", "+00:00")))
            for item in value or []]


def expected(case):
    if case in ("bitwarden-json", "bitwarden-protected-pbkdf2", "keyguard-zip"):
        path = (session.path("exports/legacy-keyguard-decrypted/vault.json") if case == "keyguard-zip"
                else CORPUS / "bitwarden/vault.json")
        source = json.loads(path.read_text())
        if case == "keyguard-zip":
            for item in source["items"]:
                item["fields"] = [field for field in item.get("fields") or []
                                  if not field["name"].startswith("keyguard.link.")]
                item["fields"].extend(dict(name="Tag", value=tag, type=0)
                                      for tag in item.get("tags") or [])
        folders = {item["id"]: item["name"] for item in source["folders"]}
        return [dict(item, folder=folders.get(item.get("folderId")))
                for item in source["items"]], {"Work", "Work/Email"}
    if case == "bitwarden-protected-argon2id":
        return [dict(name="Argon2 account note", type=2, favorite=False, reprompt=0,
                     notes="Exported with an Argon2id account.", fields=[])], set()
    if case == "bitwarden-csv":
        with (CORPUS / "bitwarden/vault.csv").open(newline="") as source:
            rows = list(csv.DictReader(source))
        items = []
        for row in rows:
            custom = []
            for line in row["fields"].splitlines():
                name, value = line.split(": ", 1)
                custom.append(dict(name=name, value=value, type=0))
            items.append(dict(name=row["name"], type=1 if row["type"] == "login" else 2,
                              notes=row["notes"], favorite=row["favorite"] == "1",
                              reprompt=int(row["reprompt"] or 0), fields=custom,
                              archivedDate=row["archivedDate"], folder=row["folder"] or None,
                              login=dict(username=row["login_username"],
                                         password=row["login_password"], totp=row["login_totp"],
                                         uris=[dict(uri=value) for value in row["login_uri"].split(",")
                                               if value])))
        return items, {"Work", "Work/Email"}
    if case == "keepassxc-csv":
        with (CORPUS / "keepassxc/database.csv").open(newline="") as source:
            rows = list(csv.DictReader(source))
        items = [dict(name=row["Title"] or "Untitled", type=1, notes=row["Notes"], favorite=False,
                      reprompt=0, fields=[], folder=row["Group"].partition("/")[2] or None,
                      login=dict(username=row["Username"], password=row["Password"],
                                 totp=row["TOTP"], uris=[dict(uri=row["URL"])] if row["URL"] else []))
                 for row in rows]
        return items, {"Banking", "Banking/Cards", "Email", "Recycle Bin"}
    if case in ("chrome", "firefox", "safari"):
        source = session.read_json(session.path("browser-import-fixtures.json"))[case]
        return source["items"], set(source["folders"])
    manager_manifest = session.path("manager-import-fixtures.json")
    if manager_manifest.exists():
        source = json.loads(manager_manifest.read_text()).get(case)
        if source:
            return source["items"], set(source["folders"])
    archive_manifest = session.path("archive-manager-import-fixtures.json")
    if archive_manifest.exists():
        source = json.loads(archive_manifest.read_text()).get(case)
        if source:
            return source["items"], set(source["folders"])
    raise ValueError(case)


def tls_context():
    """Trusts only the session's test CA."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.load_verify_locations(session.tls("ca.pem"))
    return context


def check_download_url(url, server):
    """Attachment downloads must go to the account's own HTTPS proxy on loopback."""
    parsed, public = urllib.parse.urlparse(url), urllib.parse.urlparse(server.domain)
    assert parsed.scheme == "https" and parsed.hostname == "127.0.0.1", url
    assert (parsed.scheme, parsed.hostname, parsed.port) == (
        public.scheme, public.hostname, public.port), url


def download(url, server):
    check_download_url(url, server)
    with urllib.request.urlopen(url, context=tls_context(), timeout=15) as response:
        return response.read()


def capture():
    oracle = bitwarden.Session(account(ACCOUNT))
    raw, user_key = oracle.sync, oracle.user_key
    items = oracle.ciphers()
    folders = {folder["id"]: bitwarden.decrypt(folder["name"], user_key).decode()
               for folder in raw["folders"]}
    extras = {}
    for cipher in raw["ciphers"]:
        key = oracle.cipher_key(cipher)
        text = lambda value: bitwarden.decrypt(value, key).decode() if value else None
        login = cipher.get("login") or {}
        extras[cipher["id"]] = dict(
            archived=bool(cipher.get("archivedDate")),
            uris=[dict(uri=text(uri.get("uri")), match=uri.get("match"))
                  for uri in login.get("uris") or []],
            fido2Credentials=[{name: value if name == "creationDate" else text(value)
                               for name, value in credential.items()}
                              for credential in login.get("fido2Credentials") or []],
            passwordHistory=[dict(password=text(entry["password"]),
                                  lastUsedDate=entry["lastUsedDate"])
                             for entry in cipher.get("passwordHistory") or []],
        )
    return raw, items, folders, extras


def save_capture(prefix, value):
    """Write a capture as PREFIX-destination-raw.json, -destination-decrypted.json, -extended.json."""
    raw, items, folders, extras = value
    session.write_json(Path(str(prefix) + "-destination-raw.json"), raw)
    session.write_json(Path(str(prefix) + "-destination-decrypted.json"), items)
    session.write_json(Path(str(prefix) + "-extended.json"), dict(folders=folders, extras=extras))


def verify_legacy_attachment(added_ids):
    oracle = bitwarden.Session(account(ACCOUNT))
    raw = oracle.sync
    attachments = [(cipher, attachment) for cipher in raw["ciphers"] if cipher["id"] in added_ids
                   for attachment in cipher.get("attachments") or []]
    assert len(attachments) == 1
    cipher, attachment = attachments[0]
    encrypted = download(attachment["url"], account(ACCOUNT).server)
    key = bitwarden.decrypt(attachment["key"], oracle.cipher_key(cipher))
    assert len(encrypted) > 49 and encrypted[0] == 2
    iv, mac, content = encrypted[1:17], encrypted[17:49], encrypted[49:]
    assert hmac.compare_digest(mac, hmac.digest(key[32:], iv + content, "sha256"))
    plaintext = subprocess.run(["openssl", "enc", "-d", "-aes-256-cbc", "-K", key[:32].hex(),
                                "-iv", iv.hex()], input=content, capture_output=True, check=True).stdout
    expected_bytes = session.path(
        "exports/legacy-keyguard-decrypted/attachments/attachment-manual/manual.txt").read_bytes()
    assert plaintext == expected_bytes
    return dict(bytes=len(plaintext), sha256=hashlib.sha256(plaintext).hexdigest(),
                exactSourceBytes=True, authenticatedCiphertext=True)


def verify(case, before, before_extended=None, after_prefix=None):
    if after_prefix:
        raw = json.loads(Path(str(after_prefix) + "-destination-raw.json").read_text())
        items = json.loads(Path(str(after_prefix) + "-destination-decrypted.json").read_text())
        extended_after = json.loads(Path(str(after_prefix) + "-extended.json").read_text())
        folders, extras = extended_after["folders"], extended_after["extras"]
        assert raw["profile"]["email"].lower() == account(ACCOUNT).email, "Capture of another account"
    else:
        raw, items, folders, extras = capture()
    previous = json.loads(before.read_text())
    old = {item["id"]: item for item in previous}
    actual = {item["id"]: item for item in items}
    assert all(actual.get(identifier) == item for identifier, item in old.items()), "Prior items changed"
    if before_extended:
        extended = json.loads(before_extended.read_text())
        previous_extras = extended["extras"]
        assert all(extras.get(identifier) == item for identifier, item in previous_extras.items())
        assert all(folders.get(identifier) == name for identifier, name in extended["folders"].items())
    added = [item for item in items if item["id"] not in old]
    wanted, folder_names = expected(case)
    if before_extended:
        new_folders = {identifier: name for identifier, name in folders.items()
                       if identifier not in extended["folders"]}
        assert len(new_folders) == len(folder_names) and set(new_folders.values()) == folder_names
    assert len(added) == len(wanted), (len(added), len(wanted))
    failures = []
    for source in wanted:
        try:
            candidates = [item for item in added if (item["name"] or "") == source["name"]]
            assert len(candidates) == 1, (source["name"], len(candidates))
            item = candidates[0]
            for field in ("type", "favorite", "reprompt"):
                assert item[field] == source[field], (source["name"], field)
            assert (item["notes"] or "") == (source.get("notes") or ""), (source["name"], "notes")
            login = source.get("login") or {}
            for field in ("username", "password", "totp"):
                assert (item[field] or "") == (login.get(field) or ""), (source["name"], field)
            assert item["uris"] == [uri["uri"] for uri in login.get("uris") or []], (source["name"], "uris")
            for subtype in ("card", "identity", "sshKey"):
                assert nonempty(item[subtype]) == nonempty(source.get(subtype)), (source["name"], subtype)
            assert fields(item["fields"]) == fields(source.get("fields")), (source["name"], "fields")
            assert folders.get(item["folderId"]) == source.get("folder"), (source["name"], "folder")
            extra = extras[item["id"]]
            assert extra["archived"] == bool(source.get("archivedDate")), (source["name"], "archived")
            assert bool(item["deletedDate"]) == bool(source.get("deletedDate")), (source["name"], "trash")
            assert extra["passwordHistory"] == [], "Skipped history must not silently become current data"
            assert credentials(extra["fido2Credentials"]) == credentials(login.get("fido2Credentials")), (source["name"], "passkeys")
            if case != "bitwarden-csv":
                assert extra["uris"] == [dict(uri=uri["uri"], match=uri.get("match"))
                                         for uri in login.get("uris") or []], (source["name"], "uri match")
            assert sorted(attachment["fileName"] for attachment in item["attachments"]) == sorted(
                attachment["fileName"] for attachment in source.get("attachments") or [])
        except AssertionError as error:
            failures.append(dict(item=source["name"], assertion=str(error)))
    used_folders = {folders[item["folderId"]] for item in added if item["folderId"]}
    assert used_folders <= folder_names
    assert folder_names <= set(folders.values())
    prefix = session.path("import-" + case)
    save_capture(prefix, (raw, items, folders, extras))
    proof = dict(case=case, accountId=raw["profile"]["id"], beforeItems=len(old), addedItems=len(added),
                 totalItems=len(items), allPriorItemsUnchanged=True, credentialsAndStructuredFields=not failures,
                 allAddedItemChecksPassed=not failures, failures=failures, totalFolders=len(folders),
                 verifiedAddedItems=len(added) - len(failures), attachmentCount=sum(len(item["attachments"]) for item in items),
                 timestampPreservation="Not asserted: imports create new records")
    if case == "keyguard-zip":
        proof["attachment"] = verify_legacy_attachment({item["id"] for item in added})
    session.write_json(Path(str(prefix) + "-server-proof.json"), proof)
    print(json.dumps(proof))
    assert not failures, failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case")
    parser.add_argument("--before", required=True, type=Path,
                        help="Decrypted items before the import, e.g. the previous "
                             "import-*-destination-decrypted.json or saved `e2e vault --account import`")
    parser.add_argument("--before-extended", type=Path,
                        help="Folders and extra fields before the import (a previous *-extended.json)")
    parser.add_argument("--after-prefix", type=Path,
                        help="Use a saved capture (PREFIX-destination-raw.json, "
                             "-destination-decrypted.json, -extended.json) instead of a live read")
    arguments = parser.parse_args()
    verify(arguments.case, arguments.before, arguments.before_extended, arguments.after_prefix)
