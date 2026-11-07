#!/usr/bin/env python3
"""Serve a local SimpleLogin-compatible API for email-forwarder tests.

Binds only to loopback. Generates ios-relay-NNNN@keyguard.test strings; sends
no mail and makes no outbound requests. A control endpoint switches failure
modes. The request journal omits API keys.
"""

import argparse
import json
import socket
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from lib import session
from lib.config import PORTS

ORIGIN = f"http://127.0.0.1:{PORTS['simplelogin']}"
API_KEY = "SimpleLogin-E2e-Token-2026!"
CONTROL_KEY = "Relay-Control-E2e-2026!"
OWNER = "keyguard-ios-native-e2e-simplelogin"
MODES = ("normal", "unavailable", "missing_alias", "disconnect")


class Fixture:
    def __init__(self, evidence):
        self.evidence = evidence
        evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
        evidence.chmod(0o700)
        self.path = evidence / "state.json"
        self.state = {"owner": OWNER, "mode": "normal", "aliases": [], "requests": 0}
        if self.path.exists():
            self.state = session.read_json(self.path)
            if self.state.get("owner") != OWNER:
                raise ValueError("Refusing a foreign fixture state")
        self.save()

    def save(self):
        temporary = self.path.with_suffix(".new")
        session.write_json(temporary, self.state)
        temporary.replace(self.path)

    def record(self, **event):
        session.append_jsonl(self.evidence / "requests.jsonl", {"time": time.time(), **event})


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def control_allowed(self):
        return self.headers.get("X-Keyguard-Fixture-Key") == CONTROL_KEY

    def do_GET(self):
        if self.path == "/alive":
            self.reply(200, {"owner": OWNER})
        elif self.path == "/fixture/state" and self.control_allowed():
            self.reply(200, self.server.fixture.state)
        else:
            self.reply(404, {"error": "Not found"})

    def do_POST(self):
        fixture = self.server.fixture
        if self.path == "/fixture/control" and self.control_allowed():
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length < 1024:
                self.reply(400, {"error": "Invalid control body"})
                return
            try:
                mode = json.loads(self.rfile.read(length))["mode"]
                if mode not in MODES:
                    raise ValueError("Unknown mode")
            except (KeyError, ValueError, TypeError):
                self.reply(400, {"error": "Invalid mode"})
                return
            fixture.state["mode"] = mode
            fixture.save()
            fixture.record(kind="control", mode=mode)
            self.reply(200, {"mode": mode})
            return
        parsed = urlparse(self.path)
        if parsed.path != "/api/alias/random/new":
            self.reply(404, {"error": "Not found"})
            return
        authenticated = self.headers.get("Authentication") == API_KEY
        fixture.state["requests"] += 1
        fixture.save()
        fixture.record(
            kind="generate", authenticated=authenticated, mode=fixture.state["mode"],
            query=parse_qs(parsed.query),
        )
        if not authenticated:
            self.reply(401, {"error": "Wrong API key (local fixture)"})
        elif fixture.state["mode"] == "unavailable":
            self.reply(503, {"error": "Local fixture unavailable"})
        elif fixture.state["mode"] == "missing_alias":
            self.reply(200, {"id": 0})
        elif fixture.state["mode"] == "disconnect":
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.connection.close()
        else:
            identifier = len(fixture.state["aliases"]) + 1
            alias = f"ios-relay-{identifier:04d}@keyguard.test"
            fixture.state["aliases"].append(alias)
            fixture.save()
            self.reply(201, {"id": identifier, "alias": alias})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, help="Evidence folder (default: <session>/simplelogin)")
    args = parser.parse_args()
    server = HTTPServer(("127.0.0.1", PORTS["simplelogin"]), Handler)
    server.fixture = Fixture(args.evidence or session.path("simplelogin"))
    print(f"Local SimpleLogin fixture ready at {ORIGIN}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
