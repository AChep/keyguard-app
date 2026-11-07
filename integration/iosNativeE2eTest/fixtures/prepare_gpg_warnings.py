#!/usr/bin/env python3
"""Create weak, expired and revoked GPG keys and add their private keys to test Files.

Generates RSA1024, expired RSA2048 and revoked RSA2048 keys in isolated GnuPG
homes, with independently checked signed and encrypted messages, including a
signature made after the key expired. The lease `gpg-warning-fixture-state.json`
records the previous Files hashes; existing Files documents are never overwritten.
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from lib.simulator import device_argument, files_directory, files_map
from lib import session

BODY = b"Native GPG warning E2E\n"


def expired_after_expiry_signature(output, private, public, fingerprint):
    """Sign after the original expiry using a renewal kept outside the vault."""
    homes = []
    try:
        def isolated(prefix):
            home = Path(tempfile.mkdtemp(prefix=prefix, dir="/private/tmp"))
            homes.append(home)
            return ["gpg", "--homedir", str(home), "--batch", "--yes", "--pinentry-mode", "loopback", "--passphrase", ""]

        signer = isolated("kg-gpg-late-sign-")
        subprocess.run([*signer, "--import"], input=private, capture_output=True, check=True)
        subprocess.run([*signer, "--faked-system-time", "1735689720!", "--quick-set-expire", fingerprint, "0"],
                       capture_output=True, check=True)
        signed = subprocess.run([*signer, "--faked-system-time", "1735862400!", "--armor", "--local-user", fingerprint, "--clearsign"],
                                input=BODY, capture_output=True, check=True).stdout
        target = output / "expired-after-expiry-signed.asc"
        target.write_bytes(signed)
        verifier = isolated("kg-gpg-late-verify-")
        subprocess.run([*verifier, "--import"], input=public, capture_output=True, check=True)
        result = subprocess.run([*verifier, "--status-fd", "1", "--verify", str(target)], capture_output=True)
        status = result.stdout.decode()
        assert "[GNUPG:] VALIDSIG " in status and "[GNUPG:] EXPKEYSIG " in status, status
        (output / "expired-after-expiry-verification-status.txt").write_text(status)
    finally:
        for home in homes:
            subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"], capture_output=True, check=False)
            shutil.rmtree(home)


def prepare(device):
    os.umask(0o077)
    lease = session.path("gpg-warning-fixture-state.json")
    assert not lease.exists(), "Recover the existing warning-fixture lease first"
    output = session.directory() / "gpg-warning-fixtures"
    assert not output.exists()
    output.mkdir(mode=0o700)
    (output / "message.txt").write_bytes(BODY)
    cases = []
    for kind, bits in [("weak", 1024), ("expired", 2048), ("revoked", 2048)]:
        # macOS agent sockets have a short path limit. Export evidence into the
        # session, but keep each isolated agent home under the short tmp root.
        home = Path(tempfile.mkdtemp(prefix="kg-gpg-" + kind + "-", dir="/private/tmp"))
        command = ["gpg", "--homedir", str(home), "--batch", "--yes", "--pinentry-mode", "loopback", "--passphrase", ""]

        def gpg(*args, data=None, check=True):
            return subprocess.run([*command, *args], input=data, capture_output=True, check=check)

        uid = f"Native GPG {kind.title()} E2E <gpg-{kind}@keyguard.test>"
        fake = ["--faked-system-time", "1735689600!"] if kind == "expired" else []
        try:
            gpg(*fake, "--quick-generate-key", uid, "rsa" + str(bits), "sign,cert,encr", "1d" if kind == "expired" else "0")
            listing = gpg("--with-colons", "--list-keys").stdout.decode()
            fingerprint = next(z.split(":")[9] for z in listing.splitlines() if z.startswith("fpr:"))
            if kind != "weak":
                signed = gpg(*(["--faked-system-time", "1735689660!"] if kind == "expired" else []),
                             "--armor", "--local-user", fingerprint, "--clearsign", data=BODY).stdout
                (output / (kind + "-signed.asc")).write_bytes(signed)
            if kind == "revoked":
                raw = (home / "openpgp-revocs.d" / (fingerprint + ".rev")).read_bytes()
                revocation = raw[raw.index(b"-----BEGIN PGP PUBLIC KEY BLOCK-----"):]
                (output / "revocation.asc").write_bytes(revocation)
                gpg("--import", data=revocation)
            public = gpg("--armor", "--export", fingerprint).stdout
            private = gpg("--armor", "--export-secret-keys", fingerprint).stdout
            assert public.startswith(b"-----BEGIN PGP PUBLIC KEY BLOCK-----")
            assert private.startswith(b"-----BEGIN PGP PRIVATE KEY BLOCK-----")
            (output / (kind + "-public.asc")).write_bytes(public)
            (output / (kind + "-private.asc")).write_bytes(private)
            listing = gpg("--with-colons", "--list-keys", fingerprint).stdout.decode()
            primary = next(z.split(":") for z in listing.splitlines() if z.startswith("pub:"))
            assert int(primary[2]) == bits and primary[3] == "1"
            assert primary[1] == {"weak": "u", "expired": "e", "revoked": "r"}[kind]
            shown = gpg("--with-colons", "--import-options", "show-only", "--import", data=private).stdout.decode()
            assert next(z.split(":")[9] for z in shown.splitlines() if z.startswith("fpr:")) == fingerprint
            case = dict(kind=kind, bits=bits, fingerprint=fingerprint, uid=uid,
                        gnupgPrimaryStatus=primary[1], fixtureName="iPhone E2E GPG " + kind.title())
            if kind == "weak":
                encrypted = gpg("--rfc4880", "--cipher-algo", "AES256", "--trust-model", "always", "--armor", "--recipient", fingerprint, "--encrypt", data=BODY).stdout
                (output / "weak-encrypted.asc").write_bytes(encrypted)
                assert gpg("--decrypt", data=encrypted).stdout == BODY
                case["independentDecryptExact"] = True
            else:
                verified = gpg("--status-fd", "1", "--verify", str(output / (kind + "-signed.asc")), check=False)
                status = verified.stdout.decode()
                marker = "EXPKEYSIG" if kind == "expired" else "REVKEYSIG"
                assert "[GNUPG:] VALIDSIG " in status and "[GNUPG:] " + marker + " " in status, status
                (output / (kind + "-verification-status.txt")).write_text(status)
                case.update(independentCryptographicSignatureValid=True, independentWarning=marker)
                if kind == "expired":
                    expired_after_expiry_signature(output, private, public, fingerprint)
                    case["afterExpirySignatureIndependentlyValidWithWarning"] = True
            cases.append(case)
        finally:
            subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"], capture_output=True, check=False)
            shutil.rmtree(home)
    target = files_directory(device)
    state = dict(startedAtEpoch=time.time(), device=device, passwordChecksMustStayOff=True,
                 filesFixtureDirectory=str(target), filesBaseline=files_map(target),
                 fileProvisionPending=True, draftActive=False, filePickerActive=False,
                 itemCreatePending=False, itemCleanupPending=False, toolsRestorationPending=False,
                 ownedItems=[], ownedFixtureFiles=[], cases=cases, observations=[])
    session.write_json(lease, state)
    for case in cases:
        source = output / (case["kind"] + "-private.asc")
        filename = "e2e-gpg-warning-" + case["kind"] + ".asc"
        destination = target / filename
        assert not destination.exists(), "Never overwrite an existing native fixture"
        value = dict(filename=filename, size=source.stat().st_size, sha256=hashlib.sha256(source.read_bytes()).hexdigest())
        state["ownedFixtureFiles"].append(value)
        session.write_json(lease, state)
        shutil.copyfile(source, destination)
        destination.chmod(0o600)
        assert destination.read_bytes() == source.read_bytes()
    after = files_map(target)
    assert all(after[name] == value for name, value in state["filesBaseline"].items())
    assert len(after) == len(state["filesBaseline"]) + 3
    state.update(fileProvisionPending=False, fixtureFilesRetainedAsEvidence=True)
    session.write_json(lease, state)
    session.write_json(output / "manifest.json", dict(cases=cases, message=BODY.decode(), isolatedHomesOnly=True))
    print(json.dumps(dict(cases=cases, nativeInputs=3, allOriginalNativeFilesExact=True), indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    prepare(parser.parse_args().device)
