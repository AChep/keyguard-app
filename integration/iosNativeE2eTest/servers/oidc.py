#!/usr/bin/env python3
"""Serve a synthetic OpenID Connect provider for the SSO server's sign-in.

Binds only to loopback and signs in only the fixed synthetic SSO account, after
an Approve or Deny tap. Never use it with personal or production accounts. The
issuer uses host.docker.internal so the Vaultwarden container can reach it;
only the browser authorization endpoint uses the host loopback URL. Events
omit codes and tokens; the signing key stays in <session>/oidc.
"""

import argparse
import base64
import hashlib
import html
import json
import os
import secrets
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from Cryptodome.Hash import SHA256
from Cryptodome.PublicKey import RSA
from Cryptodome.Signature import pkcs1_15

from lib import session
from lib.config import PORTS, SERVERS, account

SSO = SERVERS["sso"]
ISSUER = SSO.env["SSO_AUTHORITY"]
PUBLIC = f"http://127.0.0.1:{PORTS['oidc']}"
REDIRECT = SSO.public + "/identity/connect/oidc-signin"
CLIENT = SSO.env["SSO_CLIENT_ID"]
CLIENT_SECRET = SSO.env["SSO_CLIENT_SECRET"]
EMAIL = account("sso").email
IDENTITY = {"sub": "keyguard-native-e2e-sso", "email": EMAIL, "email_verified": True,
            "name": account("sso").name, "preferred_username": EMAIL.split("@")[0]}
PENDING = {}
CODES = {}
ACCESS = {}
REFRESH = {}
SIGNING_KEY = None


