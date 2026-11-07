#!/usr/bin/env python3
"""Inspect a v6 GPG item's keys and round-trip a message offline with Sequoia.

For keys GnuPG cannot list. Decodes the named item's transport fields (as
gpg_oracle), then uses stateless `sq` to compare public and private
fingerprints, algorithms (Ed25519/Ed25519/X25519), packet versions (6),
identities and expiry, and to sign+encrypt then decrypt+verify a synthetic
message. No user home, certificate store, key store or network. Evidence,
including private armor, goes to a new --output folder (mode 600); existing
files are never overwritten.
"""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

from oracles.gpg_oracle import decode_gpg


def inspect_v6(item, output):
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    output.chmod(0o700)
    data = decode_gpg(item)
    commands = []

    def save(name, value):
        path = output / name
        with path.open("xb") as stream:
            stream.write(value if isinstance(value, bytes) else value.encode())
        path.chmod(0o600)
        return path

    base = ["sq", "--home", "none", "--cert-store", "none", "--key-store", "none"]

    def run(*args):
        args = list(map(str, args))
        result = subprocess.run(base + args, capture_output=True, timeout=30)
        commands.append({"arguments": args, "returnCode": result.returncode,
                         "stderr": result.stderr.decode()})
        save(f"command-{len(commands)}.json", json.dumps(commands[-1], indent=2))
        if result.returncode:
            raise ValueError(f"Offline Sequoia failed: {result.stderr.decode()[-2000:]}")
        return result.stdout

    parsed = {}
    for kind in ("public", "private"):
        armor = save(kind + ".asc", data[kind])
        listing = run("inspect", armor).decode()
        save(kind + "-inspect.txt", listing)
        packets = run("packet", "dump", armor).decode()
        save(kind + "-packets.txt", packets)
        fingerprints = re.findall(r"^\s*(?:Fingerprint|Subkey): ([A-F0-9]{64})$", listing, re.M)
        algorithms = re.findall(r"Public-key algo: (\S+)", listing)
        expiration = [x.strip() for x in re.findall(r"Expiration time: ([^(\n]+)", listing)]
        identities = re.findall(r"^\s*UserID: (.*)$", listing, re.M)
        versions = re.findall(
            r"(?:Public-Key|Public-Subkey|Secret-Key|Secret-Subkey) Packet[^\n]*\n\s*Version: (\d+)",
            packets,
        )
        if len(fingerprints) != 3 or fingerprints[0] != data["fingerprint"]:
            raise ValueError("Stored and independent v6 fingerprints differ")
        if algorithms != ["Ed25519", "Ed25519", "X25519"] or versions != ["6"] * 3:
            raise ValueError("Unexpected v6 algorithm or packet version")
        if len(expiration) != 3 or len(set(expiration)) != 1 or not identities:
            raise ValueError("Missing or inconsistent v6 expiration/identity metadata")
        parsed[kind] = {"fingerprints": fingerprints, "algorithms": algorithms,
                        "packetKeyVersions": [int(x) for x in versions],
                        "expiry": expiration[0], "identities": identities}
    if parsed["public"] != parsed["private"]:
        raise ValueError("v6 public/private certificate metadata differs")

    message = save("message.txt", "Keyguard synthetic v6 round trip 🔐\n")
    ciphertext = run("encrypt", "--for-file", output / "public.asc",
                     "--signer-file", output / "private.asc", message)
    encrypted = save("signed-encrypted.asc", ciphertext)
    cleartext = run("decrypt", "--recipient-file", output / "private.asc",
                    "--signer-file", output / "public.asc", encrypted)
    if cleartext != message.read_bytes():
        raise ValueError("v6 signed/encrypted round trip changed the message")
    save("decrypted.txt", cleartext)
    version = subprocess.run(["sq", "version"], capture_output=True, text=True, check=True)
    proof = {**parsed, "engine": (version.stdout + version.stderr).strip(),
             "offline": True, "noHomeOrStores": True, "publicPrivateMatch": True,
             "signEncryptDecryptVerifyRoundTrip": True,
             "payloadSha256": hashlib.sha256(cleartext).hexdigest()}
    save("oracle.json", json.dumps(proof, indent=2, ensure_ascii=False))
    return proof


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path,
                        help="Decrypted item list, or an object with an `items` list")
    parser.add_argument("--name", default="iPhone E2E GPG Advanced",
                        help="Name of the GPG item (default: %(default)s)")
    parser.add_argument("--output", type=Path, required=True, help="New evidence folder")
    args = parser.parse_args()
    source = json.loads(args.snapshot.read_text())
    items = source if isinstance(source, list) else source["items"]
    matches = [item for item in items if item["name"] == args.name]
    if len(matches) != 1:
        raise ValueError("Expected exactly one named synthetic GPG item")
    print(json.dumps(inspect_v6(matches[0], args.output), ensure_ascii=False))
