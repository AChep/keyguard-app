#!/usr/bin/env python3
"""Serve a local HTTPS page with only a one-time code form.

Binds only to loopback with the session's test server certificate, on a
nip.io host name that resolves to 127.0.0.1. Codes are checked offline against
the seed of the main account's seeded item, read from --baseline or captured
from the server at startup. The event journal records the visited host and
path and whether a code was accepted, never codes or seeds. No password fields,
so Safari does not show its password sign-in panel.
"""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from lib import session
from lib.config import PORTS
from servers.autofill_web import (
    ALTERNATE_HOST, add_baseline_argument, add_tls_arguments, load_baseline, otp_matches, otp_seed,
)

OWNER = "keyguard-ios-native-e2e-otp"
ORIGIN = f"https://{ALTERNATE_HOST}:{PORTS['otp-web']}"
ADDITIONAL_ORIGIN = f"https://sub.{ALTERNATE_HOST}:{PORTS['otp-web']}"

PAGE = r'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Keyguard E2E OTP</title>
<style>body{font:18px system-ui;margin:24px;max-width:40rem}
label{display:block;margin-top:24px}input,button{font:inherit;padding:12px;
box-sizing:border-box;width:100%;margin:8px 0}output{display:block;margin-top:24px}</style>
<h1>Keyguard E2E OTP</h1><p>Synthetic one-time code on the local test server.</p>
<form id="otp"><label for="code">Verification code</label>
<input id="code" name="code" inputmode="numeric" autocomplete="one-time-code">
<button>Verify code</button></form><output id="status" aria-live="polite">Ready</output>
<script>document.querySelector('#otp').onsubmit=async event=>{
event.preventDefault();const response=await fetch('/verify',{method:'POST',
headers:{'Content-Type':'application/json'},
body:JSON.stringify({code:document.querySelector('#code').value})});
document.querySelector('#status').textContent=(await response.json()).result;};</script>
</html>'''


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def record(self, **event):
        session.append_jsonl(self.server.evidence / "events.jsonl", {"time": time.time(), **event})

    def reply(self, status, payload, html=False):
        data = payload.encode() if html else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8" if html else "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path in ("/", "/otp"):
            self.record(kind="page", host=self.headers.get("Host", ""), path=self.path)
            self.reply(200, PAGE, html=True)
        elif self.path == "/alive":
            self.reply(200, {"owner": OWNER})
        else:
            self.reply(404, {"error": "Not found"})

    def do_POST(self):
        if self.path != "/verify":
            self.reply(404, {"error": "Not found"})
            return
        if self.headers.get("Origin") not in (ORIGIN, ADDITIONAL_ORIGIN):
            self.reply(403, {"error": "Unexpected origin"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length < 128:
                raise ValueError("Invalid body size")
            value = json.loads(self.rfile.read(length))["code"]
            if not isinstance(value, str) or len(value) != 6 or not value.isascii() or not value.isdigit():
                raise ValueError("Invalid code shape")
        except (KeyError, TypeError, ValueError):
            self.record(kind="verification", accepted=False)
            self.reply(400, {"result": "Verification rejected"})
            return
        accepted = otp_matches(self.server.seed, value)
        self.record(kind="verification", accepted=accepted)
        self.reply(200 if accepted else 400,
                   {"result": "One-time code verified" if accepted else "Verification rejected"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, help="Evidence folder (default: <session>/otp-web)")
    add_tls_arguments(parser)
    add_baseline_argument(parser)
    args = parser.parse_args()
    try:
        seed = otp_seed(load_baseline(args.baseline))
    except ValueError as error:
        parser.error(str(error))
    evidence = args.evidence or session.path("otp-web")
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    evidence.chmod(0o700)
    server = ThreadingHTTPServer(("127.0.0.1", PORTS["otp-web"]), Handler)
    server.evidence = evidence
    server.seed = seed
    session.serve_tls(server, args.certificate, args.private_key)
    print(f"OTP fixture {OWNER} ready at {ORIGIN}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
