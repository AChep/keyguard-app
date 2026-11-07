#!/usr/bin/env python3
"""Print the current authenticator code of the 2fa account.

An independent RFC 6238 generator (SHA-1, six digits, thirty seconds) using
Python's standard library, never Keyguard code. The secret comes from
<session>/accounts/<account>-totp.json, which `e2e bootstrap` writes when it
enables 2FA. Vaultwarden rejects a reused step, and the activation code already
used the step it was made in: take a fresh code for each successful login.
Other scripts import totp(), secret() and login().
"""

import argparse
import base64
import hmac
import json
import time

from lib import bitwarden, session
from lib.config import account

DEVICE_ID = "native-e2e-two-factor-independent-oracle"


def secret(key="2fa"):
    path = session.path("accounts", f"{key}-totp.json")
    if not path.exists():
        raise SystemExit(f"No authenticator secret at {path}; run `e2e bootstrap`.")
    return session.read_json(path)["secret"]


def login(key="2fa", provider=None, code=None, remember=False):
    """Password grant, optionally with a 2FA provider code. Returns (status, body)."""
    extra = None
    if provider is not None:
        extra = {"twoFactorProvider": str(provider), "twoFactorToken": code,
                 "twoFactorRemember": "1" if remember else "0"}
    return bitwarden.token_request(account(key), DEVICE_ID, extra)


def totp(secret, step=None):
    """RFC 6238 SHA-1, six digits, thirty seconds; independent of app code."""
    key = base64.b32decode(secret.upper() + "=" * (-len(secret) % 8))
    counter = int(time.time()) // 30 if step is None else step
    digest = hmac.digest(key, counter.to_bytes(8, "big"), "sha1")
    offset = digest[-1] & 15
    number = int.from_bytes(digest[offset:offset + 4], "big") & 0x7fffffff
    return f"{number % 1_000_000:06d}"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", default="2fa", help="Account key (default: 2fa)")
    args = parser.parse_args()
    now = time.time()
    print(json.dumps({"code": totp(secret(args.account)),
                      "secondsLeft": 30 - int(now) % 30}))
