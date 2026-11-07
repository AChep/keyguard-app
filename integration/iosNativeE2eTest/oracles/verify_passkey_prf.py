#!/usr/bin/env python3
"""Check captured WebAuthn PRF outputs against the leased passkey's stored secret.

Reads the leased main-account Login with lib.bitwarden, takes the PRF secret of
its one passkey from the hidden `keyguard.passkeys` field, and recomputes every
expected output as HMAC-SHA256(secret, SHA-256("WebAuthn PRF" || 0x00 || input))
with the inputs of `e2e passkey_prf_web`. Compares them with the responses that
the relying party recorded (--responses). The calculation is checked first
against the W3C WebAuthn Level 3 PRF test vector. Never uses Keyguard code.
An `invalid-map` response that still returns a signed credential is recorded
as a client contract violation; a registration without PRF results is recorded
as capability-only.

Lease, written by hand at <session>/passkey-prf-fixture-state.json before the
native registration:

    {"itemId": "<server ID>", "fixtureName": "iPhone E2E Passkey PRF",
     "passwordChecksMustStayOff": true, "itemCleanupPending": true}

Writes <session>/passkey-prf-independent-oracle-proof.json (mode 600).
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import uuid
from pathlib import Path

from lib import bitwarden, session
from lib.config import account
from servers.passkey_prf_web import INPUTS


def self_test():
    # W3C WebAuthn Level3 section16.17.1.2, single-input hmac-secret vector.
    seed = b"WebAuthn PRF test vectors"
    secret = hashlib.sha256(seed + b"\x06").digest()
    salt = hashlib.sha256(b"WebAuthn PRF\0" + seed + b"\x02").digest()
    assert salt.hex() == "527413ebb48293772df30f031c5ac4650c7de14bf9498671ae163447b6a772b3"
    assert hmac.digest(secret, salt, "sha256").hex() == "3c33e07d202c3b029cc21f1722767021bf27d595933b3d2b6a1b9d5dddc77fae"


def decode(value):
    value = value.removeprefix("b64.")
    normalized = value.replace("-", "+").replace("_", "/")
    return base64.b64decode(normalized + "=" * (-len(normalized) % 4), validate=True)


def credential_bytes(value):
    try:
        return uuid.UUID(value).bytes
    except ValueError:
        return decode(value)


def verify(item_id, credential_id, responses_path):
    os.umask(0o077)
    self_test()
    lease = session.read_json(session.path("passkey-prf-fixture-state.json"))
    assert lease["itemId"] == item_id and lease["fixtureName"] == "iPhone E2E Passkey PRF"
    assert lease["passwordChecksMustStayOff"] and lease["itemCleanupPending"]
    oracle = bitwarden.Session(account("main"))
    cipher = next(x for x in oracle.sync["ciphers"] if x["id"] == item_id)
    key = oracle.cipher_key(cipher)
    text = lambda value: bitwarden.decrypt(value, key).decode() if value else None
    assert cipher["type"] == 1 and text(cipher["name"]) == lease["fixtureName"]
    entries = [(text(x.get("name")), text(x.get("value")), x.get("type"), x.get("linkedId"))
               for x in cipher.get("fields") or []]
    transport = [x for x in entries if x[0] == "keyguard.passkeys"]
    assert len(transport) == 1 and transport[0][2:] == (1, None)
    assert not any(x[0].startswith("keyguard.passkeys.v1.") for x in entries)
    document = json.loads(transport[0][1])
    assert document["version"] == 1 and len(document["credentials"]) == 1
    credential = document["credentials"][0]
    wire_id = decode(credential_id)
    assert credential_bytes(credential["credentialId"]) == wire_id
    secret = decode(credential["prfSecret"])
    assert len(secret) == 32
    passkeys = cipher["login"]["fido2Credentials"]
    assert len(passkeys) == 1
    assert credential_bytes(text(passkeys[0]["credentialId"])) == wire_id
    assert text(passkeys[0]["rpId"]) == "localhost"
    assert text(passkeys[0]["userName"]) == "ios-prf@keyguard.test"
    expected = {name: hmac.digest(secret, hashlib.sha256(b"WebAuthn PRF\0" + data).digest(), "sha256")
                for name, data in INPUTS.items()}
    responses = [json.loads(line) for line in responses_path.read_text().splitlines()]
    assert responses and all(x["credentialId"] == credential_id for x in responses)
    checked = []
    negative_violations = []
    for response in responses:
        mode = response["mode"]
        assert response["originChallengeSignatureVerified"]
        extensions = response["extensions"]
        if mode == "invalid-map":
            negative_violations.append(dict(mode=mode, expectedClientError="SyntaxError",
                unexpectedSignedCredentialResponse=True, signatureVerified=True,
                prfOutputPresent="prf" in extensions))
            continue
        if mode == "without-prf":
            assert "prf" not in extensions
            checked.append(dict(mode=mode, unrequestedOutputAbsent=True))
            continue
        assert mode in {"register", "same", "changed", "first-only", "per-credential"}
        prf = extensions["prf"]
        if mode == "register":
            assert prf["enabled"] is True
            if not prf.get("results"):
                checked.append(dict(mode=mode, prfEnabled=True, optionalRegistrationEvaluationUnavailable=True))
                continue
        values = prf["results"]
        first = expected["changed" if mode == "changed" else "first"]
        assert hmac.compare_digest(decode(values["first"]), first), mode + " first output mismatch"
        if mode == "first-only":
            assert "second" not in values
        else:
            assert hmac.compare_digest(decode(values["second"]), expected["second"]), mode + " second output mismatch"
        checked.append(dict(mode=mode, outputsMatchIndependentHmac=True, outputCount=len(values)))
    proof = dict(itemId=item_id, credentialId=credential_id, hiddenTransportVersionOne=True,
                 syntheticCredentialAndRpBound=True, storedSecretBytes=32,
                 calculation="HMAC-SHA256(secret, SHA256(WebAuthn PRF + zero byte + input))",
                 appCryptoNotUsed=True, officialW3CVectorPass=True, verifiedResponses=checked,
                 negativeClientContractViolations=negative_violations)
    session.write_json(session.path("passkey-prf-independent-oracle-proof.json"), proof)
    print("Independent PRF outputs verified:", [x["mode"] for x in checked])
    return proof


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--item-id", required=True, help="Server ID of the leased Login")
    parser.add_argument("--credential-id", required=True,
                        help="Base64url credential ID recorded by the relying party")
    parser.add_argument("--responses", type=Path,
                        help="Recorded responses "
                             "(default: <session>/passkey-prf-web/responses.jsonl)")
    arguments = parser.parse_args()
    verify(arguments.item_id, arguments.credential_id,
           arguments.responses or session.path("passkey-prf-web", "responses.jsonl"))
