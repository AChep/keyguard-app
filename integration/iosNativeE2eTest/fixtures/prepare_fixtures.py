#!/usr/bin/env python3
"""Place synthetic documents in the simulator's On My iPhone > Keyguard E2E folder.

Writes the documents to the session's `fixtures/` folder, then copies them into
the dedicated simulator's local Files provider. It never accesses the Keyguard
app's containers.
"""

import argparse
import json
import shutil
import subprocess
import zipfile

from lib import session
from lib.simulator import device_argument, files_directory

def prepare_keepass(fixture_root):
    source = session.REPO / "util/kdbx/src/jvmCommonTest/resources"
    # Repository interoperability fixtures use the synthetic passphrase "1".
    for name in ("ver3_aes.kdbx", "ver4_argon2.kdbx", "ver4_with_binaries.kdbx"):
        shutil.copyfile(source / name, fixture_root / ("e2e-" + name))
    (fixture_root / "e2e-invalid.kdbx").write_bytes(b"Not a KeePass database.\n")


def prepare(device):
    fixture_root = session.directory() / "fixtures"
    fixture_root.mkdir(mode=0o700, exist_ok=True)
    prepare_keepass(fixture_root)
    key = fixture_root / "e2e-ssh-import"
    if not key.exists():
        subprocess.run([
            "ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-N", "",
            "-C", "ios-e2e-fixture", "-f", str(key),
        ], check=True)
    key.chmod(0o600)
    (fixture_root / "e2e-invalid-key.txt").write_text("This is not a private key.\n")
    (fixture_root / "e2e-attachment.txt").write_text("Keyguard iOS native E2E\nUnicode: українська 🔐\n")
    original = (fixture_root / "e2e-attachment.txt").read_bytes()
    (fixture_root / "e2e-attachment-tampered.txt").write_bytes(bytes([original[0] ^ 1]) + original[1:])
    (fixture_root / "e2e-json.json").write_text(json.dumps({
        "name": "iOS E2E", "count": 2, "items": ["first", "second"], "enabled": True,
    }, indent=2))
    (fixture_root / "e2e-wordlist.txt").write_text("alpha\nbravo\ncharlie\ndelta\necho\nfoxtrot\n")
    (fixture_root / "e2e-appearance.html").write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width">'
        '<title>Keyguard appearance E2E</title><main>'
        '<h1>Keyguard local browser fixture</h1>'
        '<p id="proof">Appearance link opened successfully.</p></main></html>\n'
    )
    (fixture_root / "e2e-environment.env").write_text(
        '# Native iOS E2E\nAPI_URL=https://api.keyguard.test\nGREETING="hello world"\nEMPTY=\n'
    )
    (fixture_root / "e2e-invalid.env").write_text('INVALID="unterminated\n')
    icon = session.REPO / "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-iphone-60x60@2x.png"
    shutil.copyfile(icon, fixture_root / "e2e-image.png")
    with zipfile.ZipFile(fixture_root / "e2e-archive.zip", "w") as archive:
        for name in ("e2e-attachment.txt", "e2e-json.json", "e2e-image.png"):
            archive.write(fixture_root / name, "nested/" + name)
    target = files_directory(device, create=True)
    for source in fixture_root.iterdir():
        if source.is_file():
            shutil.copyfile(source, target / source.name)
    fingerprint = subprocess.run([
        "ssh-keygen", "-lf", str(key) + ".pub",
    ], check=True, capture_output=True, text=True).stdout.strip()
    print(json.dumps({"device": device, "files": str(target), "sshFingerprint": fingerprint}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    prepare(parser.parse_args().device)
