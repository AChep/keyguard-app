#!/usr/bin/env python3
"""Compare a backup's first snapshot with server captures and local KDBX files.

Read-only; runs with the harness virtualenv (PyKeePass) and never reads the
app's container. INSPECTION is an `e2e verify_backup_repository --output`
folder. Every backed-up item must come from a --server snapshot (decrypted
items, e.g. saved `e2e vault --account KEY` output or an import capture) or from
a --kdbx database entry with the same UUID:

- server items: common fields, credentials, URIs, card/identity/SSH fields,
  custom fields (native tags count as Tag fields);
- --extended captures (`*-extended.json` of verify_import_corpus): archive flag,
  URI match modes, passkeys and password history;
- KDBX entries: title, username, password and URL; a non-Login entry must be
  the synthetic test card (4111111111111111, code 123);
- attachments: bytes and SHA-256 of every snapshot attachment. KDBX attachments
  are hashed from the database; server attachments need an --attachment-proof
  file {"file": "<name>", "bytes": <size>, "sha256": "<hex>"} per file name.

Writes INSPECTION/fixture-values-proof.json.
"""

import argparse
import hashlib
import json
from pathlib import Path

from pykeepass import PyKeePass

from lib import session
from oracles.vault import load
from oracles.verify_export_json import fields, history, nonempty
from oracles.verify_import_corpus import credentials


def verify(inspection, servers, extended, kdbx, attachment_proofs):
    vault = json.loads(next(inspection.glob("*-vault.json")).read_text())
    actual = {item["id"]: item for item in vault["items"]}
    assert len(actual) == len(vault["items"])
    expected = {item["id"]: item for path in servers for item in load(path)}
    for identifier, item in expected.items():
        result = actual[identifier]
        for key in ["type", "name", "favorite", "folderId", "reprompt", "deletedDate"]:
            assert result.get(key) == item.get(key), (item["name"], key)
        assert (result.get("notes") or "") == (item.get("notes") or "")
        login = result.get("login") or {}
        for key in ["username", "password", "totp"]:
            assert login.get(key) == item.get(key), (item["name"], key)
        assert [uri["uri"] for uri in login.get("uris") or []] == item["uris"]
        for subtype in ["card", "identity", "sshKey"]:
            assert nonempty(result.get(subtype) or {}) == nonempty(item.get(subtype) or {})
        # The Bitwarden transport stores native tags as ordinary Tag fields.
        exported_fields = fields(result.get("fields")) + [
            dict(name="Tag", value=tag, type=0, linkedId=None) for tag in result.get("tags") or []]
        assert exported_fields == fields(item.get("fields")), (item["name"], "fields/tags")
    extended_items = 0
    for path in extended:
        for identifier, item in session.read_json(path)["extras"].items():
            extended_items += 1
            result = actual[identifier]
            assert bool(result.get("archivedDate")) == item["archived"]
            assert [dict(uri=uri["uri"], match=uri.get("match"))
                    for uri in (result.get("login") or {}).get("uris") or []] == item["uris"]
            assert [nonempty(value) for value in credentials(
                (result.get("login") or {}).get("fido2Credentials"))] == [
                    nonempty(value) for value in credentials(item["fido2Credentials"])]
            assert history(result.get("passwordHistory")) == history(item["passwordHistory"])

    local_ids = set()
    expected_attachments = {}
    for spec in kdbx:
        filename, password, keyfile = (spec + [None])[:3]
        database = PyKeePass(str(filename), password=password, keyfile=keyfile)
        for entry in database.entries:
            identifier = str(entry.uuid)
            if identifier not in actual or identifier in expected:
                continue
            local_ids.add(identifier)
            result = actual[identifier]
            assert result["name"] == entry.title
            if result["type"] == 1:
                login = result["login"]
                assert login.get("username") == entry.username and login.get("password") == entry.password
                assert [uri["uri"] for uri in login.get("uris") or []] == ([entry.url] if entry.url else [])
            else:
                assert result["type"] == 3 and result["card"]["number"] == "4111111111111111"
                assert result["card"]["code"] == "123"
            for attachment in entry.attachments:
                expected_attachments[(identifier, attachment.filename)] = (
                    len(attachment.data), hashlib.sha256(attachment.data).hexdigest())
    assert local_ids | expected.keys() == actual.keys(), "Backed-up items without a source"
    proof = json.loads((inspection / "proof.json").read_text())
    attachments = proof["snapshots"][0]["attachments"]
    files = {}
    for path in attachment_proofs:
        value = session.read_json(path)
        files[value["file"]] = (value["bytes"], value["sha256"])
    for item in expected.values():
        for attachment in item.get("attachments") or []:
            expected_attachments[(item["id"], attachment["fileName"])] = files[attachment["fileName"]]
    for attachment in attachments:
        assert (attachment["bytes"], attachment["sha256"]) == expected_attachments[
            (attachment["remoteCipherId"], attachment["fileName"])]
    assert len(expected_attachments) == len(attachments)
    result = dict(items=len(actual), serverBackedItemsExact=len(expected),
                  localEntriesBasicValuesExact=len(local_ids), allRecordIdsPresent=True,
                  serverFieldsAndCredentialsExact=True,
                  extendedItems=extended_items, importArchiveAndUriMatchingExact=True,
                  importPasskeysAndHistoryExact=True, attachments=len(attachments),
                  allAttachmentBytesExact=True,
                  attachmentSizeMetadataMismatchCount=len(proof["sizeMetadataFailures"]))
    session.write_json(inspection / "fixture-values-proof.json", result)
    print(json.dumps(result))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inspection", type=Path, help="verify_backup_repository output folder")
    parser.add_argument("--server", type=Path, action="append", default=[], required=True,
                        help="Decrypted server snapshot of backed-up items; repeat per capture")
    parser.add_argument("--extended", type=Path, action="append", default=[],
                        help="verify_import_corpus *-extended.json capture; repeatable")
    parser.add_argument("--kdbx", nargs="+", action="append", default=[],
                        metavar="VALUE",
                        help="DATABASE PASSWORD [KEYFILE]: a local KDBX with its synthetic password "
                             "and optional keyfile; repeatable")
    parser.add_argument("--attachment-proof", type=Path, action="append", default=[],
                        help="Expected server attachment {file, bytes, sha256}; repeatable "
                             "(default: <session>/attachment-native-export-proof.json)")
    args = parser.parse_args()
    for spec in args.kdbx:
        if len(spec) not in (2, 3):
            parser.error("--kdbx takes DATABASE PASSWORD [KEYFILE]")
    verify(args.inspection, args.server, args.extended, args.kdbx,
           args.attachment_proof or [session.path("attachment-native-export-proof.json")])
