#!/usr/bin/env python3
"""Publish local-only server discovery records and a loopback CAPTCHA-contract proxy.

Registers _bitwarden and _vaultwarden TXT records for a synthetic .local domain
on DNSServiceInterfaceIndexLocalOnly: no DNS configuration changes, no LAN
advertisements. A loopback control port switches the record value and the
CAPTCHA gate; a loopback HTTPS gateway forwards to the main server and asks for
a client secret until the expected one arrives. Tests the app's discovery and
client-secret UI, not a real CAPTCHA attestation. Evidence omits passwords,
request bodies, authorization and query strings; WebSockets are not relayed.
Records are removed on exit.
"""

import argparse
import ctypes as c
import json
import os
import queue
import select
import signal
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from lib import session
from lib.config import PORTS, SERVERS, account

# The synthetic account on the main server that this gateway gates.
EMAIL = account("login-branches").email
DOMAIN = EMAIL.split("@", 1)[1]
SECRET = "Client-Secret-E2e-2026"
UPSTREAM = SERVERS["main"].url
GATEWAY = PORTS["login-branch-tls"]
VALUES = {
    "invalid": f"http://127.0.0.1:{GATEWAY}/",
    "foreign": f"https://127.0.0.1:{GATEWAY}/",
    "same": f"https://{DOMAIN}:{GATEWAY}/",
}
PAGE = ("<!doctype html><meta name='viewport' content='width=device-width'>"
        "<title>Local client-secret fixture</title><h1>Local client-secret fixture</h1>"
        "<p>Protocol fixture only; no third-party CAPTCHA is performed.</p>"
        "<p id='secret'>" + SECRET + "</p><button onclick=\"navigator.clipboard.writeText("
        "document.getElementById('secret').textContent)\">Copy fixture secret</button>").encode()


class Evidence:
    def __init__(self):
        directory = session.path("login-branch-web")
        directory.mkdir(mode=0o700, exist_ok=False)
        self.path = directory / "state.json"
        self.lock = threading.RLock()
        self.commands = queue.SimpleQueue()
        self.stop = threading.Event()
        self.data = dict(owner="keyguard-ios-native-e2e-login-branches", domain=DOMAIN,
                         dnsMode="invalid", dnsLocalOnly=True, dnsCallbacks=[],
                         dnsRemoved=False, captcha=False, requests=[])
        self.save()

    def save(self):
        with self.lock:
            temporary = self.path.with_suffix(".tmp")
            session.write_json(temporary, self.data)
            temporary.replace(self.path)

    def record(self, **fields):
        with self.lock:
            self.data["requests"].append(dict(time=time.time(), **fields))
            self.save()


CALLBACK = c.CFUNCTYPE(None, c.c_void_p, c.c_void_p, c.c_uint32, c.c_int32, c.c_void_p)


class LocalRecords:
    def __init__(self, evidence):
        self.evidence = evidence
        self.lib = c.CDLL("/usr/lib/libSystem.B.dylib")
        self.lib.DNSServiceCreateConnection.argtypes = [c.POINTER(c.c_void_p)]
        self.lib.DNSServiceCreateConnection.restype = c.c_int32
        self.lib.DNSServiceRegisterRecord.argtypes = [
            c.c_void_p, c.POINTER(c.c_void_p), c.c_uint32, c.c_uint32, c.c_char_p,
            c.c_uint16, c.c_uint16, c.c_uint16, c.c_void_p, c.c_uint32, CALLBACK, c.c_void_p,
        ]
        self.lib.DNSServiceRegisterRecord.restype = c.c_int32
        self.lib.DNSServiceUpdateRecord.argtypes = [
            c.c_void_p, c.c_void_p, c.c_uint32, c.c_uint16, c.c_void_p, c.c_uint32,
        ]
        self.lib.DNSServiceUpdateRecord.restype = c.c_int32
        self.lib.DNSServiceRefSockFD.argtypes = [c.c_void_p]
        self.lib.DNSServiceRefSockFD.restype = c.c_int
        self.lib.DNSServiceProcessResult.argtypes = [c.c_void_p]
        self.lib.DNSServiceProcessResult.restype = c.c_int32
        self.lib.DNSServiceRefDeallocate.argtypes = [c.c_void_p]
        self.lib.DNSServiceRefDeallocate.restype = None
        self.reference = c.c_void_p()
        assert self.lib.DNSServiceCreateConnection(c.byref(self.reference)) == 0
        self.callback = CALLBACK(self.registered)
        self.records = []
        self.buffers = []
        for index, prefix in enumerate(("_bitwarden", "_vaultwarden")):
            record = c.c_void_p()
            name = prefix + "." + DOMAIN + "."
            data = self.payload("invalid")
            # Unique record, interface LocalOnly, TXT, Internet class, daemon TTL.
            result = self.lib.DNSServiceRegisterRecord(
                self.reference, c.byref(record), 0x20, 0xFFFFFFFF, name.encode(),
                16, 1, len(data.raw) - 1, data, 0, self.callback, index + 1,
            )
            assert result == 0, (name, result)
            self.records.append(record)
        self.fd = self.lib.DNSServiceRefSockFD(self.reference)
        assert self.fd >= 0

    def payload(self, mode):
        value = VALUES[mode].encode("ascii")
        assert len(value) < 256
        buffer = c.create_string_buffer(bytes((len(value),)) + value)
        self.buffers.append(buffer)
        return buffer

    def registered(self, _reference, _record, flags, error, context):
        with self.evidence.lock:
            self.evidence.data["dnsCallbacks"].append(dict(record=int(context), flags=flags, error=error))
            self.evidence.save()

    def set_mode(self, mode):
        assert mode in VALUES
        for record in self.records:
            data = self.payload(mode)
            result = self.lib.DNSServiceUpdateRecord(
                self.reference, record, 0, len(data.raw) - 1, data, 0,
            )
            assert result == 0, result
        with self.evidence.lock:
            self.evidence.data["dnsMode"] = mode
            self.evidence.save()

    def close(self):
        self.lib.DNSServiceRefDeallocate(self.reference)
        with self.evidence.lock:
            self.evidence.data["dnsRemoved"] = True
            self.evidence.save()


class QuietHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, status, data, content_type="application/json"):
        if not isinstance(data, bytes):
            data = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)


class Control(QuietHandler):
    def do_GET(self):
        with self.server.evidence.lock:
            self.reply(200, self.server.evidence.data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        assert body.get("owner") == "keyguard-ios-native-e2e-login-branches"
        if self.path == "/stop":
            self.server.evidence.stop.set()
        else:
            assert set(body) <= {"owner", "dnsMode", "captcha"}
            self.server.evidence.commands.put(body)
        self.reply(202, {"queued": True})


class Gateway(QuietHandler):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def proxy(self):
        route = urllib.parse.urlsplit(self.path).path
        evidence = self.server.evidence
        if self.command == "GET" and route in ("/", "/index.html"):
            evidence.record(method="GET", path=route, status=200, localSecretPage=True)
            return self.reply(200, PAGE, "text/html; charset=utf-8")
        length = int(self.headers.get("Content-Length", "0"))
        assert length <= 8 * 1024 * 1024
        body = self.rfile.read(length) if length else None
        details = {}
        if self.command == "POST" and route.endswith("/connect/token"):
            fields = urllib.parse.parse_qs((body or b"").decode(), keep_blank_values=True)
            if fields.get("grant_type") == ["password"] and fields.get("username") == [EMAIL]:
                secret = fields.get("captchaResponse", [""])[0]
                details = dict(ownedPasswordGrant=True, clientSecretPresent=bool(secret),
                               clientSecretExact=secret == SECRET, secretTransport="captchaResponse")
                with evidence.lock:
                    gate = evidence.data["captcha"]
                if gate and secret != SECRET:
                    evidence.record(method=self.command, path=route, status=400, captchaRequired=True, **details)
                    return self.reply(400, {"error": "invalid_grant", "error_description": "Local CAPTCHA contract fixture",
                                            "HCaptcha_SiteKey": "local-fixture-site-key"})
                # The gateway validates this fixture secret; the real Vaultwarden
                # still authenticates/decrypts the actual synthetic account.
                fields.pop("captchaResponse", None)
                body = urllib.parse.urlencode(fields, doseq=True).encode()
        headers = {key: value for key, value in self.headers.items()
                   if key.lower() not in {"host", "content-length", "connection", "accept-encoding"}}
        request = urllib.request.Request(UPSTREAM + self.path, body, headers, method=self.command)
        try:
            response = self.opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        except urllib.error.URLError:
            evidence.record(method=self.command, path=route, status=503, **details)
            return self.reply(503, {"Message": "Local fixture upstream unavailable"})
        with response:
            payload = response.read()
            status = response.code
            content_type = response.headers.get("Content-Type", "application/json")
        if route == "/api/config" and "json" in content_type:
            # Keep the owned account's metadata endpoints on this loopback gate.
            payload = payload.replace(UPSTREAM.encode(), f"https://127.0.0.1:{GATEWAY}".encode())
        evidence.record(method=self.command, path=route, status=status, **details)
        self.reply(status, payload, content_type)

    do_GET = do_POST = do_PUT = do_DELETE = proxy


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    os.umask(0o077)
    evidence = Evidence()
    records = LocalRecords(evidence)
    servers = []
    try:
        for port, handler in ((PORTS["login-branch-http"], Control), (GATEWAY, Gateway)):
            server = ThreadingHTTPServer(("127.0.0.1", port), handler)
            server.evidence = evidence
            if port == GATEWAY:
                session.serve_tls(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            servers.append(server)
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_args: evidence.stop.set())
        print(f"Local-only DNS records for {DOMAIN}; control on 127.0.0.1:{PORTS['login-branch-http']}, "
              f"gateway on https://127.0.0.1:{GATEWAY}", flush=True)
        while not evidence.stop.is_set():
            while not evidence.commands.empty():
                command = evidence.commands.get_nowait()
                if "dnsMode" in command:
                    records.set_mode(command["dnsMode"])
                if "captcha" in command:
                    with evidence.lock:
                        evidence.data["captcha"] = bool(command["captcha"])
                        evidence.save()
            if select.select([records.fd], [], [], 0.2)[0]:
                assert records.lib.DNSServiceProcessResult(records.reference) == 0
    finally:
        records.close()
        for server in servers:
            server.shutdown()
            server.server_close()
        print("Owned DNS records removed; both fixture listeners closed", flush=True)


if __name__ == "__main__":
    main()