def encoded(data):
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def number(value):
    return encoded(value.to_bytes((value.bit_length() + 7) // 8, "big"))


def jwt(claims):
    header = encoded(json.dumps({"alg": "RS256", "typ": "JWT", "kid": "native-e2e"}).encode())
    payload = encoded(json.dumps(claims, separators=(",", ":")).encode())
    message = (header + "." + payload).encode()
    signature = pkcs1_15.new(SIGNING_KEY).sign(SHA256.new(message))
    return message.decode() + "." + encoded(signature)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def event(self, name, **fields):
        session.append_jsonl(session.path("oidc", "events.jsonl"),
                             {"time": time.time(), "event": name, **fields})

    def reply(self, status, body, content_type="application/json"):
        data = json.dumps(body).encode() if content_type == "application/json" else body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def redirect(self, values):
        self.send_response(302)
        self.send_header("Location", REDIRECT + "?" + urllib.parse.urlencode(values))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/health":
            self.reply(200, {"synthetic": True, "identity": EMAIL})
        elif parsed.path == "/.well-known/openid-configuration":
            self.event("discovery")
            self.reply(200, {
                "issuer": ISSUER, "authorization_endpoint": PUBLIC + "/authorize",
                "token_endpoint": ISSUER + "/token", "userinfo_endpoint": ISSUER + "/userinfo",
                "jwks_uri": ISSUER + "/jwks", "response_types_supported": ["code"],
                "subject_types_supported": ["public"], "id_token_signing_alg_values_supported": ["RS256"],
                "scopes_supported": ["openid", "email", "profile", "offline_access"],
                "token_endpoint_auth_methods_supported": ["client_secret_basic"],
                "grant_types_supported": ["authorization_code", "refresh_token"],
                "code_challenge_methods_supported": ["S256"],
            })
        elif parsed.path == "/jwks":
            self.event("jwks")
            self.reply(200, {"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": "native-e2e",
                                        "n": number(SIGNING_KEY.n), "e": number(SIGNING_KEY.e)}]})
        elif parsed.path == "/userinfo":
            token = self.headers.get("Authorization", "").removeprefix("Bearer ")
            if token not in ACCESS:
                self.reply(401, {"error": "invalid_token"})
                return
            self.event("userinfo")
            self.reply(200, IDENTITY)
        elif parsed.path == "/authorize":
            params = urllib.parse.parse_qs(parsed.query)
            required = ("state", "nonce", "code_challenge")
            valid = all(len(params.get(k, [])) == 1 and params[k][0] for k in required)
            valid = valid and params.get("client_id") == [CLIENT] and params.get("redirect_uri") == [REDIRECT]
            valid = valid and params.get("response_type") == ["code"]
            valid = valid and params.get("code_challenge_method") == ["S256"]
            if not valid:
                self.event("authorize_rejected")
                self.reply(400, {"error": "invalid_request"})
                return
            request_id = secrets.token_urlsafe(24)
            PENDING[request_id] = {k: v[0] for k, v in params.items()}
            PENDING[request_id]["expires"] = time.time() + 300
            self.event("authorize_form")
            self.reply(200, f"""<!doctype html><html lang="en"><head>
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Keyguard E2E Identity Provider</title>
</head><body style="font:18px system-ui;padding:24px"><h1>Keyguard E2E Identity Provider</h1>
<p>Synthetic account: {html.escape(EMAIL)}</p><form method="post" action="/authorize">
<input type="hidden" name="request" value="{request_id}">
<button name="decision" value="approve" style="padding:16px">Approve test sign-in</button>
<button name="decision" value="deny" style="padding:16px;margin-top:16px">Deny test sign-in</button>
</form></body></html>""", "text/html; charset=utf-8")
        else:
            self.reply(404, {"error": "not_found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 8192:
            self.reply(413, {"error": "request_too_large"})
            return
        params = urllib.parse.parse_qs(self.rfile.read(length).decode())
        field = lambda key: params.get(key, [""])[0]
        if self.path == "/authorize":
            pending = PENDING.pop(field("request"), None)
            if not pending or pending["expires"] < time.time():
                self.reply(400, {"error": "expired_request"})
                return
            if field("decision") != "approve":
                self.event("authorization_denied")
                self.redirect({"error": "access_denied", "error_description": "Synthetic user denied sign-in",
                               "state": pending["state"]})
                return
            code = secrets.token_urlsafe(32)
            CODES[code] = pending
            self.event("authorization_approved")
            self.redirect({"code": code, "state": pending["state"]})
        elif self.path == "/token":
            authorization = self.headers.get("Authorization", "")
            try:
                raw = base64.b64decode(authorization.removeprefix("Basic "), validate=True).decode()
                client, _, secret = raw.partition(":")
                valid_client = authorization.startswith("Basic ") and secrets.compare_digest(
                    urllib.parse.unquote_plus(client), CLIENT,
                ) and secrets.compare_digest(urllib.parse.unquote_plus(secret), CLIENT_SECRET)
            except (ValueError, UnicodeDecodeError):
                valid_client = False
            if not valid_client:
                self.event("client_rejected")
                self.reply(401, {"error": "invalid_client"})
                return
            if field("grant_type") == "authorization_code":
                pending = CODES.pop(field("code"), None)
                valid = pending is not None and pending["expires"] >= time.time()
                valid = valid and field("redirect_uri") == REDIRECT
                challenge = encoded(hashlib.sha256(field("code_verifier").encode()).digest())
                valid = valid and secrets.compare_digest(challenge, pending["code_challenge"])
                if not valid:
                    self.event("code_rejected")
                    self.reply(400, {"error": "invalid_grant"})
                    return
            elif field("grant_type") == "refresh_token":
                pending = REFRESH.pop(field("refresh_token"), None)
                if pending is None:
                    self.reply(400, {"error": "invalid_grant"})
                    return
            else:
                self.reply(400, {"error": "unsupported_grant_type"})
                return
            access, refresh = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
            ACCESS[access] = True
            REFRESH[refresh] = pending
            now = int(time.time())
            claims = {**IDENTITY, "iss": ISSUER, "aud": CLIENT, "iat": now, "exp": now + 900,
                      "auth_time": now, "nonce": pending["nonce"],
                      "at_hash": encoded(hashlib.sha256(access.encode()).digest()[:16])}
            self.event("token_issued", grant=field("grant_type"))
            self.reply(200, {"access_token": access, "token_type": "Bearer", "expires_in": 900,
                             "refresh_token": refresh, "scope": "openid email profile",
                             "id_token": jwt(claims)})
        else:
            self.reply(404, {"error": "not_found"})


def main():
    global SIGNING_KEY
    argparse.ArgumentParser(description=__doc__).parse_args()
    key_path = session.path("oidc", "oidc-signing-key.pem")
    if not key_path.exists():
        with os.fdopen(os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
            output.write(RSA.generate(2048).export_key())
    SIGNING_KEY = RSA.import_key(key_path.read_bytes())
    print(f"Synthetic OIDC provider ready on loopback port {PORTS['oidc']}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORTS["oidc"]), Handler).serve_forever()


if __name__ == "__main__":
    main()
