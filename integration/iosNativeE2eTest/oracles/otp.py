#!/usr/bin/env python3
"""Run the self-test of the offline HOTP/TOTP, Steam and Mobile-OTP oracle.

Other scripts import code(raw, timestamp) and codes_during(raw, start, end);
the latter accepts only periods intersecting a recorded native operation.
Anchored to RFC 4226/6238 vectors.

Uses standard-library HMAC and Base32; no Keyguard code or network requests.
Steam encoding follows PyOTP's independently maintained implementation:
https://pyauth.github.io/pyotp/_modules/pyotp/contrib/steam.html
Mobile-OTP follows the original client source:
https://motp.sourceforge.net/MobileOTP.java
"""

import argparse
import base64
import hashlib
import hmac
import json
import urllib.parse

STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"


def binary_code(secret, counter, algorithm="sha1"):
    if algorithm not in ("sha1", "sha256", "sha512"):
        raise ValueError("Unsupported HOTP hash")
    digest = hmac.digest(secret, counter.to_bytes(8, "big"), algorithm)
    offset = digest[-1] & 15
    return int.from_bytes(digest[offset:offset + 4], "big") & 0x7fffffff


def numeric_code(secret, counter, algorithm="sha1", digits=6):
    return str(binary_code(secret, counter, algorithm) % (10 ** digits)).zfill(digits)


def configuration(raw):
    if raw.lower().startswith("motp://"):
        params = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(raw).query))
        return "motp", params["secret"] + params.get("pin", ""), "md5", 6, 10, None
    if raw.lower().startswith("steam://"):
        return "steam", raw[8:], "sha1", 5, 30, None
    if not raw.lower().startswith("otpauth://"):
        return "totp", raw, "sha1", 6, 30, None
    url = urllib.parse.urlsplit(raw)
    params = dict(urllib.parse.parse_qsl(url.query))
    kind = "steam" if params.get("encoder", "").lower() == "steam" else url.netloc
    return (kind, params["secret"], params.get("algorithm", "sha1").lower(),
            int(params.get("digits", 5 if kind == "steam" else 6)),
            int(params.get("period", 30)), int(params.get("counter", 0)))


def code(raw, timestamp):
    kind, encoded, algorithm, digits, period, counter = configuration(raw)
    if kind == "motp":
        message = str(int(timestamp // period)) + encoded
        return hashlib.md5(message.encode()).hexdigest()[:6]
    normalized = "".join(encoded.split()).upper()
    secret = base64.b32decode(normalized + "=" * (-len(normalized) % 8))
    counter = counter if kind == "hotp" else int(timestamp // period)
    if kind == "steam":
        value = binary_code(secret, counter, algorithm)
        result = ""
        for _ in range(5):
            result += STEAM_ALPHABET[value % len(STEAM_ALPHABET)]
            value //= len(STEAM_ALPHABET)
        return result
    if kind not in ("hotp", "totp"):
        raise ValueError("Unsupported OTP kind")
    return numeric_code(secret, counter, algorithm, digits)


def codes_during(raw, start, end):
    """Only periods intersecting the recorded operation, without a wide skew window."""
    kind, _, _, _, period, _ = configuration(raw)
    if end < start or end - start > 120:
        raise ValueError("Unexpected native operation interval")
    if kind == "hotp":
        return {code(raw, start)}
    return {code(raw, step * period) for step in range(int(start // period), int(end // period) + 1)}


def self_test():
    secret = b"12345678901234567890"
    expected = ("755224", "287082", "359152", "969429", "338314",
                "254676", "287922", "162583", "399871", "520489")
    assert tuple(numeric_code(secret, counter) for counter in range(10)) == expected
    vectors = ((secret, "sha1", "94287082"),
               (b"12345678901234567890123456789012", "sha256", "46119246"),
               (b"1234567890123456789012345678901234567890123456789012345678901234",
                "sha512", "90693936"))
    for key, algorithm, value in vectors:
        assert numeric_code(key, 59 // 30, algorithm, 8) == value
    # Independently checked with OpenSSL for counter 123456789 + secret + PIN.
    assert code("motp://E2E:test?secret=0123456789abcdef&pin=1234", 1234567890) == "f41e13"
    return {"rfc4226Vectors": 10, "rfc6238Algorithms": ["sha1", "sha256", "sha512"],
            "mobileOtpOpenSslVector": True, "noNetworkRequests": True}


if __name__ == "__main__":
    argparse.ArgumentParser(description=__doc__).parse_args()
    print(json.dumps(self_test()))
