#!/usr/bin/env python3
"""Serve the SSO Vaultwarden server over HTTPS on its public loopback URL.

Forwards to the server's loopback HTTP port with the session's test server
certificate; also fronts other loopback servers with --port/--backend-port.
WebSocket upgrades are relayed as opaque bytes. The request journal omits
bodies and queries. Optional checks: a client certificate from the test CA,
a required header, and sync faults for the synthetic SSO account read from
<evidence>/sync-fault.json.
"""

import argparse
import hashlib
import http.client
import json
import select
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from lib import session
from lib.config import SERVERS, account

SSO = SERVERS["sso"]
EVIDENCE = None  # The evidence folder, set by main().
HOP_HEADERS = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
               "te", "trailer", "transfer-encoding", "upgrade", "content-length"}


def sync_fault(path, data):
    """Alter only explicitly selected trust markers for the fixed synthetic user."""
    if urllib.parse.urlparse(path).path != "/api/sync":
        return data, []
    try:
        fault = json.loads((EVIDENCE / "sync-fault.json").read_text())
        response = json.loads(data)
    except (OSError, ValueError):
        return data, []
    profile = response.get("profile", {})
    if profile.get("email") != account("sso").email:
        return data, []
    fields = []
    for key in ("securityStamp", "id"):
        value = fault.get(key)
        if isinstance(value, str) and value:
            profile[key] = value
            fields.append(key)
    return (json.dumps(response).encode(), fields) if fields else (data, [])


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def handle(self):
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            # Browser cancellation closes keep-alive connections abruptly.
            pass

    def record(self, status, fault_fields=None, header_matched=True):
        peer = self.connection.getpeercert(binary_form=True)
        session.append_jsonl(EVIDENCE / "tls-requests.jsonl", {
            "method": self.command,
            "path": urllib.parse.urlparse(self.path).path,
            "status": status,
            "faultFields": fault_fields or [],
            "clientCertificateSha256": hashlib.sha256(peer).hexdigest() if peer else None,
            "requiredHeaderMatched": header_matched,
        })

    def proxy(self):
        required = self.server.required_header
        if required is not None and self.headers.get(required[0]) != required[1]:
            data = b'{"error":"Synthetic test header required"}'
            self.send_response(403)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(data)
            self.close_connection = True
            self.record(403, header_matched=False)
            return
        websocket = self.headers.get("Upgrade", "").lower() == "websocket"
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS}
        headers.update({"Host": f"127.0.0.1:{self.server.server_port}", "X-Forwarded-Proto": "https"})
        headers["Accept-Encoding"] = "identity"
        if websocket:
            headers.update({"Connection": "Upgrade", "Upgrade": "websocket"})
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else None
        upstream = http.client.HTTPConnection("127.0.0.1", self.server.backend_port, timeout=30)
        try:
            upstream.request(self.command, self.path, body, headers)
            response = upstream.getresponse()
            upgrading = websocket and response.status == 101
            data = None if upgrading else response.read()
            fault_fields = []
            if data is not None and response.status == 200:
                data, fault_fields = sync_fault(self.path, data)
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in HOP_HEADERS and not (fault_fields and key.lower() == "content-encoding"):
                    self.send_header(key, value)
            if upgrading:
                self.send_header("Connection", "Upgrade")
                self.send_header("Upgrade", "websocket")
                self.end_headers()
                self.wfile.flush()
                self.record(response.status)
                sockets = [self.connection, upstream.sock]
                while True:
                    readable, _, _ = select.select(sockets, [], [], 60)
                    if not readable:
                        continue
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        target = upstream.sock if source is self.connection else self.connection
                        target.sendall(data)
            else:
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(data)
                self.record(response.status, fault_fields)
        finally:
            upstream.close()

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = proxy


def main():
    global EVIDENCE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=urllib.parse.urlsplit(SSO.public).port,
                        help="HTTPS loopback port (default: the SSO server's public port)")
    parser.add_argument("--backend-port", type=int, default=SSO.port,
                        help="Loopback HTTP port to forward to (default: the SSO server)")
    parser.add_argument("--evidence", type=Path, help="Evidence folder (default: <session>/sso-tls)")
    parser.add_argument("--require-client-cert", action="store_true",
                        help="Require a client certificate issued by the session's test CA")
    parser.add_argument("--required-header", help="Require NAME=VALUE before forwarding")
    args = parser.parse_args()
    required_header = None
    if args.required_header:
        name, separator, value = args.required_header.partition("=")
        if not separator or not name or any(c.isspace() or c == ":" for c in name) or any(
            c in value for c in "\r\n"
        ):
            parser.error("--required-header must be a valid NAME=VALUE")
        required_header = (name, value)
    EVIDENCE = args.evidence or session.path("sso-tls")
    EVIDENCE.mkdir(mode=0o700, parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.backend_port = args.backend_port
    server.required_header = required_header
    session.serve_tls(server, require_client_cert=args.require_client_cert)
    print(f"TLS proxy ready on loopback port {args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
