#!/usr/bin/env python3
"""Serve a local HTTPS VKS keyserver for one synthetic public GPG key.

Binds only to loopback with the session's test server certificate. Accepts
uploads of the given public key only, rejecting secret packets and other
fingerprints. Verification requests are recorded, never mailed. Lookups
return 404 until /control sets `published`; /control also switches failure
modes. Not a full VKS implementation.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

from lib import session
from lib.config import PORTS

TOKEN = "keyguard-local-vks-token"
CONTROL = "keyguard-local-vks-control"


def fingerprint(armor):
    if (not armor.startswith(b"-----BEGIN PGP PUBLIC KEY BLOCK-----") or
            b"PRIVATE KEY" in armor or b"SECRET KEY" in armor):
        raise ValueError("Only public armor is accepted")
    with tempfile.TemporaryDirectory(prefix="kg-vks-public-", dir="/private/tmp") as home:
        result = subprocess.run(
            ["gpg", "--homedir", home, "--batch", "--no-auto-key-retrieve", "--with-colons",
             "--import-options", "show-only", "--import"],
            input=armor, capture_output=True, check=True, timeout=10,
        )
        lines = result.stdout.decode().splitlines()
        if any(x.startswith(("sec:", "ssb:")) for x in lines):
            raise ValueError("Secret packets are forbidden")
        return next(x.split(":")[9] for x in lines if x.startswith("fpr:"))


class Server(ThreadingHTTPServer):
    def __init__(self, args):
        self.directory = args.directory
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.directory.chmod(0o700)
        self.public = args.public_key.read_bytes()
        self.fingerprint = fingerprint(self.public)
        if self.fingerprint != args.fingerprint or not re.fullmatch(r"[A-F0-9]{40}", self.fingerprint):
            raise ValueError("Unexpected synthetic certificate fingerprint")
        self.email = args.email
        if not self.email.endswith("@keyguard.test"):
            raise ValueError("Only the synthetic test address is accepted")
        self.state_path = self.directory / "state.json"
        self.state = (json.loads(self.state_path.read_text()) if self.state_path.exists() else
                      {"mode": "normal", "uploaded": False, "pending": False, "published": False,
                       "uploads": [], "verificationRequests": [], "lookups": [], "noMailRelay": True})
        self.lock = threading.RLock()
        self.persist()
        super().__init__(("127.0.0.1", args.port), Handler)
        session.serve_tls(self, args.certificate, args.private_key)

    def persist(self):
        temporary = self.state_path.with_suffix(".tmp")
        session.write_json(temporary, self.state)
        temporary.replace(self.state_path)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, code, body, content_type="application/json"):
        data = json.dumps(body).encode() if content_type == "application/json" else body
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def result(self):
        state = self.server.state
        status = "published" if state["published"] else "pending" if state["pending"] else "unpublished"
        return {"key_fpr": self.server.fingerprint, "token": TOKEN,
                "status": {self.server.email: status}}

    def do_GET(self):
        with self.server.lock:
            if self.path == "/state":
                return self.reply(200, self.server.state)
            prefix = "/vks/v1/"
            path = unquote(self.path)
            if path not in (prefix + "by-fingerprint/" + self.server.fingerprint,
                            prefix + "by-email/" + self.server.email):
                return self.reply(404, {"error": "Unknown synthetic lookup"})
            self.server.state["lookups"].append({"path": path, "published": self.server.state["published"]})
            self.server.persist()
            if not self.server.state["published"]:
                return self.reply(404, {"error": "Synthetic certificate not published"})
            return self.reply(200, self.server.public, "application/pgp-keys")

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 1_000_000:
                raise ValueError("Invalid request size")
            body = json.loads(self.rfile.read(length))
            with self.server.lock:
                if self.path == "/control":
                    if self.headers.get("X-Keyguard-E2E-Control") != CONTROL:
                        return self.reply(403, {"error": "Control token required"})
                    if set(body) - {"mode", "published"}:
                        raise ValueError("Unknown control field")
                    if "mode" in body:
                        if body["mode"] not in ("normal", "unavailable", "mismatch"):
                            raise ValueError("Unknown fixture mode")
                        self.server.state["mode"] = body["mode"]
                    if "published" in body:
                        if not isinstance(body["published"], bool):
                            raise ValueError("Published must be boolean")
                        self.server.state["published"] = body["published"]
                    self.server.persist()
                    return self.reply(200, self.server.state)
                if self.path == "/vks/v1/upload":
                    if set(body) != {"keytext"} or not isinstance(body["keytext"], str):
                        raise ValueError("Unexpected upload shape")
                    armor = body["keytext"].encode()
                    if fingerprint(armor) != self.server.fingerprint:
                        raise ValueError("Unexpected certificate")
                    record = {"sha256": hashlib.sha256(armor).hexdigest(), "size": len(armor),
                              "fingerprint": self.server.fingerprint, "publicOnly": True,
                              "mode": self.server.state["mode"]}
                    self.server.state["uploads"].append(record)
                    (self.server.directory / ("upload-%03d.asc" % len(self.server.state["uploads"]))).write_bytes(armor)
                    self.server.persist()
                    if self.server.state["mode"] == "unavailable":
                        return self.reply(503, {"Message": "Local VKS E2E unavailable"})
                    self.server.state["uploaded"] = True
                    self.server.persist()
                    result = self.result()
                    if self.server.state["mode"] == "mismatch":
                        result["key_fpr"] = "0" * 40
                    return self.reply(200, result)
                if self.path == "/vks/v1/request-verify":
                    if (set(body) != {"token", "addresses"} or body["token"] != TOKEN or
                            body["addresses"] != [self.server.email] or not self.server.state["uploaded"]):
                        raise ValueError("Unexpected synthetic verification request")
                    self.server.state["verificationRequests"].append({"addresses": body["addresses"], "mailRelayed": False})
                    self.server.state["pending"] = True
                    self.server.persist()
                    return self.reply(200, self.result())
                return self.reply(404, {"error": "Unknown fixture endpoint"})
        except (ValueError, KeyError, TypeError, subprocess.SubprocessError, StopIteration):
            return self.reply(400, {"error": "Rejected non-fixture request"})


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate", type=Path,
                        help="Server certificate (default: the session's tls/server.pem)")
    parser.add_argument("--private-key", type=Path,
                        help="Server private key (default: the session's tls/server.key)")
    parser.add_argument("--public-key", type=Path, required=True,
                        help="ASCII-armored public key of the synthetic certificate")
    parser.add_argument("--fingerprint", required=True, help="Its full uppercase fingerprint")
    parser.add_argument("--email", required=True, help="Its @keyguard.test address")
    parser.add_argument("--directory", type=Path, help="Evidence folder (default: <session>/gpg-keyserver)")
    parser.add_argument("--port", type=int, default=PORTS["gpg-keyserver"])
    args = parser.parse_args()
    args.certificate = args.certificate or session.tls("server.pem")
    args.private_key = args.private_key or session.tls("server.key")
    args.directory = args.directory or session.path("gpg-keyserver")
    server = Server(args)
    print(f"Local VKS fixture ready on https://127.0.0.1:{args.port}; no mail relay", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
