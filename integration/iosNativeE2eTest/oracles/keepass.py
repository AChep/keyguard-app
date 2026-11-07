#!/usr/bin/env python3
"""Print a synthetic KDBX database's groups and entries as JSON with PyKeePass.

Independent of Keyguard. The output includes decrypted values and attachment
sizes and SHA-256 hashes: use synthetic databases only. Runs with the harness
virtualenv that `e2e bootstrap` creates.
"""

import argparse
import hashlib
import json
from pathlib import Path

from pykeepass import PyKeePass


def snapshot(database, password, keyfile=None):
    vault = PyKeePass(str(database), password=password, keyfile=str(keyfile) if keyfile else None)
    return {
        "version": vault.version,
        "name": vault.database_name,
        "groups": [{"uuid": str(group.uuid), "name": group.name, "path": group.path}
                   for group in vault.groups],
        "entries": [{
            "uuid": str(entry.uuid), "path": entry.path, "title": entry.title,
            "username": entry.username, "password": entry.password, "url": entry.url,
            "notes": entry.notes, "otp": entry.otp, "tags": entry.tags,
            "customProperties": entry.custom_properties,
            "attachments": [{"name": item.filename, "size": len(item.data),
                             "sha256": hashlib.sha256(item.data).hexdigest()}
                            for item in entry.attachments],
        } for entry in vault.entries],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("--password", help="Synthetic database password; omit for keyfile-only databases")
    parser.add_argument("--keyfile", type=Path)
    args = parser.parse_args()
    print(json.dumps(snapshot(args.database, args.password, args.keyfile), indent=2, ensure_ascii=False))
