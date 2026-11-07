#!/usr/bin/env python3
"""Add handwritten Chrome, Firefox and Safari CSV exports to test Files.

Expected items in `browser-import-fixtures.json` are written by hand, independent
of the app's parsers. Existing Files documents are never overwritten.
"""

import argparse
import csv
import hashlib
import io

from lib.simulator import device_argument, files_directory
from lib import session

OTP = "otpauth://totp/Browser:alice?secret=JBSWY3DPEHPK3PXP&issuer=Browser"


def login(name, username, password, uris, notes=None, totp=None):
    return dict(name=name, type=1, favorite=False, reprompt=0, notes=notes, fields=[],
                login=dict(username=username, password=password, totp=totp,
                           uris=[dict(uri=uri) for uri in uris]))


def prepare(device):
    files = files_directory(device)
    cases = {
        "chrome": dict(
            header=["name", "url", "username", "password", "note"],
            rows=[
                ["E2E Chrome quoted", "https://chrome.example.test/login", "alice", ' spaced "and" quoted, ', "First line\n日本語 🔐"],
                ["", "https://www.chrome-fallback.example.test/", "bob", "Chrome-Fallback-2026!", ""],
                ["E2E Chrome Android", "android://kCyQDzpaoAX2gs-1zdGPKNAeICb8LzRFOxa4NCq0jO8=@com.example.app/", "carol", "Chrome-App-2026!", ""],
            ],
            items=[login("E2E Chrome quoted", "alice", ' spaced "and" quoted, ', ["https://chrome.example.test/login"], "First line\n日本語 🔐"),
                   login("chrome-fallback.example.test", "bob", "Chrome-Fallback-2026!", ["https://www.chrome-fallback.example.test/"]),
                   login("E2E Chrome Android", "carol", "Chrome-App-2026!", ["androidapp://com.example.app"])],
            skips={},
        ),
        "firefox": dict(
            header=["url", "username", "password", "httpRealm", "formActionOrigin", "guid", "timeCreated", "timeLastUsed", "timePasswordChanged"],
            rows=[
                ["https://www.firefox.example.test", "alice", "Firefox-First-2026!", "", "https://login.firefox.example.test", "{e2e-1}", "1", "2", "3"],
                ["https://firefox-second.example.test", "bob", "Firefox-Second-2026!", "", "https://firefox-second.example.test", "{e2e-2}", "1", "2", "3"],
                ["chrome://FirefoxAccounts", "internal-account", "internal-token", "Firefox Accounts credentials", "", "{e2e-3}", "1", "2", "3"],
            ],
            items=[login("firefox.example.test", "alice", "Firefox-First-2026!", ["https://www.firefox.example.test", "https://login.firefox.example.test"]),
                   login("firefox-second.example.test", "bob", "Firefox-Second-2026!", ["https://firefox-second.example.test"])],
            skips={},
        ),
        "safari": dict(
            header=["Title", "URL", "Username", "Password", "Notes", "OTPAuth"],
            rows=[
                ["E2E Safari OTP", "https://safari.example.test/", "alice", "Safari-First-2026!", "Safari note\nÜnïcödé", OTP],
                ["E2E Safari broken OTP", "https://safari-broken.example.test/", "bob", "Safari-Second-2026!", "", "otpauth://totp/Broken?secret=not-base32!"],
            ],
            items=[login("E2E Safari OTP", "alice", "Safari-First-2026!", ["https://safari.example.test/"], "Safari note\nÜnïcödé", OTP),
                   login("E2E Safari broken OTP", "bob", "Safari-Second-2026!", ["https://safari-broken.example.test/"])],
            skips={"Otp": 1},
        ),
    }
    manifest = {}
    for case, value in cases.items():
        output = io.StringIO(newline="")
        writer = csv.writer(output, lineterminator="\r\n")
        writer.writerow(value["header"])
        writer.writerows(value["rows"])
        data = output.getvalue().encode("utf-8-sig" if case == "chrome" else "utf-8")
        name = "e2e-browser-" + case + ".csv"
        target = files / name
        assert not target.exists(), target
        target.write_bytes(data)
        manifest[case] = dict(file=name, sha256=hashlib.sha256(data).hexdigest(),
                              items=value["items"], folders=[], skips=value["skips"],
                              provenance="Handwritten synthetic CSV using documented columns and repository CsvImportersTest examples")
    invalid = files / "e2e-browser-invalid.csv"
    assert not invalid.exists()
    invalid.write_text("Title,Website,Login\r\nWrong format,https://invalid.example.test,synthetic\r\n")
    session.write_json(session.path("browser-import-fixtures.json"), manifest)
    print("Prepared", len(cases), "synthetic browser CSV files and one invalid-header file")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    prepare(parser.parse_args().device)
