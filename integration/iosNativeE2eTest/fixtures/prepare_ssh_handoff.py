#!/usr/bin/env python3
"""Sign an OpenSSH user certificate for a private key in test Files and add it next to the key.

The private key is a native export in the Files folder. The certificate is
signed by a local Ed25519 CA, by default the one `e2e prepare_ssh_certificates`
created. The key match and the CA signature are verified independently with
OpenSSL; the private key and the CA stay unchanged. The lease
`ssh-file-handoff-fixture-state.json` records the owned certificate file and
prevents a second attempt. No SSH connection or publication is made.
"""

import argparse
import base64
import hashlib
import os
import struct
import subprocess
import time
from pathlib import Path

from lib.simulator import device_argument, files_directory
from lib import session

SERIAL = 1001


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise ValueError(result.stderr[-2000:])
    return result.stdout.strip()


def string(blob, offset):
    length = struct.unpack_from(">I", blob, offset)[0]
    end = offset + 4 + length
    assert end <= len(blob)
    return blob[offset + 4:end], end


def prepare(device, private_filename, certificate_filename, ca):
    os.umask(0o077)
    state_path = session.path("ssh-file-handoff-fixture-state.json")
    state = session.read_json(state_path) if state_path.exists() else {}
    assert not state.get("certificatePrepared") and not state.get("filePreparationPending")
    files = files_directory(device)
    private = files / private_filename
    target = files / certificate_filename
    assert private.is_file() and not target.exists()
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    private_before, ca_before = digest(private), digest(ca)
    directory = session.directory() / "ssh-file-handoff"
    directory.mkdir(mode=0o700)
    state.update(filesFixtureDirectory=str(files), privateFilename=private_filename,
                 certificateFilename=certificate_filename, privateBeforeSha256=private_before,
                 localCaPath=str(ca), localCaBeforeSha256=ca_before, filePreparationPending=True)
    session.write_json(state_path, state)
    working_private = directory / "fixture-leaf-private"
    working_private.write_bytes(private.read_bytes())
    working_private.chmod(0o600)
    public = run("ssh-keygen", "-y", "-P", "", "-f", str(working_private))
    assert public.split()[0] == "ssh-rsa"
    leaf = directory / "fixture-leaf.pub"
    leaf.write_text(public + " native-e2e-file-handoff\n")
    run("ssh-keygen", "-q", "-s", str(ca), "-I", "native-e2e-ssh-file-handoff", "-z", str(SERIAL),
        "-n", "e2e", "-V", "-5m:+2h", str(leaf))
    certificate = directory / "fixture-leaf-cert.pub"
    armor = certificate.read_bytes()
    blob = base64.b64decode(armor.split()[1], validate=True)
    algorithm, offset = string(blob, 0)
    assert algorithm == b"ssh-rsa-cert-v01@openssh.com"
    _, offset = string(blob, offset)  # Nonce.
    exponent, offset = string(blob, offset)
    modulus, offset = string(blob, offset)
    plain = base64.b64decode(public.split()[1], validate=True)
    kind, cursor = string(plain, 0)
    expected_e, cursor = string(plain, cursor)
    expected_n, cursor = string(plain, cursor)
    assert cursor == len(plain) and kind == b"ssh-rsa" and (exponent, modulus) == (expected_e, expected_n)
    serial, certificate_type = struct.unpack_from(">QI", blob, offset)
    offset += 12
    identity, offset = string(blob, offset)
    principals, offset = string(blob, offset)
    principal, principal_end = string(principals, 0)
    assert principal == b"e2e" and principal_end == len(principals)
    valid_after, valid_before = struct.unpack_from(">QQ", blob, offset)
    offset += 16
    for _ in range(3):
        _, offset = string(blob, offset)  # Critical options, extensions, reserved.
    ca_blob, offset = string(blob, offset)
    signed_end = offset
    signature_blob, offset = string(blob, offset)
    assert offset == len(blob)
    signature_kind, cursor = string(signature_blob, 0)
    signature, cursor = string(signature_blob, cursor)
    ca_public = run("ssh-keygen", "-y", "-P", "", "-f", str(ca))
    assert ca_blob == base64.b64decode(ca_public.split()[1], validate=True)
    ca_kind, ca_cursor = string(ca_blob, 0)
    ca_key, ca_cursor = string(ca_blob, ca_cursor)
    assert ca_kind == signature_kind == b"ssh-ed25519" and len(ca_key) == 32 and len(signature) == 64
    assert ca_cursor == len(ca_blob) and cursor == len(signature_blob)
    assert serial == SERIAL and certificate_type == 1 and identity == b"native-e2e-ssh-file-handoff"
    assert valid_after <= time.time() < valid_before
    payload = directory / "signed-payload.bin"
    payload.write_bytes(blob[:signed_end])
    signature_file = directory / "signature.ed25519"
    signature_file.write_bytes(signature)
    public_der = directory / "ca-public.der"
    public_der.write_bytes(bytes.fromhex("302a300506032b6570032100") + ca_key)
    run("openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(public_der), "-keyform", "DER",
        "-rawin", "-in", str(payload), "-sigfile", str(signature_file))
    owned = dict(filename=target.name, size=len(armor), sha256=hashlib.sha256(armor).hexdigest())
    state["ownedFixtureFiles"] = [owned]
    session.write_json(state_path, state)
    with target.open("xb") as output:
        output.write(armor)
    target.chmod(0o600)
    assert digest(ca) == ca_before and digest(private) == private_before
    fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(plain).digest()).decode().rstrip("=")
    proof = dict(nativePrivateInputUnchanged=True, localCaUnchanged=True, publicKeyMatchesPrivate=True,
        certificateUnderlyingKeyMatches=True, issuerSignatureIndependentlyVerified=True,
        fingerprint=fingerprint, bits=int.from_bytes(modulus, "big").bit_length(),
        serial=serial, type="user", principal="e2e", keyId=identity.decode(),
        validAfter=valid_after, validBefore=valid_before, certificate=owned,
        description=run("ssh-keygen", "-Lf", str(certificate)), noSshConnectionOrPublication=True)
    session.write_json(session.path("ssh-file-handoff-certificate-proof.json"), proof)
    state.update(filePreparationPending=False, certificatePrepared=True, expectedFingerprint=fingerprint)
    session.write_json(state_path, state)
    print("Prepared one private synthetic user-certificate file; key match and Ed25519 signature independently verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    parser.add_argument("--private-key", required=True,
                        help="Filename of the unencrypted RSA private key in the Files folder")
    parser.add_argument("--certificate", required=True,
                        help="Filename of the new certificate in the Files folder")
    parser.add_argument("--ca", type=Path,
                        help="Ed25519 CA private key (default: session ssh-advanced/local-ca)")
    args = parser.parse_args()
    ca = args.ca or session.directory() / "ssh-advanced" / "local-ca"
    prepare(args.device, args.private_key, args.certificate, ca)
