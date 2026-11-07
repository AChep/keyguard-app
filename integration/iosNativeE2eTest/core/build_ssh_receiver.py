#!/usr/bin/env python3
"""Build the simulator-only ssh:// URL receiver app; never installs it.

Compiles SSHReceiver/ReceiverApp.swift for the iOS simulator into the session's
`ssh-url-receiver/` folder, ad-hoc signs and verifies it, and writes
`build-proof.json`. Refuses a non-empty folder this script does not own. Uses the
Xcode that xcrun selects; set DEVELOPER_DIR to choose another.
"""

import argparse
import hashlib
import json
import os
import plistlib
import subprocess

from lib import session

BUNDLE = "com.artemchep.keyguard.ios.e2e.sshreceiver"
SOURCE = session.HARNESS / "SSHReceiver" / "ReceiverApp.swift"


def run(*args):
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()


def build():
    os.umask(0o077)
    root = session.directory() / "ssh-url-receiver"
    root.mkdir(exist_ok=True, mode=0o700)
    owner = root / "owner.json"
    if owner.exists():
        assert json.loads(owner.read_text()) == {"bundleId": BUNDLE}
    else:
        assert not list(root.iterdir()), "Refusing an unowned artifact directory"
        owner.write_text(json.dumps({"bundleId": BUNDLE}))
    app = root / "E2eSshReceiver.app"
    app.mkdir(exist_ok=True, mode=0o700)
    info = dict(CFBundleIdentifier=BUNDLE, CFBundleName="E2eSshReceiver",
        CFBundleDisplayName="E2E SSH Receiver", CFBundleExecutable="E2eSshReceiver",
        CFBundlePackageType="APPL", CFBundleVersion="1", CFBundleShortVersionString="1.0",
        MinimumOSVersion="18.0", CFBundleSupportedPlatforms=["iPhoneSimulator"],
        UIDeviceFamily=[1, 2], UILaunchScreen={},
        CFBundleURLTypes=[dict(CFBundleURLName="keyguard-e2e-owned-ssh",
            CFBundleTypeRole="Viewer", CFBundleURLSchemes=["ssh"])])
    (app / "Info.plist").write_bytes(plistlib.dumps(info))
    sdk = run("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path")
    cache = root / "module-cache"
    cache.mkdir(exist_ok=True, mode=0o700)
    run("xcrun", "--sdk", "iphonesimulator", "swiftc", "-parse-as-library",
        "-target", "arm64-apple-ios18.0-simulator", "-sdk", sdk,
        "-module-cache-path", str(cache), str(SOURCE), "-o", str(app / "E2eSshReceiver"))
    run("codesign", "--force", "--sign", "-", "--timestamp=none", str(app))
    run("codesign", "--verify", "--strict", str(app))
    proof = dict(bundleId=BUNDLE, app=str(app), simulatorOnly=True, installed=False,
        sourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        executableSha256=hashlib.sha256((app / "E2eSshReceiver").read_bytes()).hexdigest(),
        sdk=sdk, signatureVerified=True, expectedUrl="ssh://e2e@127.0.0.1:2222")
    session.write_json(root / "build-proof.json", proof)
    print("Built simulator-only owned SSH URL receiver; not installed")


if __name__ == "__main__":
    argparse.ArgumentParser(description=__doc__).parse_args()
    build()
