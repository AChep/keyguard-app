#!/usr/bin/env python3
"""Serve the download account's server over HTTPS with attachment download faults.

Binds only to loopback with the session's test server certificate and forwards
to the download account's Vaultwarden server. Only the account's one
attachment URL is rewritten through this proxy, and only its blob GET can fail
or pause, as set in <evidence>/control.json on each request. The request
journal omits credentials, bodies, query strings and response bodies.
"""

import argparse
import http.client
import json
import select
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from lib import bitwarden, session
from lib.config import PORTS, account
from servers.sso_tls import HOP_HEADERS

ACCOUNT = account("download")
PORT = PORTS["download-transport"]
EVIDENCE = None  # The evidence folder, set by main().


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def record(self, phase, status=None, mode="healthy", **extra):
        record = {"time": time.time(), "method": self.command,
                  "path": urllib.parse.urlsplit(self.path).path,
                  "phase": phase, "status": status, "mode": mode, **extra}
        session.append_jsonl(EVIDENCE / "requests.jsonl", record)

    def rewrite_url(self, value):
        if not isinstance(value, str):
            return value
        parsed = urllib.parse.urlsplit(value)
        if parsed.hostname in ("127.0.0.1", "localhost") and parsed.path == self.server.blob_path:
            return urllib.parse.urlunsplit(("https", f"127.0.0.1:{PORT}", parsed.path,
                                            parsed.query, parsed.fragment))
        return value

    def rewrite_json(self, value):
        if isinstance(value, dict):
            return {key: self.rewrite_json(item) for key, item in value.items()}
        if isinstance(value, list):
            return [self.rewrite_json(item) for item in value]
        return self.rewrite_url(value)

    def proxy(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/health" and self.command == "GET":
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"ok")
            return
        mode = "healthy"
        if self.command == "GET" and path == self.server.blob_path:
            try:
                mode = json.loads((EVIDENCE / "control.json").read_text()).get("mode", "healthy")
            except (OSError, ValueError):
                pass
            if mode not in ("healthy", "error", "slow"):
                mode = "healthy"
            self.record("blob-start", mode=mode)
            if mode == "error":
                data = b'{"error":"Controlled synthetic download outage"}'
                self.send_response(503)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                self.record("complete", 503, mode)
                return
            if mode == "slow":
                # Long enough for native Cancel, bounded even if the client leaves.
                time.sleep(45)
        websocket = self.headers.get("Upgrade", "").lower() == "websocket"
        headers = {key: value for key, value in self.headers.items() if key.lower() not in HOP_HEADERS}
        headers.update({"Host": f"127.0.0.1:{PORT}", "X-Forwarded-Proto": "https",
                        "Accept-Encoding": "identity"})
        if websocket:
            headers.update({"Connection": "Upgrade", "Upgrade": "websocket"})
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else None
        upstream = http.client.HTTPConnection("127.0.0.1", ACCOUNT.server.port, timeout=30)
        try:
            upstream.request(self.command, self.path, body, headers)
            response = upstream.getresponse()
            upgrading = websocket and response.status == 101
            data = None if upgrading else response.read()
            rewritten = False
            if data is not None and "application/json" in response.getheader("Content-Type", ""):
                try:
                    original = json.loads(data)
                    transformed = self.rewrite_json(original)
                    if transformed != original:
                        data = json.dumps(transformed).encode()
                        rewritten = True
                except ValueError:
                    pass
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in HOP_HEADERS:
                    self.send_header(key, self.rewrite_url(value) if key.lower() == "location" else value)
            if upgrading:
                self.send_header("Connection", "Upgrade")
                self.send_header("Upgrade", "websocket")
                self.end_headers()
                self.wfile.flush()
                self.record("upgrade", 101)
                while True:
                    readable, _, _ = select.select([self.connection, upstream.sock], [], [], 30)
                    for source in readable:
                        chunk = source.recv(65536)
                        if not chunk:
                            return
                        target = upstream.sock if source is self.connection else self.connection
                        target.sendall(chunk)
            else:
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(data)
                self.record("complete", response.status, mode, metadataUrlRewritten=rewritten,
                            responseBytes=len(data))
        except (ConnectionResetError, BrokenPipeError):
            self.record("client-disconnected", mode=mode)
        finally:
            upstream.close()

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = proxy


def attachment_path():
    """Blob path of the download account's one attachment, looked up on the server."""
    attachments = [(item["id"], attachment["id"])
                   for item in bitwarden.Session(ACCOUNT).ciphers() if not item["deletedDate"]
                   for attachment in item["attachments"]]
    if len(attachments) != 1:
        raise SystemExit(f"Expected one attachment for {ACCOUNT.email}, found {len(attachments)}")
    cipher, attachment = attachments[0]
    return f"/attachments/{cipher}/{attachment}"


def main():
    global EVIDENCE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path,
                        help="Evidence and control folder (default: <session>/download-transport)")
    args = parser.parse_args()
    blob_path = attachment_path()
    EVIDENCE = args.evidence or session.path("download-transport")
    EVIDENCE.mkdir(mode=0o700, parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True
    server.blob_path = blob_path
    session.serve_tls(server)
    print(f"Synthetic download transport ready on https://127.0.0.1:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
