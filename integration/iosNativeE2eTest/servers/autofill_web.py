#!/usr/bin/env python3
"""Serve a local HTTPS login, one-time code and passkey page for AutoFill tests.

Binds only to loopback with the session's test server certificate; the
simulator must trust the test CA. Passwords and codes are checked against the
main account's synthetic items, read from --baseline or captured from the
server at startup. WebAuthn checks follow
https://www.w3.org/TR/webauthn-3/#sctn-rp-operations. The fixture never reads
Keyguard's private passkey material, and events omit passwords and codes.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from lib import bitwarden, session
from lib.config import BASELINE_LOGIN, PORTS, account
from oracles.otp import code

ORIGIN = f"https://localhost:{PORTS['autofill-web']}"
ALTERNATE_HOST = "keyguard-native-e2e.127.0.0.1.nip.io"
ALTERNATE_ORIGIN = f"https://{ALTERNATE_HOST}:{PORTS['autofill-web']}"
USER = b"keyguard-native-e2e-autofill-user"
# Seeded item of the main account whose password and one-time code the pages accept.
OTP_ITEM = "Example Site 1"
CHALLENGES = {}
CREDENTIALS = {}
BASELINE = []
_evidence = None

PAGE = r'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Keyguard E2E AutoFill</title>
<style>body{font:18px system-ui;margin:24px;max-width:40rem}label{display:block;margin-top:16px}input,button,select{font:inherit;padding:12px;box-sizing:border-box;width:100%;margin:4px 0}output{display:block;margin:20px 0;white-space:pre-wrap}</style>
<h1>Keyguard E2E AutoFill</h1><p>Synthetic credentials on the local test server.</p>
<form id="login"><label for="username">Username</label><input id="username" name="username" autocomplete="username">
<label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password">
<button>Submit login</button></form>
<label for="uv">Passkey verification</label><select id="uv"><option value="preferred">Preferred</option><option value="required">Required</option></select>
<button id="register">Register passkey</button><button id="assert">Sign in with passkey</button>
<form id="otp"><label for="code">Verification code</label><input id="code" name="code" inputmode="numeric" autocomplete="one-time-code">
<button>Verify code</button></form>
<output id="status" aria-live="polite">Ready</output>
<script>
const status=document.querySelector('#status');
const encode=b=>btoa(String.fromCharCode(...new Uint8Array(b))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
const decode=s=>Uint8Array.from(atob(s.replaceAll('-','+').replaceAll('_','/')),c=>c.charCodeAt(0));
async function post(path,body){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const result=await r.json();if(!r.ok)throw Error(result.error);status.textContent=result.status;}
document.querySelector('#login').onsubmit=async e=>{e.preventDefault();try{await post('/login',{username:document.querySelector('#username').value,password:document.querySelector('#password').value});}catch(e){status.textContent=e.name+': '+e.message;}};
document.querySelector('#otp').onsubmit=async e=>{e.preventDefault();try{await post('/otp',{code:document.querySelector('#code').value});}catch(e){status.textContent=e.name+': '+e.message;}};
async function passkey(kind){try{status.textContent='Requesting '+kind;const uv=document.querySelector('#uv').value;const options=await(await fetch('/options/'+kind+'?uv='+uv)).json();options.challenge=decode(options.challenge);if(kind==='register')options.user.id=decode(options.user.id);else options.allowCredentials=options.allowCredentials.map(c=>({...c,id:decode(c.id)}));const credential=await navigator.credentials[kind==='register'?'create':'get']({publicKey:options});const response=credential.response;const body={id:credential.id,rawId:encode(credential.rawId),type:credential.type,clientDataJSON:encode(response.clientDataJSON)};for(const field of ['attestationObject','authenticatorData','signature','userHandle'])if(response[field])body[field]=encode(response[field]);await post('/result/'+kind,body);}catch(e){status.textContent=e.name+': '+e.message;}}
document.querySelector('#register').onclick=()=>passkey('register');document.querySelector('#assert').onclick=()=>passkey('assert');
if(location.hostname!=='localhost'){for(const id of ['register','assert','uv'])document.getElementById(id).style.display='none';document.querySelector('label[for="uv"]').style.display='none';}
</script></html>'''


def encode(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def decode(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def cbor(data, offset=0, depth=0):
    """The definite-length CBOR subset used by none attestation and COSE EC2."""
    assert depth < 12 and offset < len(data)
    first = data[offset]
    offset += 1
    major, size = first >> 5, first & 31
    if size >= 24:
        width = {24: 1, 25: 2, 26: 4, 27: 8}[size]
        assert offset + width <= len(data)
        size = int.from_bytes(data[offset:offset + width], "big")
        offset += width
    if major in (0, 1):
        return (size if major == 0 else -1 - size), offset
    if major in (2, 3):
        assert offset + size <= len(data)
        value = data[offset:offset + size]
        return (value if major == 2 else value.decode()), offset + size
    if major == 4:
        values = []
        for _ in range(size):
            value, offset = cbor(data, offset, depth + 1)
            values.append(value)
        return values, offset
    if major == 5:
        values = {}
        for _ in range(size):
            key, offset = cbor(data, offset, depth + 1)
            value, offset = cbor(data, offset, depth + 1)
            assert key not in values
            values[key] = value
        return values, offset
    raise ValueError("Unsupported CBOR value")


def evidence():
    """The evidence folder: set by serve(), or <session>/autofill-web."""
    global _evidence
    if _evidence is None:
        _evidence = session.path("autofill-web")
        _evidence.mkdir(mode=0o700, exist_ok=True)
    return _evidence


def __getattr__(name):
    # `from servers.autofill_web import EVIDENCE` resolves lazily, so that
    # importing this module does not need a session.
    if name == "EVIDENCE":
        return evidence()
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def event(kind, **values):
    session.append_jsonl(evidence() / "events.jsonl", dict(time=time.time(), kind=kind, **values))


def load_baseline(path=None):
    """Live decrypted items of the main account, from a snapshot file or the server."""
    items = session.read_json(path) if path else bitwarden.snapshot(account("main"))
    return [item for item in items if not item.get("deletedDate")]


def otp_seed(items):
    matches = [item for item in items if item["name"] == OTP_ITEM]
    if len(matches) != 1 or not matches[0].get("totp"):
        raise ValueError(f"The baseline needs one {OTP_ITEM!r} item with a one-time code")
    return matches[0]["totp"]


def otp_matches(seed, value):
    """Accept the current thirty-second step and its two neighbors."""
    now = time.time()
    return any(hmac.compare_digest(value, code(seed, now + offset)) for offset in (-30, 0, 30))


def verify_otp(value):
    assert isinstance(value, str) and otp_matches(otp_seed(BASELINE), value)


def save_credentials():
    session.write_json(evidence() / "credentials.json", CREDENTIALS)


def verify(kind, body):
    client_bytes = decode(body["clientDataJSON"])
    client = json.loads(client_bytes)
    challenge = CHALLENGES.pop(client["challenge"])
    assert challenge["kind"] == kind and time.time() < challenge["expires"]
    assert client["type"] == ("webauthn.create" if kind == "register" else "webauthn.get")
    assert client["origin"] == ORIGIN and not client.get("crossOrigin", False)
    assert body["type"] == "public-key" and decode(body["id"]) == decode(body["rawId"])
    if kind == "register":
        attestation_bytes = decode(body["attestationObject"])
        attestation, end = cbor(attestation_bytes)
        assert end == len(attestation_bytes) and attestation["fmt"] == "none" and attestation["attStmt"] == {}
        auth = attestation["authData"]
    else:
        auth = decode(body["authenticatorData"])
    assert len(auth) >= 37 and auth[:32] == hashlib.sha256(b"localhost").digest()
    flags, counter = auth[32], int.from_bytes(auth[33:37], "big")
    assert flags & 1  # User presence.
    assert challenge["uv"] != "required" or flags & 4
    assert not flags & 16 or flags & 8  # Backed up implies backup eligible.
    if kind == "register":
        assert flags & 64 and len(auth) >= 55
        length = int.from_bytes(auth[53:55], "big")
        identifier = auth[55:55 + length]
        assert identifier == decode(body["rawId"]) and length > 0
        key, _ = cbor(auth, 55 + length)
        assert key[1] == 2 and key[3] == -7 and key[-1] == 1
        assert len(key[-2]) == len(key[-3]) == 32
        public_der = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004") + key[-2] + key[-3]
        subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-noout"], input=public_der, capture_output=True, check=True)
        CREDENTIALS[body["rawId"]] = dict(publicKey=encode(public_der), counter=counter)
        save_credentials()
    else:
        stored = CREDENTIALS[body["rawId"]]
        assert not body.get("userHandle") or decode(body["userHandle"]) == USER
        assert counter == stored["counter"] == 0 or counter > stored["counter"]
        with tempfile.TemporaryDirectory(prefix="verify-", dir=evidence()) as directory:
            directory = Path(directory)
            public = directory / "public.der"
            signature = directory / "signature.der"
            public.write_bytes(decode(stored["publicKey"]))
            signature.write_bytes(decode(body["signature"]))
            subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public), "-keyform", "DER", "-signature", str(signature)], input=auth + hashlib.sha256(client_bytes).digest(), capture_output=True, check=True)
        stored["counter"] = counter
        save_credentials()
    event(kind + "Verified", credentialId=body["rawId"], userPresent=True,
          userVerified=bool(flags & 4), counter=counter, originAndChallengeVerified=True)
    return "Passkey registration verified" if kind == "register" else "Passkey assertion verified"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, value, status=200, html=False):
        data = value.encode() if html else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8" if html else "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/", "/login"):
            self.reply(PAGE, html=True)
        elif self.path.startswith("/options/"):
            kind = self.path.split("/", 2)[2].split("?")[0]
            if kind not in ("register", "assert"):
                self.reply(dict(error="Unknown operation"), 404)
                return
            uv = "required" if self.path.endswith("?uv=required") else "preferred"
            challenge = encode(secrets.token_bytes(32))
            CHALLENGES[challenge] = dict(kind=kind, uv=uv, expires=time.time() + 300)
            options = dict(challenge=challenge, timeout=120000)
            if kind == "register":
                options.update(rp=dict(id="localhost", name="Keyguard local E2E"),
                               user=dict(id=encode(USER), name="ios-autofill@keyguard.test", displayName="iOS E2E AutoFill"),
                               pubKeyCredParams=[dict(type="public-key", alg=-7)], attestation="none",
                               authenticatorSelection=dict(residentKey="required", userVerification=uv))
            else:
                options.update(rpId="localhost", userVerification=uv,
                               allowCredentials=[dict(type="public-key", id=identifier) for identifier in CREDENTIALS])
            event(kind + "Requested", verification=uv)
            self.reply(options)
        else:
            self.reply(dict(error="Not found"), 404)

    def do_POST(self):
        try:
            assert self.headers.get("Origin") in (ORIGIN, ALTERNATE_ORIGIN)
            length = int(self.headers["Content-Length"])
            assert 0 < length < 256 * 1024
            body = json.loads(self.rfile.read(length))
            if self.path == "/login":
                expected = next((item for item in BASELINE
                                 if item["name"] in (BASELINE_LOGIN["name"], OTP_ITEM)
                                 and body["username"] == item["username"]
                                 and body["password"] == item["password"]), None)
                assert expected is not None
                event("passwordLoginVerified", source="Baseline synthetic " + expected["name"])
                result = "Password login verified"
            elif self.path == "/otp":
                verify_otp(body["code"])
                event("oneTimeCodeVerified", source="Baseline synthetic " + OTP_ITEM, toleranceSteps=1)
                result = "One-time code verified"
            elif self.path in ("/result/register", "/result/assert"):
                result = verify(self.path.rsplit("/", 1)[1], body)
            else:
                raise ValueError("Unknown endpoint")
            self.reply(dict(status=result))
        except (AssertionError, KeyError, ValueError, subprocess.CalledProcessError) as error:
            event("verificationRejected", path=self.path, errorType=type(error).__name__)
            self.reply(dict(error="Fixture verification rejected the response"), 400)


def add_tls_arguments(parser):
    parser.add_argument("--certificate", type=Path,
                        help="Server certificate (default: the session's tls/server.pem)")
    parser.add_argument("--private-key", type=Path,
                        help="Server private key (default: the session's tls/server.key)")


def add_baseline_argument(parser):
    parser.add_argument("--baseline", type=Path,
                        help="JSON list of the main account's decrypted items, as written by "
                             "lib.bitwarden.snapshot (default: capture from the server at startup)")


def serve(handler, port, arguments, directory, title):
    """Serve `handler` on loopback HTTPS, keeping evidence and credentials in `directory`."""
    global _evidence
    os.umask(0o077)
    _evidence = Path(directory)
    _evidence.mkdir(mode=0o700, parents=True, exist_ok=True)
    saved = _evidence / "credentials.json"
    if saved.exists():
        CREDENTIALS.update(session.read_json(saved))
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    session.serve_tls(server, arguments.certificate, arguments.private_key)
    print(f"{title} ready at {ORIGIN}", flush=True)
    server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_tls_arguments(parser)
    add_baseline_argument(parser)
    arguments = parser.parse_args()
    BASELINE[:] = load_baseline(arguments.baseline)
    try:
        otp_seed(BASELINE)
    except ValueError as error:
        parser.error(str(error))
    serve(Handler, PORTS["autofill-web"], arguments, evidence(), "AutoFill fixture")


if __name__ == "__main__":
    main()
