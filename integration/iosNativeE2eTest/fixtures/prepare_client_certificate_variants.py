#!/usr/bin/env python3
"""Add expired, not-yet-valid, expiring-soon and untrusted PKCS#12 identities to test Files.

Signs new client certificates for the session's client key with the session's
test CA, plus one from a separate untrusted CA, and adds an empty-password .pfx
and a 4 MiB + 1 byte size-limit fixture. OpenSSL checks PKCS#12 parsing, chain
and validity, and the copied-file hashes. Refuses an existing variants folder;
the session's CA, server and client files are only read.
"""

import argparse
import hashlib
import shutil
import subprocess
from datetime import datetime, timedelta, timezone

from lib.config import P12_PASSWORD as PASSWORD
from lib.simulator import device_argument, files_directory
from lib import session


def openssl(*args, check=True):
    return subprocess.run(
        ["openssl", *map(str, args)], check=check, capture_output=True, text=True
    )


def prepare_extra(directory, files):
    pfx = directory / "e2e-no-password-client.pfx"
    oversized = directory / "e2e-oversized-client.p12"
    assert not pfx.exists() and not oversized.exists()
    openssl("pkcs12", "-export", "-in", session.tls("client.pem"),
            "-inkey", session.tls("client.key"),
            "-certfile", session.tls("ca.pem"), "-out", pfx, "-passout", "pass:")
    openssl("pkcs12", "-in", pfx, "-passin", "pass:", "-noout")
    oversized.write_bytes(bytes(4 * 1024 * 1024 + 1))
    proof = []
    for source in (pfx, oversized):
        source.chmod(0o600)
        destination = files / source.name
        shutil.copyfile(source, destination)
        destination.chmod(0o600)
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        assert hashlib.sha256(destination.read_bytes()).hexdigest() == digest
        proof.append(dict(file=source.name, bytes=source.stat().st_size, sha256=digest))
    session.write_json(session.path("client-certificate-extra-fixtures-proof.json"), proof)


def prepare(files):
    now = datetime.now(timezone.utc).replace(microsecond=0)
    directory = session.path("client-certificates", "variants")
    # Refuse to overwrite previous certificates that may be referenced by a run.
    directory.mkdir(mode=0o700, exist_ok=False)
    ca = session.tls("ca.pem")
    ca_key = session.tls("ca.key")
    key = session.tls("client.key")
    password = directory / "password.txt"
    password.write_text(PASSWORD)
    password.chmod(0o600)
    wrong_ca = directory / "untrusted-ca.pem"
    wrong_key = directory / "untrusted-ca.key"
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
            "-subj", "/CN=Keyguard E2E Untrusted CA", "-keyout", wrong_key,
            "-out", wrong_ca, "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    wrong_key.chmod(0o600)
    variants = [
        ("expired", now - timedelta(days=2), now - timedelta(days=1), ca, ca_key),
        ("not-yet-valid", now + timedelta(hours=1), now + timedelta(days=1), ca, ca_key),
        ("expiring-soon", now - timedelta(days=1), now + timedelta(days=1), ca, ca_key),
        ("untrusted", now - timedelta(minutes=1), now + timedelta(days=1), wrong_ca, wrong_key),
    ]
    proof = []
    for index, (name, start, end, issuer, issuer_key) in enumerate(variants):
        stem = "e2e-" + name + "-client"
        csr, pem, p12 = [directory / (stem + extension) for extension in (".csr", ".pem", ".p12")]
        subject = "iOS E2E " + name + " Client"
        openssl("req", "-new", "-key", key, "-subj", "/CN=" + subject, "-out", csr)
        database = directory / (name + ".index")
        database.touch()
        serial = directory / (name + ".serial")
        serial.write_text(f"{1000 + index:04X}\n")
        config = directory / (name + ".cnf")
        config.write_text(f"""[ca]
default_ca=fixture
[fixture]
database={database}
serial={serial}
new_certs_dir={directory}
certificate={issuer}
private_key={issuer_key}
default_md=sha256
policy=policy
x509_extensions=client
unique_subject=no
[policy]
commonName=supplied
[client]
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=clientAuth
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
""")
        openssl("ca", "-batch", "-notext", "-config", config, "-in", csr, "-out", pem,
                "-startdate", start.strftime("%Y%m%d%H%M%SZ"),
                "-enddate", end.strftime("%Y%m%d%H%M%SZ"))
        openssl("pkcs12", "-export", "-in", pem, "-inkey", key, "-certfile", issuer,
                "-name", subject, "-out", p12, "-passout", "file:" + str(password))
        p12.chmod(0o600)
        openssl("pkcs12", "-in", p12, "-noout", "-passin", "file:" + str(password))
        verification = openssl("verify", "-purpose", "sslclient", "-CAfile", ca, pem, check=False)
        expected_valid = name == "expiring-soon"
        assert (verification.returncode == 0) == expected_valid, verification.stdout + verification.stderr
        destination = files / p12.name
        shutil.copyfile(p12, destination)
        destination.chmod(0o600)
        digest = hashlib.sha256(p12.read_bytes()).hexdigest()
        assert hashlib.sha256(destination.read_bytes()).hexdigest() == digest
        metadata = openssl("x509", "-in", pem, "-noout", "-subject", "-issuer", "-dates",
                           "-serial", "-fingerprint", "-sha256").stdout
        proof.append(dict(name=name, file=p12.name, subject=subject, password=PASSWORD,
                          sha256=digest, metadata=metadata,
                          trustedAndCurrentlyValid=expected_valid,
                          verification=(verification.stdout + verification.stderr).strip()))
    output = session.write_json(session.path("client-certificate-variants-proof.json"),
                                dict(createdAt=now.isoformat(), files=str(files), fixtures=proof))
    prepare_extra(directory, files)
    print(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    prepare(files_directory(parser.parse_args().device))
