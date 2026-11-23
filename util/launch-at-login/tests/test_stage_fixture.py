import plistlib
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from stage_fixture import stage


class StagingTest(unittest.TestCase):
    def test_macos_stages_matching_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "binary"
            binary.write_bytes(b"fixture")
            identity = "com.artemchep.keyguard.startuptest." + "a" * 32
            bundle = stage("macos", root / "staged", identity, binary, binary)
            info = plistlib.loads((bundle / "Contents/Info.plist").read_bytes())
            self.assertEqual(info["CFBundleIdentifier"], identity)
            self.assertTrue((bundle / "Contents/MacOS" / info["CFBundleExecutable"]).is_file())

    def test_msix_stages_opt_in_task_and_separate_control_application(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "binary"
            binary.write_bytes(b"fixture")
            identity = "Keyguard.Test." + "b" * 32
            package = stage("msix", root / "staged", identity, binary, binary, "CN=Startup Fixture", "arm64")
            manifest = ET.parse(package / "AppxManifest.xml")
            ns = {"f": "http://schemas.microsoft.com/appx/manifest/foundation/windows10", "u5": "http://schemas.microsoft.com/appx/manifest/uap/windows10/5"}
            self.assertEqual(manifest.find("f:Identity", ns).get("Name"), identity)
            self.assertLessEqual(len(identity), 50)
            self.assertEqual(manifest.find("f:Identity", ns).get("ProcessorArchitecture"), "arm64")
            apps = manifest.findall("f:Applications/f:Application", ns)
            self.assertEqual([app.get("Id") for app in apps], ["Entry", "Registration"])
            extension = manifest.find(".//u5:Extension", ns)
            self.assertEqual(extension.get("{http://schemas.microsoft.com/appx/manifest/uap/windows10/10}Parameters"), "--launch-at-login")
            self.assertEqual(extension.find("u5:StartupTask", ns).get("Enabled"), "false")
            self.assertEqual(extension.find("u5:StartupTask", ns).get("TaskId"), "KeyguardStartup")

    def test_refuses_production_identity_existing_output_and_missing_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "binary"
            binary.write_bytes(b"fixture")
            identity = "com.artemchep.keyguard.startuptest." + "c" * 32
            for target, name, helper in ((root / "new", "com.artemchep.keyguard", binary), (root, identity, binary), (root / "new", identity, root / "absent")):
                with self.assertRaises(ValueError):
                    stage("macos", target, name, helper, binary)
            self.assertFalse((root / "new").exists())


if __name__ == "__main__":
    unittest.main()
