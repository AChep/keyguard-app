import importlib.util
from pathlib import Path
import plistlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "autofill_packaging", Path(__file__).resolve().parents[1] / "xcode/scripts/verify-ios-autofill.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AutofillPackagingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name) / "Keyguard.app"
        self.extension = self.app / "PlugIns/KeyguardAutofill.appex"
        self.extension.mkdir(parents=True)
        self.parent = {"CFBundleIdentifier": "test.keyguard", "CFBundleVersion": "1",
                       "CFBundleShortVersionString": "1.0", "KeyguardAppGroupIdentifier": "group.test.keyguard"}
        self.child = dict(self.parent, CFBundleIdentifier="test.keyguard.autofill", NSExtension={
            "NSExtensionPointIdentifier": "com.apple.authentication-services-credential-provider-ui",
            "NSExtensionPrincipalClass": "KeyguardAutofill.CredentialProviderViewController",
            "NSExtensionAttributes": {"ASCredentialProviderExtensionCapabilities": dict.fromkeys(
                ["ProvidesPasswords", "ProvidesPasskeys", "ProvidesOneTimeCodes"], True)}})
        self.signing = {
            "com.apple.developer.authentication-services.autofill-credential-provider": True,
            "com.apple.security.application-groups": ["group.test.keyguard"],
            "com.apple.developer.team-identifier": "TEAM",
            "keychain-access-groups": ["TEAM.keyguard.shared"], "get-task-allow": False}
        self.write()

    def write(self):
        for bundle, value in [(self.app, self.parent), (self.extension, self.child)]:
            (bundle / "Info.plist").write_bytes(plistlib.dumps(value))

    def verify(self, **kwargs):
        return MODULE.verify(self.app, entitlements_reader=lambda _: self.signing, **kwargs)

    def test_valid_distribution_and_unsigned_metadata(self):
        self.verify(distribution=True)
        self.verify(unsigned=True)

    def test_unresolved_group_rejected(self):
        self.parent["KeyguardAppGroupIdentifier"] = "$(KEYGUARD_APP_GROUP_ID)"
        self.write()
        with self.assertRaisesRegex(ValueError, "unresolved"):
            self.verify()

    def test_group_mismatch_rejected(self):
        self.child["KeyguardAppGroupIdentifier"] = "group.other"
        self.write()
        with self.assertRaisesRegex(ValueError, "differ"):
            self.verify()

    def test_missing_capability_rejected(self):
        del self.child["NSExtension"]["NSExtensionAttributes"]["ASCredentialProviderExtensionCapabilities"]["ProvidesPasskeys"]
        self.write()
        with self.assertRaisesRegex(ValueError, "ProvidesPasskeys"):
            self.verify(unsigned=True)

    def test_missing_shared_keychain_rejected(self):
        self.signing["keychain-access-groups"] = []
        with self.assertRaisesRegex(ValueError, "keychain"):
            self.verify()

    def test_development_signature_is_not_distribution_proof(self):
        self.signing["get-task-allow"] = True
        self.verify()
        with self.assertRaisesRegex(ValueError, "Development signature"):
            self.verify(distribution=True)


if __name__ == "__main__":
    unittest.main()
