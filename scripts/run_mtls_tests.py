#!/usr/bin/env python3
"""Run Gradle mTLS integration tests against isolated, loopback-only TLS servers.

Requires Python 3 and OpenSSL. Generates disposable test CAs and identities;
never installs trust roots or changes the machine's certificate stores.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading


def certificates(directory: Path) -> dict[str, str]:
    openssl = os.environ.get("OPENSSL") or shutil.which("openssl")
    if not openssl:
        raise RuntimeError("OpenSSL is required to generate the mTLS test identities")

    def run(*args: str) -> None:
        subprocess.run([openssl, *args], cwd=directory, check=True, capture_output=True)

    def key(name: str, ec: bool = False) -> None:
        if ec:
            run("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", f"{name}.key")
        else:
            run("genrsa", "-out", f"{name}.key", "2048")

    def issue(name: str, issuer: str, serial: int, extensions: str, ec: bool = False) -> None:
        key(name, ec)
        run("req", "-new", "-key", f"{name}.key", "-subj", f"/CN={name}", "-out", f"{name}.csr")
        (directory / f"{name}.ext").write_text(extensions)
        run("x509", "-req", "-in", f"{name}.csr", "-CA", f"{issuer}.pem", "-CAkey", f"{issuer}.key",
            "-set_serial", str(serial), "-days", "2", "-sha256", "-extfile", f"{name}.ext", "-out", f"{name}.pem")

    for ca in ("ca", "untrusted-ca"):
        key(ca)
        (directory / f"{ca}.cnf").write_text(
            f"[req]\ndistinguished_name=dn\nx509_extensions=ca\n[dn]\n[ca]\n"
            "basicConstraints=critical,CA:true\nkeyUsage=critical,keyCertSign,cRLSign\n"
        )
        run("req", "-new", "-x509", "-key", f"{ca}.key", "-subj", f"/CN={ca}", "-days", "2",
            "-sha256", "-config", f"{ca}.cnf", "-out", f"{ca}.pem")
    issue("intermediate", "ca", 10,
          "basicConstraints=critical,CA:true,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\n")
    server_extensions = ("basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                         "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\n")
    issue("server", "ca", 11, server_extensions)
    issue("untrusted-server", "untrusted-ca", 12, server_extensions)
    result = {}
    client_extensions = "basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n"
    for name, issuer, serial, ec in (("client-one", "intermediate", 20, False),
                                     ("client-two", "intermediate", 21, True),
                                     ("wrong-client", "untrusted-ca", 22, False)):
        issue(name, issuer, serial, client_extensions, ec)
        chain = (directory / f"{issuer}.pem").read_bytes()
        if issuer == "intermediate":
            chain += (directory / "ca.pem").read_bytes()
        (directory / "chain.pem").write_bytes(chain)
        run("pkcs12", "-export", "-inkey", f"{name}.key", "-in", f"{name}.pem", "-certfile", "chain.pem",
            "-passout", "pass:fixture", "-keypbe", "PBE-SHA1-3DES", "-certpbe", "PBE-SHA1-3DES",
            "-macalg", "sha1", "-out", f"{name}.p12")
        result[name] = base64.b64encode((directory / f"{name}.p12").read_bytes()).decode()
    run("x509", "-in", "ca.pem", "-outform", "DER", "-out", "ca.der")
    result["ca"] = base64.b64encode((directory / "ca.der").read_bytes()).decode()
    return result


class TestServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, context: ssl.SSLContext | None = None):
        super().__init__(("127.0.0.1", 0), Handler)
        self.context = context
        self.requests = 0
        self.handshakes = 0
        self.lock = threading.Lock()
        self.fixture: dict = {}
        self.servers: list[TestServer] = []

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(5)
        try:
            if self.context:
                connection = self.context.wrap_socket(connection, server_side=True)
                with self.lock:
                    self.handshakes += 1
            return connection, address
        except BaseException:
            connection.close()
            raise


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        with self.server.lock:
            self.server.requests += 1
        if self.path == "/fixture":
            self.respond(json.dumps(self.server.fixture))
        elif self.path == "/counts":
            self.respond(json.dumps({str(s.server_port): {"requests": s.requests, "handshakes": s.handshakes}
                                     for s in self.server.servers}))
        elif self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", self.server.fixture["externalUrl"] + "identity")
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            certificate = self.connection.getpeercert() or {}
            peer = next((v for rdn in certificate.get("subject", ()) for k, v in rdn if k == "commonName"), "none")
            if self.path == "/ws":
                self.websocket(peer)
            else:
                self.respond(peer)

    def respond(self, value: str):
        data = value.encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def websocket(self, peer: str):
        self.close_connection = True
        digest = hashlib.sha1((self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", base64.b64encode(digest).decode())
        self.end_headers()
        self.frame(peer)
        header = self.rfile.read(2)
        if len(header) != 2 or header[0] != 0x81 or header[1] != 0x84:
            raise ValueError("Expected one masked text frame containing ping")
        mask = self.rfile.read(4)
        payload = self.rfile.read(4)
        if bytes(value ^ mask[i % 4] for i, value in enumerate(payload)) != b"ping":
            raise ValueError("Unexpected WebSocket message")
        self.frame("pong:" + peer)
        self.wfile.write(b"\x88\x02\x03\xe8")  # Normal closure.
        self.wfile.flush()

    def frame(self, text: str):
        data = text.encode()
        self.wfile.write(bytes((0x81, len(data))) + data)
        self.wfile.flush()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", action="store_true", help="Forward the fixture ports to an Android device")
    parser.add_argument("--device", help="ADB serial; otherwise ADB must have exactly one device")
    parser.add_argument("command", nargs=argparse.REMAINDER, help="-- ./gradlew <test tasks and filters>")
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("A Gradle command is required after --")
    servers = []
    threads = []
    forwarded = []
    adb = ["adb"] + (["-s", args.device] if args.device else [])
    with tempfile.TemporaryDirectory(prefix="keyguard-mtls-") as temporary:
        directory = Path(temporary)
        identities = certificates(directory)
        try:
            control = TestServer()
            servers.append(control)
            for identity, required in (("server", True), ("server", True), ("server", False), ("untrusted-server", True)):
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.load_cert_chain(directory / f"{identity}.pem", directory / f"{identity}.key")
                context.load_verify_locations(directory / "ca.pem")
                context.verify_mode = ssl.CERT_REQUIRED if required else ssl.CERT_OPTIONAL
                servers.append(TestServer(context))
            fixture = {"ca": identities["ca"], "first": identities["client-one"],
                       "second": identities["client-two"], "wrong": identities["wrong-client"]}
            for name, server in zip(("baseUrl", "notificationsUrl", "externalUrl", "untrustedUrl"), servers[1:]):
                fixture[name] = f"https://localhost:{server.server_port}/"
            for server in servers:
                server.fixture = fixture
                server.servers = servers
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                threads.append(thread)
                if args.android:
                    port = f"tcp:{server.server_port}"
                    subprocess.run([*adb, "reverse", port, port], check=True)
                    forwarded.append(port)
            environment = dict(os.environ, KEYGUARD_MTLS_PORT=str(control.server_port))
            environment["SIMCTL_CHILD_KEYGUARD_MTLS_PORT"] = str(control.server_port)
            command.append("-Pkeyguard.mtlsTests=true")
            if args.android:
                command.append(f"-Pandroid.testInstrumentationRunnerArguments.mtlsPort={control.server_port}")
                if args.device:
                    environment["ANDROID_SERIAL"] = args.device
            print("Running mTLS tests with disposable loopback servers", flush=True)
            result = subprocess.run(command, env=environment).returncode
            # Instrumentation can report success with zero tests when the selected
            # class is missing. Require the HTTPS, WSS and external-transfer paths.
            if result == 0 and any(server.requests == 0 for server in servers[1:4]):
                print("mTLS fixture was not exercised: the network suite did not run", file=sys.stderr)
                return 1
            return result
        finally:
            for port in forwarded:
                subprocess.run([*adb, "reverse", "--remove", port], check=False)
            for server in servers[:len(threads)]:
                server.shutdown()
            for server in servers:
                server.server_close()
            for thread in threads:
                thread.join(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
