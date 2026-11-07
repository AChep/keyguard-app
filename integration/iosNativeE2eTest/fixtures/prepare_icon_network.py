#!/usr/bin/env python3
"""Create a KDBX with one Login that points at the loopback website-icon server.

Each run needs a new folder and gets a fresh root group UUID, so the database is
never mistaken for an account added before. Pairs with `e2e icon_network_web`.
"""

import argparse
import hashlib
import json
import os
import uuid
from pathlib import Path

from pykeepass import create_database

from lib.config import PORTS

ORIGINS = [f"https://127.0.0.1:{PORTS[name]}" for name in ("icon-network", "icon-network-alternate")]


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="New folder for the database and its manifest")
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    database = args.directory / "e2e-icon-network.kdbx"
    vault = create_database(str(database), password="Icon-Network-E2E-2026!")
    # PyKeePass's blank template has a stable root UUID. Keyguard uses that UUID
    # to reject duplicate accounts, so every independent fixture needs a new one.
    vault.root_group.uuid = uuid.uuid4()
    vault.database_name = "E2E Icon Network"
    entry = vault.add_entry(
        vault.root_group, "iPhone E2E Icon Probe", "icon-e2e",
        "Icon-Only-Local-2026!", url=ORIGINS[0] + "/login",
    )
    vault.save()
    database.chmod(0o600)
    manifest = {
        "owner": "keyguard-ios-native-e2e-icons",
        "database": database.name,
        "rootUuid": str(vault.root_group.uuid),
        "entryUuid": str(entry.uuid),
        "sha256": hashlib.sha256(database.read_bytes()).hexdigest(),
        "origins": ORIGINS,
    }
    (args.directory / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
