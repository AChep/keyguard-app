#!/usr/bin/env python3
"""Enable email 2FA on the send account through the local mail sink.

Requests the activation mail from the mail server, reads the code from
`e2e smtp_sink` and enables the provider, then checks that a password login
is challenged for email 2FA. Only the mail server and the sink's API on
loopback are contacted, without proxies or redirects; no mail leaves the sink.
Requires an empty send account (purge its Sends first) without email 2FA.

Refuses to run when the state file exists. The state (mode 600) keeps an owner
access token for later read-only checks and cleanup: keep it in the session.
This proves only the server setup; the native login journeys run in the app.
"""

import argparse
import re
import time
from datetime import datetime, timezone
from pathlib import Path

from lib import bitwarden, session
from lib.config import PORTS, account

ACCOUNT = account("send")
SERVER = ACCOUNT.server.url
MAIL = f"http://127.0.0.1:{PORTS['smtp-api']}"
DEVICE = "native-e2e-email-twofa-fixture"


def request(base, path, body=None, token=None, method=None, form=False):
    if base not in (SERVER, MAIL):
        raise ValueError("Only the fixed loopback fixtures are allowed")
    return bitwarden.call(base, path, body, token, method, form)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def prepare(output):
    require(not output.exists(), "Private fixture state already exists; inspect before retrying")
    status, health = request(MAIL, "/health")
    require(status == 200 and health.get("sinkOnly") is True, "Local mail sink unavailable")
    email = ACCOUNT.email
    password_hash = bitwarden.password_hash(ACCOUNT)
    status, login = bitwarden.token_request(ACCOUNT, DEVICE)
    require(status == 200, f"Fixture login failed with status {status}")
    token = login["access_token"]
    status, sync = request(SERVER, "/api/sync", token=token)
    require(status == 200 and sync["profile"]["email"].lower() == email, "Unexpected fixture owner")
    require(not sync["ciphers"] and not sync["sends"], "Fixture account is not empty")
    password = {"masterPasswordHash": password_hash}
    status, existing = request(SERVER, "/api/two-factor/get-email", password, token)
    require(status == 200 and existing["enabled"] is False, "Email 2FA is already configured")
    state = {
        "server": SERVER, "email": email, "accountId": sync["profile"]["id"],
        "enabledBefore": False, "restorePending": True, "enabled": False,
        "ownerAccessToken": token, "mailSinkOnly": True,
        "source": "https://raw.githubusercontent.com/dani-garcia/vaultwarden/1.37.4/src/api/core/two_factor/email.rs",
    }
    session.write_json(output, state, exclusive=True)

    status, messages = request(MAIL, "/api/messages")
    require(status == 200, "Cannot read local mail metadata")
    before = {message["id"] for message in messages}
    status, _ = request(SERVER, "/api/two-factor/send-email", {**password, "email": email}, token)
    require(status == 200, f"Activation mail request failed with status {status}")
    deadline = time.monotonic() + 15
    while True:
        status, messages = request(MAIL, "/api/messages")
        candidates = [message for message in messages if message["id"] not in before
                      and message["recipients"] == [email]]
        if candidates:
            break
        require(time.monotonic() < deadline, "Activation mail not captured")
        time.sleep(0.2)
    require(len(candidates) == 1, "Ambiguous activation mail")
    message_id = candidates[0]["id"]
    status, message = request(MAIL, "/api/messages/" + message_id)
    require(status == 200, "Cannot read captured activation mail")
    codes = set(re.findall(r"\b\d{6}\b", message["text"]))
    require(len(codes) == 1, "Activation mail does not contain one unambiguous code")
    status, result = request(
        SERVER, "/api/two-factor/email",
        {**password, "email": email, "token": codes.pop()}, token, method="PUT",
    )
    require(status == 200 and result["enabled"] in (True, "true"), "Email activation failed")
    state.update(enabled=True, activationMailId=message_id)
    session.write_json(output, state)
    status, current = request(SERVER, "/api/two-factor/get-email", password, token)
    require(status == 200 and current["enabled"] is True and current["email"] == email,
            "Email provider verification failed")
    status, challenge = bitwarden.token_request(ACCOUNT, DEVICE)
    providers = challenge.get("TwoFactorProviders", [])
    require(status == 400 and "1" in [str(value) for value in providers], "Email challenge missing")
    state.update(challengeProviders=providers, preparedAt=datetime.now(timezone.utc).isoformat())
    session.write_json(output, state)
    print("Local email 2FA enabled and independent email-provider challenge verified; native login pending")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path,
                        help="Private state file (default: <session>/email-twofa-fixture-state.json)")
    args = parser.parse_args()
    prepare(args.output or session.path("email-twofa-fixture-state.json"))
