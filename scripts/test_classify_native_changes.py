import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts.classify_native_changes import FLAGS, changed_paths, classify


class NativeChangeClassificationTest(unittest.TestCase):
    def test_docs_translations_and_ui_do_not_build_native_packages(self):
        result = classify([
            "docs/architecture/native-crypto.md",
            "util/io/README.md",
            "common/src/commonMain/composeResources/values/strings.xml",
            "desktopApp/src/jvmMain/kotlin/com/artemchep/keyguard/desktop/ui/PopupComposeDialog.kt",
            "androidApp/src/main/java/com/artemchep/keyguard/ui/MainScreen.kt",
        ])
        self.assertFalse(any(result[flag] for flag in FLAGS))

    def test_crypto_rust_change_keeps_quality_and_representative_packages(self):
        result = classify(["util/crypto/rust/crates/keyguard-crypto-core/src/lib.rs"])
        for flag in ("crypto", "fuzz", "desktop", "android", "apple"):
            self.assertTrue(result[flag], flag)
        self.assertEqual([row["os"] for row in result["desktop_matrix"]["include"]], ["ubuntu-24.04", "macos-15", "windows-2025"])
        self.assertEqual(result["android_matrix"], {"include": [{"api": 26}]})
        self.assertFalse(result["wear"])

    def test_io_keeps_both_android_seccomp_boundaries(self):
        for path in (
            "util/io/rust/crates/keyguard-io-core/src/fsops.rs",
            "util/io/src/jvmMain/kotlin/com/artemchep/keyguard/util/io/bridge/NativeIoBridge.kt",
            "androidApp/src/androidTest/kotlin/com/artemchep/keyguard/test/io/NativeIoAtomicDirectorySmokeTest.kt",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertTrue(result["io"])
                self.assertTrue(result["android_run"])
                self.assertEqual(result["android_matrix"], {"include": [{"api": 26}, {"api": 30}]})

    def test_webauthn_selects_consumers_and_native_protocol_tests(self):
        result = classify(["util/webauthn/src/commonMain/kotlin/WebAuthnAuthenticator.kt"])
        for flag in ("desktop", "android", "apple", "apple_regressions"):
            self.assertTrue(result[flag], flag)
        self.assertFalse(result["crypto"])
        self.assertFalse(result["native_quality"])

        result = classify(["util/webauthn/src/commonTest/kotlin/WebAuthnProtocolTest.kt"])
        self.assertTrue(result["apple_regressions"])
        for flag in ("desktop", "android", "apple", "crypto"):
            self.assertFalse(result[flag], flag)

    def test_fuzz_only_edit_does_not_rebuild_apps(self):
        result = classify(["util/crypto/rust/fuzz/fuzz_targets/dispatch.rs"])
        self.assertTrue(result["fuzz"])
        self.assertFalse(result["desktop"])
        self.assertFalse(result["android"])
        self.assertFalse(result["apple"])

    def test_native_dependencies_are_not_silently_omitted(self):
        cases = {
            "util/zxcvbn/rust/Cargo.lock": (True, True, True),
            "util/zip/rust/Cargo.toml": (False, False, True),
            "util/instance/rust/Cargo.lock": (True, False, False),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                result = classify([path])
                self.assertEqual(tuple(result[flag] for flag in ("desktop", "android", "apple")), expected)
                self.assertTrue(result["native_quality"])

    def test_jni_source_sets_select_only_their_consuming_platforms(self):
        cases = {
            "util/crypto/src/androidMain/kotlin/NativeCryptoLibraryLoader.android.kt": (False, True, False),
            "util/io/src/androidMain/kotlin/NativeIoLibraryLoader.android.kt": (False, True, False),
            "util/zxcvbn/src/desktopMain/kotlin/NativeZxcvbnLibraryLoader.kt": (True, False, False),
            "util/io/src/desktopMain/kotlin/NativeIoLibraryLoader.kt": (True, False, False),
            "util/crypto/src/jvmCommonMain/kotlin/NativeCryptoJni.kt": (True, True, False),
            "util/io/src/jvmMain/kotlin/NativeIoJni.kt": (True, True, False),
            "util/zxcvbn/src/jvmMain/kotlin/NativeZxcvbnJni.kt": (True, True, False),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                result = classify([path])
                self.assertEqual(tuple(result[flag] for flag in ("desktop", "android", "apple")), expected)
                self.assertFalse(result["apple_regressions"])
                self.assertEqual(result["desktop_run"], expected[0])

    def test_apple_sources_do_not_select_jni_regressions_or_android(self):
        for path in (
            "util/io/src/appleMain/kotlin/FileLastModified.apple.kt",
            "util/crypto/src/appleInteropMain/kotlin/NativeCryptoPlatform.apple.kt",
            "util/zxcvbn/src/nativeInterop/cinterop/nativeZxcvbn.def",
            "util/io/src/iosMain/kotlin/NativeIoIos.kt",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertTrue(result["apple"])
                self.assertFalse(result["desktop"])
                self.assertFalse(result["desktop_regressions"])
                self.assertFalse(result["android_run"])
                self.assertFalse(result["io"])
                self.assertEqual([host["platform"] for host in result["desktop_matrix"]["include"]], ["macos"])

    def test_instance_apple_bridge_and_macos_sources_need_only_native_macos(self):
        for path in (
            "util/instance/src/appleInteropMain/kotlin/NativeInstance.kt",
            "util/instance/src/nativeInterop/cinterop/nativeInstance.def",
            "util/io/src/macosArm64Main/kotlin/NativeIoMac.kt",
            "util/instance/src/macosArm64Test/kotlin/NativeInstanceTest.kt",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertTrue(result["apple_regressions"])
                self.assertFalse(result["desktop"])
                self.assertFalse(result["apple"])
                self.assertFalse(result["android_run"])
                self.assertFalse(result["instance"])
                self.assertEqual([host["platform"] for host in result["desktop_matrix"]["include"]], ["macos"])

    def test_zip_jvm_implementation_does_not_request_native_work(self):
        result = classify(["util/zip/src/jvmMain/kotlin/Zip4jArchive.kt"])
        self.assertFalse(any(result[flag] for flag in FLAGS))

    def test_io_android_bridge_retains_seccomp_matrix_without_desktop_hosts(self):
        result = classify(["util/io/src/androidMain/kotlin/NativeIoLibraryLoader.android.kt"])
        self.assertTrue(result["io"])
        self.assertTrue(result["android"])
        self.assertFalse(result["desktop_run"])
        self.assertEqual(result["android_matrix"]["include"], [{"api": 26}, {"api": 30}])

    def test_packaged_desktop_icons_trigger_bundle_checks(self):
        for name in ("icon.png", "icon.icns", "icon.ico"):
            with self.subTest(name=name):
                result = classify([f"desktopApp/{name}"])
                self.assertTrue(result["desktop"])
                self.assertFalse(result["android"])

    def test_packaging_actions_and_configuration_request_packages_not_crypto_quality(self):
        for path in (
            ".github/actions/build_appimage/build.sh",
            ".github/actions/build_flatpak/action.yml",
            ".github/workflows/new_flatpak.yaml",
            "desktopApp/proguard-rules.pro",
            "desktopApp/default.entitlements",
            "desktopApp/src/jvmMain/kotlin/com/artemchep/keyguard/desktop/nativebundle/NativeBundleSmoke.kt",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertTrue(result["desktop"])
                self.assertFalse(result["crypto"])
                self.assertFalse(result["android"])

    def test_android_packaging_paths_are_covered_without_desktop(self):
        for path in (
            "androidApp/build.gradle.kts",
            "wearApp/proguard-rules.pro",
            "wearApp/src/main/AndroidManifest.xml",
            ".github/actions/setup_android_ndk/action.yml",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertTrue(result["android"])
                self.assertFalse(result["desktop"])
                self.assertFalse(result["crypto"])

    def test_test_only_changes_do_not_build_packages(self):
        for path in (
            "buildPlugins/src/test/kotlin/CargoTest.kt",
            "scripts/test_verify_native_bundle.py",
            "util/io/src/desktopTest/kotlin/NativeIoTest.kt",
            "util/instance/src/commonTest/kotlin/InstanceTest.kt",
            "desktopApp/src/jvmTest/kotlin/com/artemchep/keyguard/desktop/instance/InstanceDirectoriesTest.kt",
            "iosApp/src/iosTest/kotlin/NativeBundleSmokeTest.kt",
            "util/io/rust/crates/keyguard-io-core/tests/atomic.rs",
            "util/crypto/rust/crates/keyguard-crypto-core/tests/properties.rs",
            "desktopLibNative/src/tests/encoding.rs",
            "desktopSshAgent/src/tests/socket.rs",
            "androidSshAgent/src/tests/peer.rs",
            "thirdParty/rust/ssh-key-0.6.7-keyguard/tests/keys.rs",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertFalse(any(result[flag] for flag in ("desktop", "android", "apple")))

    def test_native_helper_test_runs_all_hosts_without_packaging(self):
        result = classify(["desktopLibNative/src/tests/encoding.rs"])
        self.assertTrue(result["desktop_native"])
        self.assertTrue(result["desktop_run"])
        self.assertFalse(result["desktop"])
        self.assertEqual(len(result["desktop_matrix"]["include"]), 3)

    def test_windows_acl_regression_is_retained_on_windows(self):
        result = classify(["util/io/src/desktopTest/kotlin/com/artemchep/keyguard/util/io/atomic/WindowsOwnerOnlySecurityTest.kt"])
        self.assertTrue(result["io"])
        self.assertTrue(result["desktop_run"])
        self.assertIn("windows", [row["platform"] for row in result["desktop_matrix"]["include"]])
        self.assertFalse(result["desktop"])

    def test_io_native_changes_also_check_the_instance_consumer(self):
        for path in (
            "util/io/rust/crates/keyguard-io-core/src/windows_file.rs",
            "util/io/rust/crates/keyguard-io-core/src/windows_nt.rs",
            "util/io/rust/Cargo.toml",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertTrue(result["instance"])
                self.assertTrue(result["desktop_regressions"])
                self.assertIn("windows", [row["platform"] for row in result["desktop_matrix"]["include"]])

    def test_crypto_test_change_keeps_quality_without_fuzz_or_packages(self):
        result = classify(["util/crypto/rust/crates/keyguard-crypto-core/tests/properties.rs"])
        self.assertTrue(result["crypto"])
        self.assertFalse(result["fuzz"])
        self.assertFalse(result["desktop_run"])

    def test_cfg_test_crypto_module_keeps_quality_without_packages(self):
        result = classify(["util/crypto/rust/crates/keyguard-crypto-core/src/openpgp/message/read/tests.rs"])
        self.assertTrue(result["crypto"])
        self.assertFalse(result["fuzz"])
        self.assertFalse(result["desktop_run"])
        self.assertFalse(result["android_run"])

    def test_cfg_test_io_module_retains_quality_and_os_regressions(self):
        result = classify(["util/io/rust/crates/keyguard-io-core/src/crash_tests.rs"])
        self.assertTrue(result["native_quality"])
        self.assertTrue(result["io"])
        self.assertTrue(result["desktop_regressions"])
        self.assertEqual([row["platform"] for row in result["desktop_matrix"]["include"]], ["macos", "windows"])
        self.assertFalse(result["desktop"])
        self.assertFalse(result["android_run"])

    def test_cfg_test_agent_modules_remain_with_agent_workflows(self):
        for path in (
            "desktopSshAgent/src/src/agent/tests.rs",
            "desktopGpgAgent/src/src/assuan/tests.rs",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertFalse(any(result[flag] for flag in FLAGS))

    def test_standalone_termux_agent_does_not_rebuild_app_bundles(self):
        for path in (
            "androidSshAgent/src/src/main.rs",
            "androidSshAgent/src/Cargo.toml",
            "androidSshAgent/build.gradle.kts",
        ):
            with self.subTest(path=path):
                result = classify([path])
                self.assertFalse(any(result[flag] for flag in FLAGS))

    def test_shared_rust_agents_affect_desktop_helpers_but_not_apk_bundles(self):
        for path in ("commonAgent/src/lib.rs", "commonSshAgent/src/lib.rs"):
            with self.subTest(path=path):
                result = classify([path])
                self.assertTrue(result["desktop"])
                self.assertTrue(result["desktop_native"])
                self.assertFalse(result["android_run"])

    def test_production_rust_module_with_inline_tests_still_requests_packages(self):
        result = classify(["util/io/rust/crates/keyguard-io-core/src/txn.rs"])
        self.assertTrue(result["native_quality"])
        self.assertTrue(result["desktop"])
        self.assertTrue(result["android"])

    def test_android_test_edits_run_only_the_android_runtime(self):
        result = classify(["androidApp/src/androidTest/kotlin/com/artemchep/keyguard/test/io/NativeIoAtomicDirectorySmokeTest.kt"])
        self.assertTrue(result["android_run"])
        self.assertFalse(result["android"])
        self.assertFalse(result["desktop_run"])

    def test_platform_only_regressions_reuse_hosts_without_linux_duplication(self):
        result = classify(["util/io/src/desktopTest/kotlin/NativeIoTest.kt"])
        self.assertTrue(result["desktop_run"])
        self.assertFalse(result["desktop"])
        self.assertEqual([row["platform"] for row in result["desktop_matrix"]["include"]], ["macos", "windows"])

    def test_shared_probe_and_desktop_jna_loader_trigger_packages(self):
        shared = classify(["common/src/commonMain/kotlin/com/artemchep/keyguard/nativebundle/NativeBundleProbe.kt"])
        self.assertTrue(all(shared[flag] for flag in ("desktop", "android", "apple")))
        desktop = classify(["desktopLibJvm/src/jvmMain/kotlin/com/artemchep/keyguard/desktop/Native/DesktopLibJna.kt"])
        self.assertTrue(desktop["desktop"])
        self.assertFalse(desktop["android"])

    def test_ios_ui_does_not_request_native_linking(self):
        result = classify(["iosApp/src/iosMain/kotlin/com/artemchep/keyguard/UI.kt"])
        self.assertFalse(result["apple"])

    def test_targeted_manual_keeps_representative_hosts(self):
        result = classify([], all_checks=True)
        self.assertTrue(result["desktop"])
        self.assertTrue(result["android"])
        self.assertEqual(len(result["desktop_matrix"]["include"]), 3)
        self.assertFalse(result["wear"])

    def test_apple_only_changes_share_just_the_arm64_macos_host(self):
        result = classify(["util/zip/rust/Cargo.toml"])
        self.assertTrue(result["desktop_run"])
        self.assertFalse(result["desktop"])
        self.assertEqual([(row["platform"], row["arch"]) for row in result["desktop_matrix"]["include"]], [("macos", "aarch64")])

    def test_shared_toolchain_and_build_plugins_request_all_semantic_checks(self):
        for path in ("rust-toolchain.toml", "buildPlugins/src/main/kotlin/Cargo.kt", ".github/actions/prefetch_native/prefetch.sh", "gradle/libs.versions.toml"):
            with self.subTest(path=path):
                result = classify([path])
                self.assertTrue(all(result[flag] for flag in FLAGS if flag != "wear"))

    def test_security_policy_records_keep_validation(self):
        result = classify(["docs/security/native-crypto-exceptions.md"])
        self.assertTrue(result["crypto"])
        self.assertFalse(result["desktop"])

    def test_full_manual_matrix_covers_all_hosts_and_wear(self):
        result = classify([], full=True)
        self.assertTrue(all(result[flag] for flag in FLAGS))
        pairs = {(row["platform"], row["arch"]) for row in result["desktop_matrix"]["include"]}
        self.assertEqual(pairs, {("linux", "x86_64"), ("linux", "aarch64"), ("macos", "x86_64"), ("macos", "aarch64"), ("windows", "x86_64")})
        self.assertEqual(len(result["android_matrix"]["include"]), 2)

    def test_github_outputs_are_single_line_json(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            subprocess.run([
                sys.executable, str(Path(__file__).with_name("classify_native_changes.py")),
                "--github-output", str(output), "util/io/rust/Cargo.toml",
            ], check=True, stdout=subprocess.PIPE)
            values = {key: json.loads(value) for key, value in (line.split("=", 1) for line in output.read_text().splitlines())}
            self.assertTrue(values["io"])
            self.assertEqual(values["android_matrix"]["include"], [{"api": 26}, {"api": 30}])


class NativeGitComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Native CI Test")
        self.git("config", "user.email", "native-ci@example.invalid")
        source = self.root / "util/io/rust/Cargo.toml"
        source.parent.mkdir(parents=True)
        source.write_text("native fixture\n")
        self.git("add", ".")
        self.git("commit", "--quiet", "-m", "fixture")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *arguments):
        return subprocess.check_output(["git", *arguments], cwd=self.root, text=True)

    def test_rename_out_of_native_tree_preserves_removed_path(self):
        self.git("mv", "util/io/rust/Cargo.toml", "notes.txt")
        self.git("commit", "--quiet", "-m", "move")
        paths = changed_paths(self.base, cwd=self.root)
        self.assertEqual(set(paths), {"util/io/rust/Cargo.toml", "notes.txt"})
        self.assertTrue(classify(paths)["io"])

    def test_missing_history_is_distinct_from_no_changes(self):
        self.assertEqual(changed_paths(self.base, cwd=self.root), [])
        self.assertIsNone(changed_paths("0" * 40, cwd=self.root))
        self.assertIsNone(changed_paths("f" * 40, cwd=self.root))


if __name__ == "__main__":
    unittest.main()
