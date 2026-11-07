#!/usr/bin/env python3
"""Add five small ZIP attachment-preview fixtures to test Files.

Mixed AES/plain entries, ZipCrypto, a damaged stored entry, an invalid ZIP
directory and a valid LZMA entry. 7-Zip and Python's ZIP reader check encryption,
extracted bytes and the deliberate CRC failure before copying. The lease
`archive-preview-fixture-state.json` records the previous Files hashes and new
filenames; existing Files documents are never overwritten.
"""

import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import time
import zipfile

from lib.simulator import device_argument, files_directory, files_map
from lib import session

PASSWORD = "Archive-E2e-2026!"


def prepare(device):
    os.umask(0o077)
    state_path = session.path("archive-preview-fixture-state.json")
    assert not state_path.exists(), "Recover the existing fixture lease first"
    output = session.directory() / "archive-preview-fixtures"
    assert not output.exists()
    output.mkdir(mode=0o700)
    payloads = output / "payloads"
    (payloads / "nested").mkdir(parents=True, mode=0o700)
    content = {
        "nested/secret.txt": "Archive native E2E\nUnicode: українська 🔐\n".encode(),
        "nested/data.json": json.dumps(dict(name="Archive E2E", count=3, enabled=True), indent=2).encode(),
        "clear.txt": b"Unencrypted entry inside the mixed AES archive.\n",
    }
    for name, data in content.items():
        (payloads / name).write_bytes(data)
    aes = output / "e2e-preview-aes-mixed.zip"
    crypto = output / "e2e-preview-zipcrypto.zip"
    for target, method, entries in [
        (aes, "AES256", ["nested/secret.txt", "nested/data.json"]),
        (crypto, "ZipCrypto", ["nested/secret.txt"]),
    ]:
        subprocess.run(["7zz", "a", "-tzip", "-mem=" + method, "-p" + PASSWORD, str(target.resolve()), *entries],
                       cwd=payloads, check=True, capture_output=True)
    with zipfile.ZipFile(aes, "a") as archive:
        archive.writestr("clear.txt", content["clear.txt"])
    for target in [aes, crypto]:
        subprocess.run(["7zz", "t", "-p" + PASSWORD, str(target)], check=True, capture_output=True)
        with zipfile.ZipFile(target) as archive:
            for entry in archive.infolist():
                actual = subprocess.check_output(["7zz", "x", "-so", "-p" + PASSWORD, str(target), entry.filename], stderr=subprocess.DEVNULL)
                assert actual == content[entry.filename]
    with zipfile.ZipFile(aes) as archive:
        assert {x.filename: (bool(x.flag_bits & 1), x.compress_type) for x in archive.infolist()} == {
            "nested/secret.txt": (True, 99), "nested/data.json": (True, 99), "clear.txt": (False, 0)}
    with zipfile.ZipFile(crypto) as archive:
        assert archive.read("nested/secret.txt", pwd=PASSWORD.encode()) == content["nested/secret.txt"]
    damaged = output / "e2e-preview-damaged-entry.zip"
    with zipfile.ZipFile(damaged, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("damaged.txt", b"Expected CRC failure in this disposable archive.\n")
    raw = bytearray(damaged.read_bytes())
    with zipfile.ZipFile(damaged) as archive:
        info = archive.infolist()[0]
        start = info.header_offset + 30 + int.from_bytes(raw[26:28], "little") + int.from_bytes(raw[28:30], "little")
        raw[start] ^= 1
    damaged.write_bytes(raw)
    with zipfile.ZipFile(damaged) as archive:
        assert archive.testzip() == "damaged.txt"
    invalid = output / "e2e-preview-invalid.zip"
    invalid.write_bytes(b"PK\x03\x04Not a ZIP directory.\n")
    unsupported = output / "e2e-preview-lzma.zip"
    with zipfile.ZipFile(unsupported, "w", compression=zipfile.ZIP_LZMA) as archive:
        archive.writestr("unsupported.txt", b"Small LZMA-compressed ZIP entry.\n")
    with zipfile.ZipFile(unsupported) as archive:
        assert archive.read("unsupported.txt") == b"Small LZMA-compressed ZIP entry.\n"
    target = files_directory(device)
    state = dict(startedAtEpoch=time.time(), device=device, passwordChecksMustStayOff=True,
                 filesFixtureDirectory=str(target), filesBaseline=files_map(target),
                 fileProvisionPending=True, draftActive=False, previewActive=False,
                 filePickerActive=False, itemCreatePending=False, itemCleanupPending=False,
                 exportPickerActive=False, fixtureName="iPhone E2E Archive Errors", ownedFixtureFiles=[])
    session.write_json(state_path, state)
    for source in [aes, crypto, damaged, invalid, unsupported]:
        destination = target / source.name
        assert not destination.exists(), "Never overwrite an existing fixture"
        state["ownedFixtureFiles"].append(dict(filename=source.name, size=source.stat().st_size,
                                               sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
        session.write_json(state_path, state)
        shutil.copyfile(source, destination)
        destination.chmod(0o600)
        assert destination.read_bytes() == source.read_bytes()
    after = files_map(target)
    assert all(after[name] == value for name, value in state["filesBaseline"].items())
    assert len(after) == len(state["filesBaseline"]) + 5
    state.update(fileProvisionPending=False, fixtureFilesRetainedAsEvidence=True)
    session.write_json(state_path, state)
    manifest = dict(password=PASSWORD, content={name: base64.b64encode(data).decode() for name, data in content.items()},
                    independent7zipTestAndExactExtraction=True, independentZipCryptoRead=True,
                    damagedEntryCrcFailureConfirmed=True, files=state["ownedFixtureFiles"])
    session.write_json(output / "fixture-manifest.json", manifest)
    print(json.dumps(dict(provisioned=5, originalFiles=len(state["filesBaseline"]), allOriginalFilesExact=True)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    prepare(parser.parse_args().device)
