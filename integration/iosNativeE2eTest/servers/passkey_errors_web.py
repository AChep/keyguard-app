#!/usr/bin/env python3
"""Serve a local HTTPS relying party for passkey error cases.

A separate process from `e2e autofill_web`, with its own synthetic user and
credential store, reusing its verifier. Covers registration, exclusion of the
owned credential, an unsupported algorithm, known and unknown credential
assertions, and discovery without an allow list. Negative ceremonies cannot
persist a credential response; client errors record only the mode and DOM
error name.
"""

import argparse
import json
import secrets
import time
from pathlib import Path

from lib import session
from lib.config import PORTS
from servers import autofill_web as base


PAGE = r'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Keyguard E2E Passkey Errors</title>
<style>body{font:18px system-ui;margin:24px;max-width:40rem}button{display:block;font:inherit;padding:12px;width:100%;margin:12px 0}output{display:block;white-space:pre-wrap}</style>
<h1>Keyguard E2E Passkey Errors</h1><p>Separate synthetic account on the local test server.</p>
<button data-mode="register">Register owned passkey</button>
<button data-mode="exclude">Register excluding owned passkey</button>
<button data-mode="unsupported">Register with unsupported algorithm</button>
<button data-mode="assert">Sign in with owned passkey</button>
<button data-mode="unknown">Sign in with unknown credential</button>
<button data-mode="discoverable">Sign in without an allow list</button>
<output id="request" aria-live="polite"></output><output id="status" aria-live="polite">Ready</output>
<script>
const status=document.querySelector('#status');
const encode=b=>btoa(String.fromCharCode(...new Uint8Array(b))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
const decode=s=>Uint8Array.from(atob(s.replaceAll('-','+').replaceAll('_','/')),c=>c.charCodeAt(0));
async function run(mode){
 try{
  status.textContent='Requesting '+mode;
  const result=await fetch('/options/'+mode),options=await result.json();
  if(!result.ok)throw Error(options.error);
  options.challenge=decode(options.challenge);
  const register=['register','exclude','unsupported'].includes(mode);
  document.querySelector('#request').textContent=register?'Requested algorithms: '+options.pubKeyCredParams.map(p=>p.alg).join(', '):'Requested allow-list count: '+options.allowCredentials.length;
  if(register){options.user.id=decode(options.user.id);options.excludeCredentials=options.excludeCredentials.map(c=>({...c,id:decode(c.id)}));}
  else options.allowCredentials=options.allowCredentials.map(c=>({...c,id:decode(c.id)}));
  const credential=await navigator.credentials[register?'create':'get']({publicKey:options});
  const response=credential.response,body={id:credential.id,rawId:encode(credential.rawId),type:credential.type,clientDataJSON:encode(response.clientDataJSON)};
  for(const field of ['attestationObject','authenticatorData','signature','userHandle'])if(response[field])body[field]=encode(response[field]);
  const verified=await fetch('/result/'+(register?'register':'assert'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  const outcome=await verified.json();status.textContent=verified.ok?outcome.status:outcome.error;
 }catch(error){status.textContent=mode+': '+error.name+': '+error.message;await fetch('/client-error',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mode,errorName:error.name})});}
}
for(const button of document.querySelectorAll('button'))button.onclick=()=>run(button.dataset.mode);
</script></html>'''

MODES = {"register", "exclude", "unsupported", "assert", "unknown", "discoverable"}
original_verify = base.verify


def verify(kind, body):
    client = json.loads(base.decode(body["clientDataJSON"]))
    requested = base.CHALLENGES[client["challenge"]]
    # These ceremonies must not produce a credential response. Check before the
    # shared verifier can persist a new key or advance an assertion counter.
    if requested["mode"] in {"exclude", "unsupported", "unknown"}:
        diagnostic = dict(mode=requested["mode"], credentialId=body["rawId"])
        if kind == "register" and body.get("attestationObject"):
            attestation, _ = base.cbor(base.decode(body["attestationObject"]))
            auth = attestation["authData"]
            length = int.from_bytes(auth[53:55], "big")
            public, _ = base.cbor(auth, 55 + length)
            diagnostic["returnedCoseAlgorithm"] = public[3]
        base.event("unexpectedNegativeResponse", **diagnostic)
        raise AssertionError("Negative ceremony returned a credential")
    return original_verify(kind, body)


class Handler(base.Handler):
    def do_GET(self):
        mode = self.path.removeprefix("/options/")
        if self.path == "/":
            self.reply(PAGE, html=True)
            return
        if self.path == "/health":
            self.reply(dict(owner="keyguard-ios-native-e2e-passkey-errors", credentials=len(base.CREDENTIALS)))
            return
        if not self.path.startswith("/options/") or mode not in MODES:
            self.reply(dict(error="Not found"), 404)
            return
        if mode in {"exclude", "assert", "discoverable"} and not base.CREDENTIALS:
            self.reply(dict(error="Register the owned passkey first"), 409)
            return
        kind = "register" if mode in {"register", "exclude", "unsupported"} else "assert"
        challenge = base.encode(secrets.token_bytes(32))
        base.CHALLENGES[challenge] = dict(kind=kind, mode=mode, uv="required", expires=time.time() + 300)
        options = dict(challenge=challenge, timeout=45000)
        identifiers = [dict(type="public-key", id=identifier) for identifier in base.CREDENTIALS]
        if kind == "register":
            options.update(
                rp=dict(id="localhost", name="Keyguard local error E2E"),
                user=dict(id=base.encode(base.USER), name="ios-passkey-errors@keyguard.test", displayName="iPhone E2E Passkey Errors"),
                pubKeyCredParams=[dict(type="public-key", alg=-257 if mode == "unsupported" else -7)],
                excludeCredentials=identifiers if mode == "exclude" else [],
                attestation="none",
                authenticatorSelection=dict(residentKey="required", userVerification="required"),
            )
        else:
            allowed = [] if mode == "discoverable" else identifiers
            if mode == "unknown":
                allowed = [dict(type="public-key", id=base.encode(secrets.token_bytes(32)))]
            options.update(rpId="localhost", userVerification="required", allowCredentials=allowed)
        base.event("optionsRequested", mode=mode, excludedCount=len(options.get("excludeCredentials", [])),
                   allowedCount=len(options.get("allowCredentials", [])))
        self.reply(options)

    def do_POST(self):
        if self.path != "/client-error":
            super().do_POST()
            return
        try:
            assert self.headers.get("Origin") == base.ORIGIN
            length = int(self.headers["Content-Length"])
            assert 0 < length < 1024
            body = json.loads(self.rfile.read(length))
            assert body["mode"] in MODES
            assert body["errorName"] in {"NotAllowedError", "NotSupportedError", "InvalidStateError", "SecurityError", "AbortError", "UnknownError", "Error"}
            base.event("clientError", mode=body["mode"], errorName=body["errorName"])
            self.reply(dict(status="Client error recorded"))
        except (AssertionError, KeyError, ValueError):
            self.reply(dict(error="Invalid client error"), 400)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=PORTS["passkey-errors-web"])
    parser.add_argument("--directory", type=Path,
                        help="Evidence folder (default: <session>/passkey-errors-web)")
    base.add_tls_arguments(parser)
    arguments = parser.parse_args()
    base.ORIGIN = f"https://localhost:{arguments.port}"
    base.ALTERNATE_ORIGIN = base.ORIGIN
    base.USER = b"keyguard-native-e2e-passkey-errors-user"
    base.verify = verify
    base.serve(Handler, arguments.port, arguments,
               arguments.directory or session.path("passkey-errors-web"), "Passkey error fixture")


if __name__ == "__main__":
    main()
