#!/usr/bin/env python3
"""Compare a decrypted native vault.json export with the main account's server.

Checks every exported item against --baseline (the main account's decrypted
snapshot taken right before the export, e.g. saved `e2e vault` output): remote
IDs, common fields, login credentials and TOTP, URIs, card, identity and SSH
fields, custom fields (including GPG and environment transport fields) and
attachment metadata. A fresh independent read must still equal the baseline;
it also supplies folders and password history. The single exported attachment
file (in `attachments/` next to the JSON) must match --attachment-proof:
{"file": "<name>", "bytes": <size>, "sha256": "<hex>"}.
Absent notes and card brands compare equal to empty strings.
"""

import argparse
import hashlib
from datetime import datetime
from pathlib import Path

from lib import bitwarden, session
from lib.config import account
from oracles.vault import load


def fields(values):
    return [{key: value.get(key) for key in ("name", "value", "type", "linkedId")}
            for value in values or []]


def nonempty(values):
    return {key: value for key, value in values.items() if value not in (None, "")}


def history(values):
    return sorted((value["password"], datetime.fromisoformat(
        value["lastUsedDate"].replace("Z", "+00:00")
    )) for value in values or [])


def verify(path, output, baseline_path, attachment_proof_path):
    exported = session.read_json(path)
    baseline = load(baseline_path)
    expected = {item["id"]: item for item in baseline}
    actual = {item["id"]: item for item in exported["items"]}
    assert len(actual) == len(exported["items"]) == len(expected) == len(baseline)
    assert actual.keys() == expected.keys()
    for identifier, item in expected.items():
        result = actual[identifier]
        for key in ("type", "name", "favorite", "folderId", "reprompt", "deletedDate"):
            assert result.get(key) == item.get(key), (item["name"], key)
        # The export serializes an absent note as an empty string.
        assert (result.get("notes") or "") == (item.get("notes") or "")
        login = result.get("login", {})
        for key in ("username", "password", "totp"):
            assert login.get(key) == item.get(key), (item["name"], key)
        assert [uri.get("uri") for uri in login.get("uris") or []] == item["uris"]
        for subtype in ("card", "identity", "sshKey"):
            assert nonempty(result.get(subtype, {})) == nonempty(item[subtype]), (item["name"], subtype)
        assert fields(result.get("fields")) == fields(item["fields"]), (item["name"], "fields")
        attachment_fields = lambda values: sorted(
            (value["id"], value["fileName"], str(value["size"])) for value in values or []
        )
        assert attachment_fields(result.get("attachments")) == attachment_fields(item["attachments"])

    oracle = bitwarden.Session(account("main"))
    assert oracle.ciphers() == baseline, "Live server changed since the baseline"
    expected_folders = sorted((folder["id"], bitwarden.decrypt(folder["name"], oracle.user_key).decode())
                              for folder in oracle.sync["folders"])
    assert sorted((folder["id"], folder["name"]) for folder in exported["folders"]) == expected_folders
    for item in oracle.sync["ciphers"]:
        cipher_key = oracle.cipher_key(item)
        expected_history = [dict(password=bitwarden.decrypt(entry["password"], cipher_key).decode(),
                                 lastUsedDate=entry["lastUsedDate"])
                            for entry in item.get("passwordHistory") or []]
        assert history(actual[item["id"]].get("passwordHistory")) == history(expected_history)

    attachment_proof = session.read_json(attachment_proof_path)
    attachment_files = [file for file in (path.parent / "attachments").rglob("*") if file.is_file()]
    assert len(attachment_files) == 1
    attachment = attachment_files[0]
    assert attachment.name == attachment_proof["file"]
    assert attachment.stat().st_size == attachment_proof["bytes"]
    assert hashlib.sha256(attachment.read_bytes()).hexdigest() == attachment_proof["sha256"]
    proof = dict(items=len(baseline), exactRemoteIds=True, commonFields=True,
                 loginCredentialsAndTotp=True, uris=True, cards=True, identities=True,
                 sshKeys=True, customFieldsIncludingGpgAndEnvironment=True, passwordHistory=True,
                 folderIdsAndNames=True, attachmentMetadata=True, exactAttachmentBytes=True,
                 originalServerStillMatchesBaseline=True,
                 normalization="Absent notes and card brand are equivalent to empty strings",
                 jsonSha256=hashlib.sha256(path.read_bytes()).hexdigest())
    session.write_json(output, proof)
    print(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("json", type=Path, help="Decrypted export vault.json")
    parser.add_argument("--baseline", type=Path, required=True,
                        help="Main account snapshot taken before the export (saved `e2e vault`)")
    parser.add_argument("--attachment-proof", type=Path,
                        help="Expected attachment file name, size and SHA-256 "
                             "(default: <session>/attachment-native-export-proof.json)")
    parser.add_argument("--output", required=True, type=Path, help="Proof JSON to write")
    args = parser.parse_args()
    verify(args.json, args.output, args.baseline,
           args.attachment_proof or session.path("attachment-native-export-proof.json"))
