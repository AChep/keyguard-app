# Startup registration fixtures

Ordinary tests use temporary files, fake OS adapters, and private D-Bus connections.
They do not change the current account's startup registrations.

```sh
cargo test -p keyguard-launch-at-login-core -p keyguard-launch-at-login-c -p keyguard-launch-at-login-jni --all-targets --locked --offline
cargo clippy -p keyguard-launch-at-login-core -p keyguard-launch-at-login-c -p keyguard-launch-at-login-jni --all-targets --locked --offline -- -D warnings
bash util/launch-at-login/tests/macos/run.sh # macOS only
```

Run filesystem permission tests as an **unprivileged user**. Running them as root
fails explicitly because root bypasses the permission under test. Linux portal
tests require `dbus-daemon`; each test starts its own bus with no service activation
directories and stops it on exit. No session-bus environment variables are changed.
On macOS, authenticated Unix socket pairs exercise the same D-Bus request/response
logic without requiring a system bus. The Windows target runs real COM shortcut
tests in temporary directories rather than the account's Startup folder.

The Objective-C harness compiles the production operation logic against a fake
service. It tests approval/status refresh, registration/unregistration failures,
idempotence, diagnostic bounds, and launch-event classification. It does not
establish that a signed application will receive permission or run at login.

## Disposable OS registration helper

The `registration_fixture` example changes **real OS startup registration**. Build
it only for a disposable account/VM; it is not included in Keyguard distributions.

```sh
cargo build -p keyguard-launch-at-login-core --example registration_fixture --locked --offline
registration_fixture --config /absolute/run/config.json --operation query
registration_fixture --config /absolute/run/config.json --operation enable
registration_fixture --config /absolute/run/config.json --operation disable
```

The helper requires all three attestations before every operation:

- `account` matches the actual output of `whoami`.
- `KEYGUARD_STARTUP_DISPOSABLE_ACCOUNT` equals that same account name.
- A `DISPOSABLE_ACCOUNT` file beside the configuration contains exactly:

```text
disposable startup-test account
<whoami output>
<32-hex run_id>
```

Use a new random `run_id` for each isolated run. The helper refuses the production
application identity. Example configuration (replace the nonce/account/paths):

```json
{
  "run_id": "0123456789abcdef0123456789abcdef",
  "account": "fixture-user",
  "backend": "xdg",
  "id": "com.artemchep.keyguard.startuptest.0123456789abcdef0123456789abcdef",
  "launcher": "/opt/startup-fixture/bin/entrypoint",
  "arguments": ["--launch-at-login"]
}
```

For MSIX, use `id: Keyguard.Test.<run_id>` to fit Windows' 50-character package-name
limit. For Flatpak, use `id: com.artemchep.keyguard.startuptest.run<run_id>` so the
last component starts with a letter. Other targets use the identity shown above.

Backends and package prerequisites:

| Backend | Required execution environment |
| --- | --- |
| `xdg` | Linux outside Flatpak; `launcher` is the installed executable. Use the persistent AppImage path. |
| `windows` | Unpackaged Windows helper; `launcher` is the installed executable. The unique fixture ID names its Startup shortcut. |
| `msix` | Helper launched with the installed fixture package identity, matching `id`; it sends the `windows` target, which picks the startup task under package identity. Optional `task_id` defaults to `KeyguardStartup`. The manifest must declare that task disabled by default and include `uap10:Parameters="--launch-at-login"`. An unpacked MSIX is insufficient. |
| `macos` | Helper embedded in the installed fixture `.app`; `bundle` names that app. `CFBundleIdentifier` must equal `id`, and strict code-signature verification must succeed with a non-ad-hoc team identity. The app's main executable may be the session-evidence harness. |
| `flatpak` | Helper runs inside a separately installed fixture Flatpak whose ID equals `id`. `arguments` contains the complete in-sandbox command array. Portal permission still requires the desktop session. |

The helper prints JSON with the observed numeric statuses, operation, run identity,
and `cleanup_complete`. The separate session observer and platform installer tests record login-session
receipts and upgrade/uninstall observations; this helper does not
claim a successful login or approval from a successful registration call.

Before first enable, the helper refuses existing registrations. A fresh fixture
Flatpak has no marker, so it reports `DISABLED`. Each helper run is a new process
and reads status before its operation. When the Flatpak marker exists, that read
repeats the portal request, so a `disable` run asks the portal to enable before it
disables. Before mutation the helper creates a
`registration.owned` marker. Failed operations retain it so cleanup can be retried.
Disable refuses to run without that marker and removes it only when the OS reports
disabled. Policy-managed, approval-pending, unknown, and denied results remain
observations rather than proof of effective enablement.

Always run disable in the orchestrator's `finally` step **before** uninstalling the
fixture or deleting its configuration. Assert `cleanup_complete=true`; preserve
the marker and logs on failure. Restore the VM snapshot when OS policy or approval
prevents cleanup. Never reset all portal permissions, remove arbitrary login items,
or clear the account's Startup folder. Keep unrelated sentinel registrations and
assert they are unchanged. Moving a fixture executable may retain a stale entry;
reuse its run identity and ownership marker for explicit repair and later cleanup.

The helper fails clearly when signing, package identity, OS version, or portal
prerequisites are absent. Real minimum-Windows-version behavior, administrator
policy, user approval/revocation, logout/login, and installed-package upgrades still
require the corresponding disposable OS session; unit and metadata fixtures are
not substitutes for those runs.

## Fixture package staging

`stage_fixture.py` creates a separate macOS app or MSIX source directory around
the built registration helper and an evidence entrypoint supplied by the acceptance
harness. It refuses existing output directories and production identities:

```sh
python3 util/launch-at-login/tests/stage_fixture.py --platform macos \
  --output /absolute/new-stage --id com.artemchep.keyguard.startuptest.<run_id> \
  --helper /absolute/registration_fixture --entry /absolute/evidence-entrypoint
```

The macOS result is the main bundle.
Sign both executables and then the app using the disposable test signing identity;
install it in the disposable account before running the embedded helper. For MSIX,
use `--platform msix --id Keyguard.Test.<run_id> --publisher 'CN=Test Certificate'`
and `--architecture x64|arm64`. Pack with the Windows SDK's `makeappx`, sign with
the matching test certificate, and install in the disposable account. The `Entry`
application owns the disabled-by-default startup task; the `Registration`
application is the helper and must be activated with that installed identity.
Staging validates structure, not SDK installation/signing or real activation.

```sh
python3 -m unittest discover -s util/launch-at-login/tests -p 'test_*.py'
```

The existing ARM64 MSI upgrade/uninstall script accepts `-RegistrationFixture`
with the built example executable. It uses a nonce-owned shortcut to the installed
application, verifies valid registration after upgrades, verifies the inert stale
entry left after uninstall, then explicitly cleans it. It also verifies that an
unrelated Startup shortcut and vault sentinels survive.
