#!/usr/bin/env python3
"""Decrypt and validate a synthetic Keyguard backup repository with age and 7-Zip.

Reads only a dedicated fixture repository: a path containing a `Keyguard E2E`
folder (the simulator's test Files folder), or inside the session folder
(for example WebDAV or S3 storage there).
Uses the age CLI (on PATH, or build/ios-native-e2e/tools/age) for age metadata and indexes
(and checks that a wrong password fails), 7-Zip for AES-256 ZIP archives, and
never Keyguard code. For every indexed snapshot it checks the manifest, item
and attachment counts and the authenticated attachment bytes against the
recorded sizes. Writes index.json, SNAPSHOT-vault.json, SNAPSHOT-manifest.json
and proof.json to --output; exits nonzero when attachment size metadata differs
from the plaintext (the mismatches stay in proof.json).
"""

import argparse
import hashlib
import json
import os
import pty
import select
import signal
import shutil
import subprocess
import tempfile
import time
import zipfile
from pathlib import Path

from lib import session

AGE = shutil.which("age") or session.BUILD / "tools" / "age"


def decrypt_age(source, destination, password):
    """Supply the synthetic password through age's controlling terminal."""
    pid, descriptor = pty.fork()
    if pid == 0:
        os.execv(str(AGE), [str(AGE), "--decrypt", "--output", str(destination), str(source)])
    output = b""
    supplied = False
    deadline = time.monotonic() + 45
    try:
        while time.monotonic() < deadline:
            if select.select([descriptor], [], [], .2)[0]:
                try:
                    chunk = os.read(descriptor, 4096)
                except OSError:
                    chunk = b""
                output += chunk
                if b"Enter passphrase:" in output and not supplied:
                    os.write(descriptor, password.encode() + b"\n")
                    supplied = True
            finished, status = os.waitpid(pid, os.WNOHANG)
            if finished:
                return os.waitstatus_to_exitcode(status)
        os.kill(pid, signal.SIGTERM)
        os.waitpid(pid, 0)
        raise TimeoutError("Independent age decryption timed out")
    finally:
        os.close(descriptor)


def archive_entry(path, entry, password=None):
    if password:
        subprocess.run(["7zz", "t", "-p" + password, str(path)], capture_output=True, check=True)
        return subprocess.run(["7zz", "e", "-so", "-p" + password, str(path), entry],
                              capture_output=True, check=True).stdout
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
        return archive.read(entry)


