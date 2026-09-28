#!/usr/bin/env python3
"""Validate AutoFill packaging in a built app; distribution checks require an exported app.

Use --unsigned for CI simulator builds. Without it, inspect both signed bundles.
--distribution also rejects development signatures (get-task-allow).
This does not replace device testing of provider activation, unlock and filling.
"""

import argparse
from pathlib import Path
import plistlib
import subprocess
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_plist(path):
    with path.open("rb") as stream:
        return plistlib.load(stream)


def signed_entitlements(bundle):
    subprocess.run(["codesign", "--verify", "--strict", str(bundle)], check=True, capture_output=True)
    result = subprocess.run(
        ["codesign", "--display", "--xml", "--entitlements", "-", str(bundle)],
        check=True, capture_output=True,
    )
    return plistlib.loads(result.stdout)


def verify(app, *, unsigned=False, distribution=False, entitlements_reader=signed_entitlements):
    require(not (unsigned and distribution), "Distribution validation requires signatures")
    appex = app / "PlugIns/KeyguardAutofill.appex"
    parent = read_plist(app / "Info.plist")
    extension = read_plist(appex / "Info.plist")
    require(extension["CFBundleIdentifier"].startswith(parent["CFBundleIdentifier"] + "."),
            "Extension bundle identifier must be a child of the containing app")
    for key in ("CFBundleShortVersionString", "CFBundleVersion"):
        require(parent.get(key) == extension.get(key), f"App and extension differ in {key}")
    group = parent.get("KeyguardAppGroupIdentifier")
    require(isinstance(group, str) and group and "$" not in group, "App Group identifier is unresolved")
    require(group == extension.get("KeyguardAppGroupIdentifier"), "App Group identifiers differ")
    contract = extension.get("NSExtension", {})
    require(contract.get("NSExtensionPointIdentifier") == "com.apple.authentication-services-credential-provider-ui",
            "AutoFill extension point is missing")
    principal = contract.get("NSExtensionPrincipalClass", "")
    require(principal.endswith(".CredentialProviderViewController") and "$" not in principal,
            "AutoFill principal class is unresolved")
    capabilities = contract.get("NSExtensionAttributes", {}).get("ASCredentialProviderExtensionCapabilities", {})
    for capability in ("ProvidesPasswords", "ProvidesPasskeys", "ProvidesOneTimeCodes"):
        require(capabilities.get(capability) is True, f"Missing capability: {capability}")
    if unsigned:
        return
    app_signing = entitlements_reader(app)
    extension_signing = entitlements_reader(appex)
    for signing in (app_signing, extension_signing):
        require(signing.get("com.apple.developer.authentication-services.autofill-credential-provider") is True,
                "Signed AutoFill entitlement is missing")
        require(group in signing.get("com.apple.security.application-groups", []),
                "Configured App Group is absent from signed entitlements")
        if distribution:
            require(signing.get("get-task-allow") is not True, "Development signature cannot validate TestFlight export")
    require(app_signing.get("com.apple.developer.team-identifier") and
            app_signing.get("com.apple.developer.team-identifier") == extension_signing.get("com.apple.developer.team-identifier"),
            "Signed teams differ or are missing")
    require(set(app_signing.get("keychain-access-groups", [])) & set(extension_signing.get("keychain-access-groups", [])),
            "No shared signed keychain access group")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--unsigned", action="store_true")
    parser.add_argument("--distribution", action="store_true")
    args = parser.parse_args()
    try:
        verify(args.app, unsigned=args.unsigned, distribution=args.distribution)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print("AutoFill packaging checks passed; signed-device behavior still requires testing.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
