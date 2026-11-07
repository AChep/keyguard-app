#!/usr/bin/env python3
"""Serve two local HTTPS origins with a favicon, recording each request.

Binds only to loopback with the session's test server certificate. Records
HTML and PNG requests passively in state.json; /health and /state reads are
not recorded. No outbound relay.
"""

import argparse
import binascii
import json
import os
import struct
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from lib import session
from lib.config import PORTS


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data))


PNG = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 6, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress((b"\0" + bytes((20, 107, 209, 255)) * 32) * 32)) + chunk(b"IEND", b""))
HTML = b'<!doctype html><title>E2E icon fixture</title><link rel="icon" type="image/png" sizes="32x32" href="/icon.png">'


class Evidence:
    def __init__(self, directory):
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = directory / "state.json"
        self.lock = threading.Lock()
        self.state = session.read_json(self.path) if self.path.exists() else {"owner": "keyguard-ios-native-e2e-icons", "requests": []}
        assert self.state["owner"] == "keyguard-ios-native-e2e-icons"
        self.save()

    def save(self):
        temporary = self.path.with_suffix(".tmp")
        session.write_json(temporary, self.state)
        temporary.replace(self.path)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        evidence = self.server.evidence
        if self.path in ("/health", "/state"):
            with evidence.lock:
                return self.reply(json.dumps(evidence.state).encode(), "application/json")
        icon = self.path.split("?", 1)[0] in ("/icon.png", "/favicon.ico", "/apple-touch-icon.png")
        with evidence.lock:
            evidence.state["requests"].append(dict(port=self.server.server_port, path=self.path,
                time=time.time(), kind="png" if icon else "html", userAgent=self.headers.get("User-Agent")))
            evidence.save()
        return self.reply(PNG if icon else HTML, "image/png" if icon else "text/html; charset=utf-8")

    def reply(self, data, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, help="Evidence folder (default: <session>/icon-network-web)")
    args = parser.parse_args()
    evidence = Evidence(args.directory or session.path("icon-network-web"))
    ports = (PORTS["icon-network"], PORTS["icon-network-alternate"])
    for port in ports:
        server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        server.evidence = evidence
        session.serve_tls(server)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f"Loopback favicon origins ready on {ports[0]} and {ports[1]}; passive evidence only", flush=True)
    threading.Event().wait()


if __name__ == "__main__":
    main()