def inspect(repository, password, output):
    repository = repository.resolve()
    assert "Keyguard E2E" in repository.parts or any(
        repository.is_relative_to(root.resolve())
        for root in (session.directory(),)
    ), "Use only a dedicated fixture directory"
    output.mkdir(parents=True, exist_ok=True)
    encrypted = (repository / "repo.zip.age").exists()
    proof = dict(repository=str(repository), encryptedMetadata=encrypted)
    with tempfile.TemporaryDirectory(prefix="keyguard-backup-oracle-") as temporary:
        temporary = Path(temporary)
        if encrypted:
            assert password
            wrong = decrypt_age(repository / "repo.zip.age", temporary / "wrong.zip", password + "-wrong")
            assert wrong != 0, "Wrong password decrypted metadata"
            assert decrypt_age(repository / "repo.zip.age", temporary / "repo.zip", password) == 0
            metadata = json.loads(archive_entry(temporary / "repo.zip", "repo.json"))
            identity = temporary / "index.key"
            identity.write_text(metadata["keys"]["index"] + "\n")
            identity.chmod(0o600)
            proof["wrongPasswordRejected"] = True
        else:
            metadata = json.loads(archive_entry(repository / "repo.zip", "repo.json", password))
            if metadata.get("crypto", {}).get("archive") == "zip-aes-256":
                assert password
                wrong = subprocess.run(["7zz", "t", "-p" + password + "-wrong",
                                        str(repository / "repo.zip")], capture_output=True)
                assert wrong.returncode != 0, "Wrong password opened legacy metadata"
                proof["wrongPasswordRejected"] = True
                proof["encryptedMetadata"] = True
        indexes = sorted((repository / "indexes").glob("*.zip*"))
        assert indexes, "No backup index"
        newest_generation = max(path.name[:20] for path in indexes)
        latest = [path for path in indexes if path.name[:20] == newest_generation]
        assert len(latest) == 1, "This fixture expects one writer"
        index_path = latest[0]
        if index_path.suffix == ".age":
            decrypted_index = temporary / "index.zip"
            subprocess.run([str(AGE), "--decrypt", "-i", str(identity), "-o", str(decrypted_index),
                            str(index_path)], capture_output=True, check=True)
            index = json.loads(archive_entry(decrypted_index, "index.json"))
        else:
            index = json.loads(archive_entry(index_path, "index.json", password))
        (output / "index.json").write_text(json.dumps(index, indent=2))
        snapshots = []
        size_failures = []
        for identifier, record in index.get("snapshots", {}).items():
            encryption = record.get("encryption", {})
            object_password = encryption.get("keyBase64")
            path = repository / record["path"]
            manifest = json.loads(archive_entry(path, "manifest.json", object_password))
            payload = archive_entry(path, "vault.json", object_password)
            vault = json.loads(payload)
            assert manifest["snapshotId"] == identifier
            assert len(payload) == manifest["vault"]["size"]
            assert len(vault["items"]) == manifest["stats"]["cipherCount"]
            (output / (identifier + "-vault.json")).write_bytes(payload)
            (output / (identifier + "-manifest.json")).write_text(json.dumps(manifest, indent=2))
            attachments = []
            for attachment in manifest["attachments"]:
                blob = index["blobs"][attachment["blobId"]]
                assert blob["path"] == attachment["blobPath"]
                data = archive_entry(repository / blob["path"], "attachment.bin",
                                     blob.get("encryption", {}).get("keyBase64"))
                sizes_match = len(data) == attachment["plainSize"] == blob["plainSize"]
                if not sizes_match:
                    size_failures.append(dict(snapshot=identifier, fileName=attachment["fileName"],
                                              actualBytes=len(data), manifestBytes=attachment["plainSize"],
                                              indexBytes=blob["plainSize"]))
                attachments.append(dict(fileName=attachment["fileName"], bytes=len(data),
                                        reportedBytes=attachment["plainSize"], sizeMetadataMatches=sizes_match,
                                        remoteCipherId=attachment.get("remoteCipherId"),
                                        accountId=attachment["accountId"],
                                        sha256=hashlib.sha256(data).hexdigest()))
            assert len(attachments) == manifest["stats"]["attachmentCount"]
            snapshots.append(dict(id=identifier, items=len(vault["items"]), attachments=attachments,
                                  stats=manifest["stats"], options=manifest["options"],
                                  objectEncrypted=bool(object_password)))
        assert snapshots, "No indexed snapshots"
        proof.update(repoId=metadata["repoId"], formatVersion=metadata.get("formatVersion", 1),
                     crypto=metadata["crypto"], generation=index["generation"],
                     snapshotCount=len(snapshots), snapshots=snapshots,
                     allIndexedArchivesValidated=True, allEncryptedArchivesAuthenticated=True,
                     sizeMetadataFailures=size_failures,
                     allAttachmentSizesMatch=not size_failures)
        (output / "proof.json").write_text(json.dumps(proof, indent=2))
        print(json.dumps({key: proof[key] for key in ["repoId", "formatVersion", "generation", "snapshotCount"]}))
        assert not size_failures, "Backup attachment size metadata differs from authenticated plaintext; see proof.json"
    return proof


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path, help="Backup repository folder")
    parser.add_argument("--password", help="Synthetic repository password")
    parser.add_argument("--output", type=Path, required=True, help="Evidence folder")
    arguments = parser.parse_args()
    inspect(arguments.repository, arguments.password, arguments.output)
