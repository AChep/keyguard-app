#!/usr/bin/env python3
"""Decode a GPG item's transport fields and inspect its keys offline with GnuPG.

Reconstructs the direct or SHA-256-checked chunked `keyguard.gpg.*` fields of
the named item in a saved vault snapshot, then checks that the public and
private keys match the stored fingerprint and each other (algorithms, sizes,
dates, identities), records packet versions and verifies every self-signature.
No Keyguard codecs, user keyring, key retrieval or network calls. GnuPG runs in
a short temporary home under /private/tmp (Unix socket path limit) whose agent is
stopped and home removed afterwards. Private armor and evidence go to --output
(mode 600); differing existing evidence is never overwritten, so use a new
folder per key revision. v6 keys need `e2e gpg_v6_oracle`.
"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


def component(item, name, expected_type):
    singles = [f for f in item["fields"] if f["name"] == name]
    prefix = name + ".v1."
    chunks = [f for f in item["fields"] if f["name"].startswith(prefix)]
    if singles and chunks or len(singles) > 1:
        raise ValueError("Ambiguous GPG transport")
    fields = singles or chunks
    if not fields or any(f["type"] != expected_type or f.get("linkedId") is not None for f in fields):
        raise ValueError("Unexpected GPG field shape")
    if singles:
        return singles[0]["value"]
    values = {f["name"][len(prefix):]: f["value"] for f in chunks}
    if len(values) != len(chunks):
        raise ValueError("Duplicate GPG chunk")
    parts = {int(k[5:]): v for k, v in values.items() if re.fullmatch(r"part\.[1-9][0-9]*", k)}
    if not parts or sorted(parts) != list(range(1, len(parts) + 1)) or len(values) != len(parts) + 1:
        raise ValueError("Missing or unknown GPG chunk")
    text = "".join(parts[i] for i in sorted(parts))
    if hashlib.sha256(text.encode()).hexdigest() != values.get("sha256"):
        raise ValueError("GPG chunk checksum mismatch")
    return text


def decode_gpg(item):
    return {
        "private": component(item, "keyguard.gpg.private_key.armored", 1),
        "public": component(item, "keyguard.gpg.public_key.armored", 0),
        "fingerprint": component(item, "keyguard.gpg.fingerprint", 0).upper(),
    }


def colon_records(text):
    keys, identities = [], []
    for line in text.splitlines():
        row = line.split(":")
        if row[0] in ("pub", "sec", "sub", "ssb"):
            keys.append({"kind": row[0], "validity": row[1], "bits": int(row[2]),
                         "algorithm": int(row[3]), "keyId": row[4], "created": int(row[5]),
                         "expires": int(row[6]) if row[6] else None, "capabilities": row[11]})
        elif row[0] == "fpr" and keys:
            keys[-1]["fingerprint"] = row[9].upper()
        elif row[0] == "uid":
            value = re.sub(rb"\\x([0-9a-fA-F]{2})", lambda m: bytes((int(m[1], 16),)),
                           row[9].encode()).decode()
            identities.append({"value": value, "validity": row[1], "created": int(row[5]) if row[5] else None,
                               "expires": int(row[6]) if row[6] else None})
    return {"keys": keys, "identities": identities}


def inspect_gpg(item, output):
    output.mkdir(parents=True, mode=0o700, exist_ok=True)
    output.chmod(0o700)
    # macOS Unix socket paths cannot contain the long build/evidence directory.
    home = Path(tempfile.mkdtemp(prefix="kg-gpg-", dir="/private/tmp"))
    home.chmod(0o700)
    data = decode_gpg(item)
    def save(name, text):
        path = output / name
        if path.exists() and path.read_text() != text:
            raise ValueError("Refusing to overwrite different GPG evidence")
        path.write_text(text)
        path.chmod(0o600)
        return path
    base = ["gpg", "--no-options", "--homedir", str(home), "--batch", "--no-tty",
            "--no-autostart", "--auto-key-locate", "clear", "--no-auto-key-retrieve"]
    def run(*args):
        result = subprocess.run(base + list(args), capture_output=True, text=True)
        if result.returncode:
            raise ValueError(f"Offline GnuPG failed: {result.stderr[-2000:]}")
        return result.stdout
    try:
        subprocess.run(["gpgconf", "--homedir", str(home), "--launch", "gpg-agent"],
                       capture_output=True, check=True)
        parsed = {}
        for kind in ("public", "private"):
            armor = save(kind + ".asc", data[kind])
            listing = run("--with-colons", "--fixed-list-mode", "--show-keys", str(armor))
            save(kind + "-listing.txt", listing)
            parsed[kind] = colon_records(listing)
            if not parsed[kind]["keys"]:
                raise ValueError("GnuPG returned no supported key records; v6 fixtures need gpg_v6_oracle")
            if parsed[kind]["keys"][0]["fingerprint"] != data["fingerprint"]:
                raise ValueError("Stored and independent GPG fingerprints differ")
        for field in ("fingerprint", "algorithm", "bits", "created", "expires"):
            if [k[field] for k in parsed["public"]["keys"]] != [k[field] for k in parsed["private"]["keys"]]:
                raise ValueError("GPG public/private key metadata differs")
        if parsed["public"]["identities"] != parsed["private"]["identities"]:
            raise ValueError("GPG public/private identities differ")
        run("--import", str(output / "public.asc"))
        packets = run("--list-packets", str(output / "public.asc"))
        save("public-packets.txt", packets)
        versions = [int(x) for x in re.findall(r"version (\d+), algo \d+,", packets)]
        if len(versions) != len(parsed["public"]["keys"]):
            raise ValueError("Unexpected GPG packet metadata")
        signatures = run("--with-colons", "--check-sigs", data["fingerprint"])
        save("signatures.txt", signatures)
        sigs = [x.split(":") for x in signatures.splitlines() if x.startswith(("sig:", "rev:"))]
        if not sigs or any(x[1] != "!" for x in sigs):
            raise ValueError("GPG certificate contains a self-signature that did not verify")
        proof = {"fingerprint": data["fingerprint"], **parsed["public"],
                 "packetKeyVersions": versions, "publicPrivateMatch": True,
                 "verifiedSignatures": len(sigs), "offline": True}
        save("oracle.json", json.dumps(proof, indent=2, ensure_ascii=False))
        return proof
    finally:
        subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"], capture_output=True)
        shutil.rmtree(home)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path,
                        help="Decrypted item or item list, e.g. saved `e2e vault` output")
    parser.add_argument("--name", default="iPhone E2E GPG Advanced",
                        help="Name of the GPG item (default: %(default)s)")
    parser.add_argument("--output", type=Path, required=True, help="Evidence folder")
    args = parser.parse_args()
    items = json.loads(args.snapshot.read_text())
    matches = [i for i in (items if isinstance(items, list) else [items]) if i["name"] == args.name]
    if len(matches) != 1:
        raise ValueError("Expected exactly one named synthetic GPG fixture")
    print(json.dumps(inspect_gpg(matches[0], args.output), ensure_ascii=False))
