# Native startup regression tests

`Keyguard Startup Tests` compiles the real `KeyguardMacApp` scenes and `AppDelegate`.
The test host replaces storage, core construction, and instance acquisition with
fixtures. It has a separate bundle ID and no AppGroup entitlement. It cannot open
a user vault or register a login item. Test commands and construction counters
are compiled only into this host, under `KEYGUARD_STARTUP_TESTING`.

After building the shared XCFramework and generating Xcode projects, run:

```sh
sh xcode/scripts/test-macos-startup.sh -resultBundlePath apple-startup-tests.xcresult
```

This is a required GUI test: a missing console session is a failure, not a skip.
The host tests startup races, preference loading, cross-flavor focus requests,
AppKit lifecycle notifications, and last-window termination. UI tests check
main-root construction, reopening, and close-to-tray. SwiftPM tests exercise the
real focus-notification transport.

## Signed package acceptance

The fixture does not prove sandbox access, actual OS login registration, or
biometric prompt behavior. Run the installed-package acceptance scenarios in a
dedicated QA account with provisioned direct and App Store packages, on macOS 14
and the current supported macOS. Repeat with a long/non-ASCII account path.

The read-only evidence collector integrates with the session acceptance harness:

```sh
python3 xcode/scripts/verify-macos-startup-package.py \
  --app /Applications/Keyguard.app --case preflight --expected-os-major 14 \
  --output evidence/native-preflight.json
python3 xcode/scripts/verify-macos-startup-package.py \
  --app /Applications/Keyguard.app --case quiet --expected-os-major 14 \
  --output evidence/native-login.json
```

Use `--case visible` after a user reopen. These commands do not launch, quit,
register, or modify Keyguard. JSON distinguishes PASS, FAIL, and BLOCKED and
records the main executable hash. An entitlement declaration is not proof of
successful AppGroup access. Quiet visibility alone is not proof that a hidden
main root was never constructed.

The session scenarios must additionally verify:

- Two real logins, approval revocation/recovery, relocation, and upgrade.
- Both native flavors sharing the same AppGroup cannot run two vault owners.
- Launching one flavor while the other runs brings the running one to the front.
- Dock/menu-bar reopening, system-restored windows, screen lock/sleep, and
  biometric dismissal preserve quiet startup and the configured lock timeout.
- Quit releases ownership and stops child helpers; a crashed owner can restart.

Do not record these scenarios as passed from fixture tests or a package preflight.
