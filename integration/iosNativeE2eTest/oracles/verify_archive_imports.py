#!/usr/bin/env python3
"""Check a native archive import, including its attachment, on the import account.

Read-only. Captures the import account to <session>/archive-manager-CASE-after-*,
runs verify_import_corpus against the --before-prefix capture, then checks the
case's attachment from <session>/archive-manager-import-fixtures.json (written by
`e2e prepare_archive_imports`): exactly one attachment among the --owned-id items,
downloaded through the account's TLS proxy, MAC-authenticated, decrypted and
compared with the manifest's name, size, SHA-256 and content. The result is
added to <session>/import-CASE-server-proof.json.

--before-prefix is a capture from before the import, as written by
verify_import_corpus (PREFIX-destination-decrypted.json and PREFIX-extended.json).
"""

import argparse
import hashlib
import hmac
import subprocess
from pathlib import Path

from lib import bitwarden, session
from lib.config import account
from oracles import verify_import_corpus as corpus


def capture():
    return corpus.capture()


def save_capture(prefix, value=None):
    value = value or capture()
    corpus.save_capture(prefix, value)
    return value


def attachment(case, owned_ids):
    manifest = session.read_json(session.path("archive-manager-import-fixtures.json"))[case]
    expected = manifest.get("attachment")
    if expected is None:
        return None
    oracle = bitwarden.Session(account(corpus.ACCOUNT))
    matches = [(cipher, value) for cipher in oracle.sync["ciphers"] if cipher["id"] in owned_ids
               for value in cipher.get("attachments") or []]
    assert len(matches) == 1
    cipher, value = matches[0]
    encrypted = corpus.download(value["url"], account(corpus.ACCOUNT).server)
    cipher_key = oracle.cipher_key(cipher)
    key = bitwarden.decrypt(value["key"], cipher_key)
    assert len(key) == 64 and len(encrypted) > 49 and encrypted[0] == 2
    iv, mac, content = encrypted[1:17], encrypted[17:49], encrypted[49:]
    assert hmac.compare_digest(mac, hmac.digest(key[32:], iv + content, "sha256"))
    plaintext = subprocess.run(["openssl", "enc", "-d", "-aes-256-cbc", "-K", key[:32].hex(),
                                "-iv", iv.hex()], input=content, capture_output=True, check=True).stdout
    assert plaintext == expected["content"].encode()
    assert len(plaintext) == expected["size"]
    assert hashlib.sha256(plaintext).hexdigest() == expected["sha256"]
    assert bitwarden.decrypt(value["fileName"], cipher_key).decode() == expected["filename"]
    return dict(authenticatedCiphertext=True, exactSourceBytes=True, bytes=len(plaintext),
                sha256=expected["sha256"])


def verify(case, before_prefix, owned_ids):
    after_prefix = session.path("archive-manager-" + case + "-after")
    save_capture(after_prefix)
    corpus.verify(case, Path(str(before_prefix) + "-destination-decrypted.json"),
                  Path(str(before_prefix) + "-extended.json"), after_prefix=after_prefix)
    proof_path = session.path("import-" + case + "-server-proof.json")
    proof = session.read_json(proof_path)
    proof["attachmentProof"] = attachment(case, owned_ids)
    session.write_json(proof_path, proof)
    return proof


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", help="Case name in archive-manager-import-fixtures.json")
    parser.add_argument("--before-prefix", type=Path, required=True,
                        help="Capture prefix from before the import")
    parser.add_argument("--owned-id", action="append", default=[], dest="owned_ids",
                        help="Server ID of an item the import created; repeat for each")
    args = parser.parse_args()
    verify(args.case, args.before_prefix, set(args.owned_ids))
