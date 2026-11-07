#!/usr/bin/env python3
"""Add a KDBX with XML keyfiles, nested folders and a recycle bin to test Files.

Run `e2e prepare_fixtures` first: its attachments go into the database.
Existing databases are never overwritten, so reruns cannot erase journey data.
"""

import argparse
import base64
import hashlib
import json
import shutil

from pykeepass import PyKeePass, create_database

from lib.simulator import device_argument, files_directory
from lib import session


def prepare(device):
    output = session.directory() / "keepass-variants"
    output.mkdir(mode=0o700, exist_ok=True)
    database = output / "e2e-hierarchy-keyfile.kdbx"
    if database.exists():
        raise FileExistsError(f"Preserving existing fixture: {database}")

    key = hashlib.sha256(b"Keyguard iOS E2E XML keyfile fixture").digest()
    checksum = hashlib.sha256(key).hexdigest()[:8].upper()
    xml1 = output / "e2e-keyfile-v1.keyx"
    xml2 = output / "e2e-keyfile-v2.keyx"
    invalid = output / "e2e-keyfile-bad-hash.keyx"
    hexadecimal = output / "e2e-keyfile-hex.key"
    xml1.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<KeyFile><Meta><Version>1.00</Version></Meta><Key><Data>'
        + base64.b64encode(key).decode()
        + '</Data></Key></KeyFile>\n'
    )
    xml2.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<KeyFile><Meta><Version>2.0</Version></Meta><Key>'
        f'<Data Hash="{checksum}">\n{key.hex().upper()}\n</Data>'
        '</Key></KeyFile>\n'
    )
    invalid.write_text(xml2.read_text().replace(checksum, "00000000"))
    hexadecimal.write_text(key.hex())

    password = "Hierarchy-Keyfile-E2e-2026!"
    vault = create_database(str(database), password=password, keyfile=str(xml2))
    vault.database_name = "iOS E2E Hierarchy"
    vault.tree.find("Meta/RecycleBinEnabled").text = "True"
    first = vault.add_group(vault.root_group, "Parent A")
    child = vault.add_group(first, "Shared child")
    leaf = vault.add_group(child, "Leaf 🔐")
    second = vault.add_group(vault.root_group, "Parent B")
    sibling = vault.add_group(second, "Shared child")
    vault.add_group(vault.root_group, "Move target")
    entry = vault.add_entry(
        leaf, "iOS E2E Nested Login", "nested-user", "Nested-E2e-2026!",
        url="https://nested.keyguard.test/login", notes="Nested folder fixture\nУкраїнська 🔐",
    )
    entry.set_custom_property("Protected fixture", "nested-hidden<&>2026!", protect=True)
    fixtures = session.directory() / "fixtures"
    for name in ("e2e-attachment.txt", "e2e-json.json", "e2e-image.png"):
        binary = vault.add_binary((fixtures / name).read_bytes())
        entry.add_attachment(binary, name)
    vault.add_entry(sibling, "iOS E2E Sibling Login", "sibling-user", "Sibling-E2e-2026!")
    vault.save()
    for keyfile in (xml1, xml2, hexadecimal):
        readback = PyKeePass(str(database), password=password, keyfile=str(keyfile))
        assert len(readback.entries) == 2
    try:
        PyKeePass(str(database), password=password, keyfile=str(invalid))
    except AssertionError as error:
        assert "invalid hash" in str(error)
    else:
        raise AssertionError("Invalid XML keyfile checksum was accepted")

    target = files_directory(device, create=True)
    files = [database, xml1, xml2, invalid, hexadecimal]
    for source in files:
        destination = target / source.name
        if destination.exists():
            raise FileExistsError(f"Preserving existing simulator fixture: {destination}")
        shutil.copyfile(source, destination)
    return {
        "device": device, "database": str(target / database.name), "password": password,
        "recycleBinEnabled": True, "entries": 2, "groups": len(vault.groups),
        "files": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
        "equivalentKeyfileFormats": ["XML 1.00", "XML 2.0", "64-character hexadecimal"],
        "invalidChecksumRejectedByOracle": True,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    print(json.dumps(prepare(parser.parse_args().device), indent=2, ensure_ascii=False))
