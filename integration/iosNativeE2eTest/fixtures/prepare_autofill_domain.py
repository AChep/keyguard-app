#!/usr/bin/env python3
"""Issue a TLS leaf for test DNS names that resolve only to loopback.

Checks that every host resolves solely to 127.0.0.1, then signs a two-day leaf
with the session's test CA. Refuses an existing output folder and verifies that
the session's CA and server files stay unchanged.
"""

import argparse
import hashlib
import json
import secrets
import socket
import subprocess
from pathlib import Path

from lib import session
from lib.config import PORTS
from servers.autofill_web import ALTERNATE_HOST, evidence


def prepare(directory=None, additional_hosts=()):
    proof_path = directory / "domain-tls-proof.json" if directory is not None else evidence() / "domain-tls-proof.json"
    hosts = list(dict.fromkeys([ALTERNATE_HOST, *additional_hosts]))
    resolutions = {}
    for host in hosts:
        addresses = sorted({value[4][0] for value in socket.getaddrinfo(
            host, PORTS["autofill-web"], type=socket.SOCK_STREAM)})
        if addresses != ["127.0.0.1"]:
            raise ValueError(f"Test host {host} does not resolve only to loopback: {addresses}")
        resolutions[host] = addresses
    addresses = resolutions[ALTERNATE_HOST]
    protected = [session.tls(name) for name in ("ca.pem", "ca.key", "server.pem", "server.key")]
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    before = {str(path): digest(path) for path in protected}
    directory = directory or evidence() / "domain-tls"
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    key, csr, certificate = [directory / name for name in ("server.key", "server.csr", "server.pem")]
    extensions = directory / "server.ext"
    extensions.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
                          "extendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\n"
                          "authorityKeyIdentifier=keyid,issuer\n"
                          "subjectAltName=DNS:localhost,IP:127.0.0.1," +
                          ",".join("DNS:" + host for host in hosts) + "\n")
    def run(*arguments):
        return subprocess.run(["openssl", *map(str, arguments)], capture_output=True, check=True)
    run("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
        "-keyout", key, "-out", csr, "-subj", "/CN=Keyguard AutoFill Local Test")
    key.chmod(0o600)
    run("x509", "-req", "-in", csr, "-CA", session.tls("ca.pem"), "-CAkey", session.tls("ca.key"),
        "-set_serial", "0x" + secrets.token_hex(16), "-days", "2", "-extfile", extensions, "-out", certificate)
    for host in hosts:
        run("verify", "-CAfile", session.tls("ca.pem"), "-verify_hostname", host, certificate)
    assert before == {str(path): digest(path) for path in protected}
    proof = dict(host=ALTERNATE_HOST, resolvedAddresses=addresses, onlyLoopback=True,
                 certificate=str(certificate), privateKey=str(key), certificateSha256=digest(certificate),
                 allExistingTlsFilesUnchanged=True, protectedFileHashes=before)
    if additional_hosts:
        proof["additionalHosts"] = {host: resolutions[host] for host in hosts if host != ALTERNATE_HOST}
    session.write_json(proof_path, proof)
    print(json.dumps({name: value for name, value in proof.items() if name != "protectedFileHashes"}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path,
                        help="New output folder; the proof is written inside it "
                             "(default: session autofill-web/domain-tls)")
    parser.add_argument("--additional-host", action="append", default=[],
                        help="Another test host name for the leaf; repeatable")
    args = parser.parse_args()
    prepare(directory=args.directory, additional_hosts=args.additional_host)
