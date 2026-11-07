#!/usr/bin/env python3
"""Sign valid, expired, future, host and mismatched OpenSSH certificates for a native SSH item.

Reads the item from the server with the independent oracle, checks its
public/private pair and RSA-3072 size with ssh-keygen, then signs certificates
with a new local Ed25519 CA. Keys and certificates stay in the session folder;
no SSH connection, publication or server change is made. The lease
`ssh-advanced-fixture-state.json` prevents a second attempt.
"""

import argparse
import json
import subprocess

from lib import session
from lib.bitwarden import snapshot
from lib.config import account

NAME = "iPhone E2E SSH Advanced"


def run(*args):
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def private_file(path, value):
    path.write_text(value)
    path.chmod(0o600)


def prepare(fixture, item_id):
    state_path = session.path("ssh-advanced-fixture-state.json")
    state = session.read_json(state_path) if state_path.exists() else {}
    if state.get("certificatePrepareAttempted"):
        raise ValueError("Already attempted; inspect private fixture state before retrying")
    items = snapshot(fixture)
    matches = [x for x in items if x["id"] == item_id and x["name"] == NAME and x["type"] == 5]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one SSH key item {NAME!r} with ID {item_id}")
    item = matches[0]
    directory = session.directory() / "ssh-advanced"
    directory.mkdir(mode=0o700)
    state.update(name=NAME, itemId=item_id, certificatePrepareAttempted=True)
    session.write_json(state_path, state)

    key = item["sshKey"]
    leaf = directory / "native-rsa3072"
    private_file(leaf, key["privateKey"])
    derived = run("ssh-keygen", "-y", "-f", str(leaf)).strip()
    if derived.split()[:2] != key["publicKey"].split()[:2]:
        raise ValueError("Native public/private keys do not match")
    public = leaf.with_suffix(".pub")
    private_file(public, derived + " native-e2e\n")
    listing = run("ssh-keygen", "-lf", str(public)).strip()
    if not listing.startswith("3072 ") or not listing.endswith("(RSA)"):
        raise ValueError("Native generator did not produce RSA-3072")

    ca = directory / "local-ca"
    other = directory / "other-leaf"
    for path, comment in ((ca, "native-e2e-local-ca"), (other, "native-e2e-other-leaf")):
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", comment, "-f", str(path))

    variants = (
        ("valid", public, "-5m:+1h", False),
        ("expired", public, "-2h:-1h", False),
        ("future", public, "+1h:+2h", False),
        ("host", public, "-5m:+1h", True),
        ("mismatch", other.with_suffix(".pub"), "-5m:+1h", False),
    )
    proof = {"cipherId": item["id"], "nativeRsa3072PairMatches": True, "publicKeyListing": listing,
             "certificates": {}, "noExternalRequests": True}
    for serial, (name, source, validity, host) in enumerate(variants, 101):
        args = ["ssh-keygen", "-q", "-s", str(ca), "-I", "native-e2e-" + name,
                "-z", str(serial), "-n", "127.0.0.1" if host else "e2e",
                "-V", validity]
        if host:
            args.append("-h")
        run(*args, str(source))
        generated = source.with_name(source.stem + "-cert.pub")
        target = directory / (name + "-cert.pub")
        generated.rename(target)
        target.chmod(0o600)
        proof["certificates"][name] = {
            "file": str(target), "serial": serial,
            "description": run("ssh-keygen", "-Lf", str(target)),
        }
    if snapshot(fixture) != items:
        raise ValueError("Fixture changed while preparing local certificates")
    session.write_json(session.path("ssh-advanced-certificate-oracle.json"), proof)
    state["certificatesPrepared"] = True
    session.write_json(state_path, state)
    return {"nativeRsa3072PairMatches": True, "certificateVariants": list(proof["certificates"]),
            "serverUnchanged": True, "noExternalRequests": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--item-id", required=True,
                        help=f"Server ID of the native SSH key item named {NAME!r}")
    parser.add_argument("--account", default="main", help="Fixture account key (default: main)")
    args = parser.parse_args()
    print(json.dumps(prepare(account(args.account), args.item_id)))
