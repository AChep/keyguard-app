#!/usr/bin/env python3
"""Re-encrypt a native backup repository into the legacy ZIP AES-256 format.

Copies the snapshot and blob payloads of an existing native backup repository
(for example the WebDAV folder the app backed up to) into a new folder, and
encrypts its metadata, index, snapshots and blobs with 7-Zip AES-256. This is an
independently constructed fixture, not an older-app capture. Every archive is
checked with the correct and a wrong password; the source files are never edited.
"""

import argparse
import base64
import hashlib
import json
import secrets
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path

from lib import session

PASSWORD = "Backup-Legacy-E2e-2026!"


def entries(path):
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
        assert all(name in {"repo.json", "index.json", "manifest.json", "vault.json", "attachment.bin"}
                   for name in archive.namelist())
        return {name: archive.read(name) for name in archive.namelist()}


def encrypted_zip(path, values, password):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="keyguard-legacy-backup-") as directory:
        directory = Path(directory)
        for name, value in values.items():
            (directory / name).write_bytes(value)
        subprocess.run(["7zz", "a", "-tzip", "-mem=AES256", "-p" + password,
                        str(path.resolve()), *values], cwd=directory, capture_output=True, check=True)
    subprocess.run(["7zz", "t", "-p" + password, str(path)], capture_output=True, check=True)
    assert subprocess.run(["7zz", "t", "-p" + password + "-wrong", str(path)],
                          capture_output=True).returncode != 0
    for name, value in values.items():
        result = subprocess.run(["7zz", "e", "-so", "-p" + password, str(path), name],
                                capture_output=True, check=True)
        assert result.stdout == value


def prepare(source, target):
    assert not target.exists(), "Refuse overwriting a fixture"
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    before = {str(path.relative_to(source)): digest(path) for path in source.rglob("*") if path.is_file()}
    metadata = json.loads(entries(source / "repo.zip")["repo.json"])
    latest = max((source / "indexes").glob("*.zip"), key=lambda path: path.name)
    index = json.loads(entries(latest)["index.json"])
    target.mkdir(parents=True)
    for record in [*index["snapshots"].values(), *index["blobs"].values()]:
        password = base64.b64encode(secrets.token_bytes(32)).decode()
        encrypted_zip(target / record["path"], entries(source / record["path"]), password)
        record["encryption"] = dict(method="zip_aes_256", keyBase64=password)
        if "encryptedSize" in record:
            record["encryptedSize"] = (target / record["path"]).stat().st_size
    metadata.update(formatVersion=1, repoId=str(uuid.uuid4()),
                    crypto=dict(archive="zip-aes-256", objectArchive="zip-aes-256"))
    metadata["features"] = ["encrypted-zip-metadata" if value == "plain-zip-metadata" else value
                            for value in metadata["features"]]
    encrypted_zip(target / "repo.zip", {"repo.json": json.dumps(metadata).encode()}, PASSWORD)
    encrypted_zip(target / "indexes" / latest.name, {"index.json": json.dumps(index).encode()}, PASSWORD)
    assert before == {str(path.relative_to(source)): digest(path)
                      for path in source.rglob("*") if path.is_file()}
    proof = dict(provenance="Independent 7-Zip AES re-encryption of a real native backup snapshot",
                 source=str(source), target=str(target), password=PASSWORD, sourceFilesUnchanged=True,
                 snapshotPayloadsUnchanged=True, allEncryptedArchivesRoundtripVerified=True,
                 allWrongPasswordsRejected=True,
                 targetFileHashes={str(path.relative_to(target)): digest(path)
                                   for path in target.rglob("*") if path.is_file()})
    session.write_json(session.path("backup-legacy-fixture-proof.json"), proof)
    print(json.dumps({key: proof[key] for key in ["provenance", "sourceFilesUnchanged",
                                               "snapshotPayloadsUnchanged", "allWrongPasswordsRejected"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Existing native backup repository folder")
    parser.add_argument("target", type=Path, help="New folder for the legacy-format copy")
    args = parser.parse_args()
    prepare(args.source, args.target)
