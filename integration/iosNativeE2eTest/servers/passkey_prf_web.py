#!/usr/bin/env python3
"""Serve a local HTTPS relying party that captures passkey PRF outputs.

A separate process from `e2e autofill_web`, with its own synthetic user and
credential store, reusing its verifier. Covers PRF at registration, same and
changed inputs, one input, per-credential overrides, omission and an invalid
credential map. Captured outputs stay in private evidence files.
"""

import argparse
import json
import secrets
import time
from pathlib import Path

from lib import session
from lib.config import PORTS
from servers import autofill_web as base


INPUTS = {name: bytes([value]) * 32 for name, value in (("first", 17), ("second", 34), ("changed", 51), ("fallback", 68))}
MODES = {"register", "same", "changed", "first-only", "per-credential", "without-prf", "invalid-map"}
original_verify = base.verify

PAGE = r'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Keyguard E2E Passkey PRF</title>
<style>body{font:18px system-ui;margin:24px;max-width:40rem}button{display:block;font:inherit;padding:10px;width:100%;margin:8px 0}output{display:block;white-space:pre-wrap}</style>
<h1>Keyguard E2E Passkey PRF</h1><p>Synthetic loopback requests only.</p>
<button data-mode="register">Register PRF with two inputs</button>
<button data-mode="same">Assert same PRF inputs</button>
<button data-mode="changed">Assert changed first input</button>
<button data-mode="first-only">Assert first input only</button>
<button data-mode="per-credential">Assert credential override</button>
<button data-mode="without-prf">Assert without PRF</button>
<button data-mode="invalid-map">Reject invalid credential map</button>
<output id="request" aria-live="polite"></output><output id="status" aria-live="polite">Ready</output>
<script>
const status=document.querySelector('#status');
const encode=b=>btoa(String.fromCharCode(...new Uint8Array(b))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
const decode=s=>Uint8Array.from(atob(s.replaceAll('-','+').replaceAll('_','/')),c=>c.charCodeAt(0));
const values=v=>({first:decode(v.first),...(v.second?{second:decode(v.second)}:{})});
async function run(mode){
 try{
  status.textContent='Requesting '+mode;
  const response=await fetch('/options/'+mode),options=await response.json();
  if(!response.ok)throw Error(options.error);
  document.querySelector('#request').textContent='PRF mode: '+mode;
  options.challenge=decode(options.challenge);
  const register=mode==='register';
  if(register)options.user.id=decode(options.user.id);
  else options.allowCredentials=options.allowCredentials.map(c=>({...c,id:decode(c.id)}));
  const prf=options.extensions?.prf;
  if(prf?.eval)prf.eval=values(prf.eval);
  if(prf?.evalByCredential)prf.evalByCredential=Object.fromEntries(Object.entries(prf.evalByCredential).map(([k,v])=>[k,values(v)]));
  const credential=await navigator.credentials[register?'create':'get']({publicKey:options});
  const result=credential.response,extensions=credential.getClientExtensionResults();
  if(extensions.prf?.results){const v=extensions.prf.results;extensions.prf.results={first:encode(v.first),...(v.second?{second:encode(v.second)}:{})};}
  const body={id:credential.id,rawId:encode(credential.rawId),type:credential.type,clientDataJSON:encode(result.clientDataJSON),clientExtensions:extensions};
  for(const field of ['attestationObject','authenticatorData','signature','userHandle'])if(result[field])body[field]=encode(result[field]);
  const checked=await fetch('/result/'+(register?'register':'assert'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  const outcome=await checked.json();status.textContent=checked.ok?outcome.status:outcome.error;
 }catch(error){status.textContent=mode+': '+error.name+': '+error.message;await fetch('/client-error',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mode,errorName:error.name})});}
}
for(const button of document.querySelectorAll('button'))button.onclick=()=>run(button.dataset.mode);
</script></html>'''


def verify(kind, body):
    client = json.loads(base.decode(body["clientDataJSON"]))
    request = dict(base.CHALLENGES[client["challenge"]])
    result = original_verify(kind, body)
    extensions = body.get("clientExtensions", {})
    record = dict(mode=request["mode"], credentialId=body["rawId"], extensions=extensions,
                  originChallengeSignatureVerified=True, capturedAtEpoch=time.time())
    session.append_jsonl(base.evidence() / "responses.jsonl", record)
    if request["mode"] == "without-prf":
        assert "prf" not in extensions, "Unrequested PRF output"
    else:
        prf = extensions.get("prf") or {}
        if kind == "register":
            assert prf.get("enabled") is True, "PRF registration did not report enabled"
            if not prf.get("results"):
                base.event("prfRegistrationEnabledWithoutEvaluation", credentialId=body["rawId"])
                return result + "; PRF enabled without registration evaluation"
        values = prf.get("results") or {}
        assert len(base.decode(values["first"])) == 32, "Missing first PRF output"
        if request["mode"] == "first-only":
            assert "second" not in values, "Unexpected second PRF output"
        else:
            assert len(base.decode(values["second"])) == 32, "Missing second PRF output"
    base.event("prfResponseCaptured", mode=request["mode"], credentialId=body["rawId"],
               outputCount=len((extensions.get("prf") or {}).get("results") or {}))
    return result + "; PRF response captured"


class Handler(base.Handler):
    def do_GET(self):
        if self.path == "/":
            return self.reply(PAGE, html=True)
        if self.path == "/health":
            return self.reply(dict(owner="keyguard-ios-native-e2e-passkey-prf", credentials=len(base.CREDENTIALS)))
        mode = self.path.removeprefix("/options/")
        if not self.path.startswith("/options/") or mode not in MODES:
            return self.reply(dict(error="Not found"), 404)
        if mode != "register" and not base.CREDENTIALS:
            return self.reply(dict(error="Register the owned PRF passkey first"), 409)
        kind = "register" if mode == "register" else "assert"
        challenge = base.encode(secrets.token_bytes(32))
        base.CHALLENGES[challenge] = dict(kind=kind, mode=mode, uv="required", expires=time.time() + 300)
        options = dict(challenge=challenge, timeout=45000)
        if kind == "register":
            options.update(rp=dict(id="localhost", name="Keyguard local PRF E2E"),
                user=dict(id=base.encode(base.USER), name="ios-prf@keyguard.test", displayName="iPhone E2E Passkey PRF"),
                pubKeyCredParams=[dict(type="public-key", alg=-7)], attestation="none",
                authenticatorSelection=dict(residentKey="required", userVerification="required"))
        else:
            identifier = next(reversed(base.CREDENTIALS))
            options.update(rpId="localhost", userVerification="required",
                           allowCredentials=[dict(type="public-key", id=identifier)])
        values = dict(first=base.encode(INPUTS["changed" if mode == "changed" else "first"]))
        if mode != "first-only":
            values["second"] = base.encode(INPUTS["second"])
        if mode != "without-prf":
            extension = dict(eval=values)
            if mode == "per-credential":
                extension = dict(eval=dict(first=base.encode(INPUTS["changed"]), second=base.encode(INPUTS["fallback"])),
                                 evalByCredential={identifier: values})
            elif mode == "invalid-map":
                extension = dict(evalByCredential={"not-base64!": values})
            options["extensions"] = dict(prf=extension)
        base.event("prfOptionsRequested", mode=mode, ceremonyKind=kind)
        return self.reply(options)

    def do_POST(self):
        if self.path != "/client-error":
            return super().do_POST()
        try:
            assert self.headers.get("Origin") == base.ORIGIN
            length = int(self.headers.get("Content-Length", "0"))
            assert 0 < length < 16384
            body = json.loads(self.rfile.read(length))
            assert set(body) == {"mode", "errorName"} and body["mode"] in MODES
            assert isinstance(body["errorName"], str) and len(body["errorName"]) <= 128
            base.event("prfClientError", **body)
            return self.reply(dict(status="Client error recorded"))
        except (AssertionError, KeyError, ValueError):
            return self.reply(dict(error="Invalid client error"), 400)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=PORTS["passkey-prf-web"])
    parser.add_argument("--directory", type=Path,
                        help="Evidence folder (default: <session>/passkey-prf-web)")
    base.add_tls_arguments(parser)
    arguments = parser.parse_args()
    base.ORIGIN = f"https://localhost:{arguments.port}"
    base.ALTERNATE_ORIGIN = base.ORIGIN
    base.USER = b"keyguard-native-e2e-passkey-prf-user"
    base.verify = verify
    base.serve(Handler, arguments.port, arguments,
               arguments.directory or session.path("passkey-prf-web"), "Passkey PRF fixture")


if __name__ == "__main__":
    main()
