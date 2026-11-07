# Native iOS E2E

Drives the shipping SwiftUI app through XCTest and checks results with
independent oracles. The runner and its fixture app do not link Keyguard or
replace its bridge, storage or network services. Everything runs on loopback
with synthetic accounts and a dedicated simulator.

[SESSION.md](SESSION.md) explains how to run a test session. This file
documents the commands.

## Layout

```
e2e            launcher: e2e <command> [options]; e2e help lists commands
core/          session steps: bootstrap, driver, baseline, smoke
servers/       loopback fixture servers (websites, OIDC, SMTP, TLS proxies)
fixtures/      scripts that create or change test data
oracles/       read-only checks of server, export and file contents
lib/           config (accounts, ports), session paths, Bitwarden client, journeys
Tests/ Host/   XCTest runner and its host app (project.yml)
SSHReceiver/   simulator-only ssh:// URL receiver app
```

Run commands from the repository root, for example
`integration/iosNativeE2eTest/e2e smoke`. Each command writes to the session
folder, `build/ios-native-e2e/current/`, unless told otherwise.

## Requirements

Xcode, XcodeGen, Docker, Python 3, OpenSSL and a JDK. Some fixtures need more
tools; their sections list them.

## Setup

```sh
export E2E_DEVICE=<UDID>   # a dedicated simulator: xcrun simctl list devices available
integration/iosNativeE2eTest/e2e bootstrap
```

`bootstrap` is safe to rerun. It:

- creates the session folder `build/ios-native-e2e/<date>/` and points
  `build/ios-native-e2e/current` at it (`--session NAME` picks another name,
  `E2E_SESSION` another folder);
- installs [`requirements.txt`](requirements.txt) into `build/ios-native-e2e/venv`,
  which `e2e` then uses;
- creates a test CA, server and client certificate in `<session>/tls/` and
  trusts the CA on `$E2E_DEVICE`. The client identity is `client.p12`,
  password `P12-E2e-2026!`;
- starts the Vaultwarden servers in [`lib/config.py`](lib/config.py) with
  `server/bw/bw`, reusing running ones (`--servers main,sso,mail` limits them);
- creates the accounts on them, with seed data, the organization and
  authenticator 2FA. The 2FA secret is saved to `<session>/accounts/2fa-totp.json`;
- records server URLs and account IDs in `<session>/session.json`.

Accounts on the mail server are created only while `e2e smtp_sink` runs.
Account creation runs Gradle; wait for it before starting another Gradle build
in this checkout, as both share generated KSP outputs.

## Build

```sh
./gradlew :appleApp:assembleKeyguardSharedIosSimulatorArm64DebugXCFramework
xcode/scripts/generate-projects.sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme Keyguard \
  -configuration Debug -destination "platform=iOS Simulator,id=$E2E_DEVICE" \
  -derivedDataPath build/xcode/ios-native-e2e \
  KEYGUARD_BUNDLE_ID=com.artemchep.keyguard.ios.e2e \
  KEYGUARD_APP_GROUP_ID=group.com.artemchep.keyguard.ios.e2e build
xcrun simctl install "$E2E_DEVICE" \
  build/xcode/ios-native-e2e/Build/Products/Debug-iphonesimulator/Keyguard.app
xcodegen generate --spec integration/iosNativeE2eTest/project.yml
xcodebuild -project integration/iosNativeE2eTest/KeyguardNativeE2e.xcodeproj \
  -scheme NativeE2e -configuration Debug \
  -destination "platform=iOS Simulator,id=$E2E_DEVICE" \
  -derivedDataPath build/ios-native-e2e/runner build-for-testing
```

The app uses a dedicated bundle ID and App Group. The shared keychain access
group still follows the shipping entitlements, so use only a dedicated simulator.

## Driver

Start the broker, then the runner, each in its own terminal:

```sh
integration/iosNativeE2eTest/e2e driver serve
xcodebuild -project integration/iosNativeE2eTest/KeyguardNativeE2e.xcodeproj \
  -scheme NativeE2e -configuration Debug \
  -destination "platform=iOS Simulator,id=$E2E_DEVICE" \
  -derivedDataPath build/ios-native-e2e/runner \
  -parallel-testing-enabled NO -test-timeouts-enabled NO test-without-building
```

The broker binds to `127.0.0.1:8233`; the runner polls it from the simulator
and runs one command at a time. Commands and results are journaled to
`<session>/actions.jsonl` without typed text or screenshot bytes. UI trees and
value reads can contain vault content.

```sh
e2e driver launch
e2e driver tree
e2e driver tap --type button --label 'Add account'
e2e driver type --type secureTextField --text 'synthetic-test-password'
e2e driver value --type textView --label 'Message'
e2e driver swipe --type button --label 'Lock vault' --direction up
e2e driver drag --x 200 --y 450 --toX 200 --toY 250 --duration 0.6 --endHold 1
e2e driver adjustSlider --type slider --label Length --position 0.5
e2e driver doubleTap --type image --label e2e-image.png
e2e driver pinch --type image --label e2e-image.png --scale 2
e2e driver screenshot --output screen.png
e2e driver orientation --orientation landscapeLeft
e2e driver accessibilityAudit
e2e driver deviceInfo
e2e driver systemTree
e2e driver systemTap --type button --label Cancel
e2e driver stop
```

(`e2e` stands for `integration/iosNativeE2eTest/e2e`.) Elements match by
`--type`, `--label`, `--identifier` or `--placeholder`, with `--contains` and
`--index`. Without `--index` the driver prefers a hittable match.
`adjustSlider` takes a position from 0 to 1. `drag` durations and `--endHold`
are between 0 and 3 seconds. `pinch` takes a positive scale other than 1.
`system*` commands target SpringBoard alerts. Also available: `hold`,
`typeFocused`, `tapPoint --x --y`, `home`, `terminate`, and `activate --bundle` /
`selectApplication --bundle` to switch the target app.

The runner also bundles `xcode/Keyguard.storekit`. `storekitStart` creates an
`SKTestSession`; `storekitConfigure` accepts `--disableDialogs`, `--askToBuy`,
`--failPurchase` and `--failRestore`. `storekitStatus`, `storekitReset`,
`storekitRefund`, `storekitDisableAutoRenew`, `storekitApprove`,
`storekitDecline`, `storekitRenew` and `storekitExpire` control test
transactions (`--productId`, `--transactionId`). Purchases still go through
the app's buttons. StoreKit sessions share one test environment, so run these
checks serially.

### SwiftBridgeTests

The project also has a `SwiftBridgeTests` scheme that runs the file picker and
client certificate model tests from `appleUi/Tests` on the simulator, hosted by
the fixture app. It needs the KeyguardShared XCFramework from the build above.
Stop the driver first:

```sh
e2e driver stop
xcodebuild -project integration/iosNativeE2eTest/KeyguardNativeE2e.xcodeproj \
  -scheme SwiftBridgeTests -configuration Debug \
  -destination "platform=iOS Simulator,id=$E2E_DEVICE" \
  -derivedDataPath build/xcode/ios-native-e2e-unit \
  -parallel-testing-enabled NO test
```

## Baseline

```sh
e2e baseline
e2e smoke
e2e navigation_regression --device "$E2E_DEVICE"
```

`baseline` brings a fresh app to the state the other journeys expect: a local
vault with password `Local-E2e-2026!`, the main account signed in, the
Development premium switch on, and the login `iOS E2E Login`. It skips steps
that are already done.

`smoke` checks the baseline login in the app and, decrypted independently, on
the server. Then it locks the vault, checks that a wrong password is rejected,
unlocks, relaunches the app and unlocks again. It leaves the app unlocked.

`navigation_regression` keeps the baseline login open while switching through
Settings to Generator and GPG tools, twice, and checks that the detail and its
Copy action still work.

`e2e vault [--account KEY] [--sends]` prints an account's decrypted items or
Sends.

## Servers

Local fixture servers. Each binds only to `127.0.0.1`, runs in the foreground
until Ctrl+C, and keeps its evidence in a named folder of the session
(`build/ios-native-e2e/current/<folder>`). Evidence files are owner-only (0600 in
0700 folders). Default ports come from `PORTS` in `lib/config.py`.

HTTPS servers use the session's test server certificate (`tls/server.pem`) by
default. Its names cover `localhost`, `127.0.0.1`,
`keyguard-native-e2e.127.0.0.1.nip.io` and its subdomains. The simulator must
trust the test CA: run `e2e bootstrap --device <UDID>` once.

### autofill_web

Login, one-time code and passkey page on `https://localhost:8240/`. Passwords
must match the main account's `BASELINE_LOGIN` or `Example Site 1`; codes must
match `Example Site 1`'s seed, current step ±1. Passkey registration and
assertion are verified as a WebAuthn relying party (RP ID `localhost`): challenge, origin, RP hash,
flags, P-256 key, ES256 signature and counter.

```sh
integration/iosNativeE2eTest/e2e autofill_web [--baseline FILE] [--certificate PEM --private-key KEY]
```

- Needs: the main account (bootstrap). Without `--baseline`, it logs in to the main
  server at startup and reads the items. `--baseline` takes a JSON list as
  written by `lib.bitwarden.snapshot`.
- `https://keyguard-native-e2e.127.0.0.1.nip.io:8240/` serves the same page on a
  DNS name for domain matching; passkey controls are hidden there.
- Evidence: `autofill-web/events.jsonl` (no passwords or codes),
  `credentials.json` (credential IDs, public keys, counters). It never reads
  Keyguard's private passkey material.

### passkey_errors_web, passkey_prf_web

Separate relying parties, with their own synthetic users and credential stores,
using the `autofill_web` verifier. Run them next to `autofill_web`.

```sh
integration/iosNativeE2eTest/e2e passkey_errors_web [--port 8248] [--directory DIR]
integration/iosNativeE2eTest/e2e passkey_prf_web [--port 8252] [--directory DIR]
```

- `passkey_errors_web` (`https://localhost:8248/`): registration, registration
  excluding the owned credential, an RS256-only request, known and unknown
  credential assertions, discovery without an allow list. Negative ceremonies
  that return a credential are rejected before anything is stored.
- `passkey_prf_web` (`https://localhost:8252/`): PRF at registration, same and
  changed inputs, first input only, per-credential override, no PRF, an invalid
  credential map. Responses go to `passkey-prf-web/responses.jsonl`;
  `e2e verify_passkey_prf` checks them against the leased item's secret.
- Register the owned passkey first; assertion modes return 409 until then.
- Client errors record only the mode and DOM error name.

### otp_web

One-time code page with no password fields, so Safari shows no password panel.
Open `https://keyguard-native-e2e.127.0.0.1.nip.io:8247/otp` (also accepted on
`sub.keyguard-native-e2e.127.0.0.1.nip.io`).

```sh
integration/iosNativeE2eTest/e2e otp_web [--baseline FILE] [--evidence DIR] [--certificate PEM --private-key KEY]
```

- Needs: the main account, as for `autofill_web`. Checks codes against `Example
  Site 1`'s seed, current step ±1.
- The `nip.io` names must resolve only to `127.0.0.1`.
- `otp-web/events.jsonl` records the visited host and path, and whether a code
  was accepted; never codes or seeds.

### oidc, sso_tls

The SSO server (8223) signs in through `oidc` and is reached by the app at
`https://127.0.0.1:8224` through `sso_tls`.

```sh
integration/iosNativeE2eTest/e2e oidc
integration/iosNativeE2eTest/e2e sso_tls [--port 8224] [--backend-port 8223] [--evidence DIR]
                                         [--require-client-cert] [--required-header NAME=VALUE]
```

- `oidc` (`http://127.0.0.1:8238`): signs in only the `sso` account, after an
  Approve or Deny tap. Client ID, secret and issuer come from the SSO server's
  environment in `lib/config.py`. The issuer is `host.docker.internal:8238` so the
  container can reach it. Signing key and events (no codes or tokens) are in `oidc/`.
- `sso_tls`: HTTPS proxy to a loopback HTTP server; relays WebSockets.
  `sso-tls/tls-requests.jsonl` records method, path, status and client
  certificate hash; no bodies or queries.
  - `--require-client-cert`: require a certificate from the test CA
    (`tls/client.p12`, password `P12-E2e-2026!`).
  - `--required-header NAME=VALUE`: answer 403 without it.
  - Sync fault: write `{"securityStamp": "..."}` or `{"id": "..."}` to
    `<evidence>/sync-fault.json` to replace that field in `/api/sync` for the
    `sso` account. Delete the file to stop.
- HTTPS for the mail server, in its own evidence folder:
  `e2e sso_tls --port 8245 --backend-port 8244 --evidence build/ios-native-e2e/current/mail-tls`.

### download_transport

HTTPS proxy on `https://127.0.0.1:8241` to the `download` account's server
(8223). Sign in that account at this URL in Keyguard. The proxy looks up the
account's one attachment at startup and rewrites only that attachment's URL
to point at the proxy.

```sh
integration/iosNativeE2eTest/e2e download_transport [--evidence DIR]
```

- Needs: the `download` account with exactly one attachment (bootstrap seeds it).
- `<evidence>/control.json` is read on each blob GET: `{"mode":"healthy"}`,
  `{"mode":"error"}` (503) or `{"mode":"slow"}` (45 s pause). Other requests
  pass through unchanged. Restore `healthy` afterwards.
- `download-transport/requests.jsonl` omits credentials, bodies, queries and
  response bodies. `GET /health` answers `ok`.

### smtp_sink

SMTP on `127.0.0.1:8242` for the mail server (8244), with a read-only API on
`http://127.0.0.1:8243`: `/health`, `/api/messages` (no bodies),
`/api/messages/<id>`.

```sh
integration/iosNativeE2eTest/e2e smtp_sink [--data-dir DIR] [--smtp-port 8242] [--api-port 8243]
```

- Start it before `e2e bootstrap` creates the `send` account.
- Accepts only `@keyguard.test` recipients. Never relays or connects out.
- Raw messages and parsed bodies stay in `smtp-sink/`.

### simplelogin_fixture

SimpleLogin-compatible API on `http://127.0.0.1:8246`. Use it as the custom
server URL with API key `SimpleLogin-E2e-Token-2026!`.

```sh
integration/iosNativeE2eTest/e2e simplelogin_fixture [--evidence DIR]
```

- Generates `ios-relay-NNNN@keyguard.test` strings; sends no mail, makes no
  outbound requests. A wrong API key gets 401.
- Control, with header `X-Keyguard-Fixture-Key: Relay-Control-E2e-2026!`:
  `GET /fixture/state`; `POST /fixture/control` with `{"mode": ...}`:
  `normal`, `unavailable` (503), `missing_alias` (200 without `alias`),
  `disconnect` (closes the connection). Restore `normal` afterwards.
- `simplelogin/requests.jsonl` omits API keys.

### local_gpg_keyserver

VKS keyserver on `https://127.0.0.1:8251` for one synthetic public key.

```sh
integration/iosNativeE2eTest/e2e local_gpg_keyserver --public-key KEY.asc --fingerprint FPR \
  --email NAME@keyguard.test [--directory DIR] [--port 8251]
```

- Needs `gpg`. Uploads must be that public key; secret packets and other
  fingerprints are rejected.
- Control, with header `X-Keyguard-E2E-Control: keyguard-local-vks-control`:
  `POST /control` with `{"mode": "normal" | "unavailable" | "mismatch"}` and/or
  `{"published": true}`. Lookups return 404 until `published`, which stands in
  for address verification. `GET /state` shows uploads and requests.
- Verification requests are recorded, never mailed. Not a full VKS
  implementation.

### icon_network_web

Two favicon origins, `https://127.0.0.1:8253` and `https://127.0.0.1:8254`,
serving an HTML page and a PNG icon.

```sh
integration/iosNativeE2eTest/e2e icon_network_web [--directory DIR]
```

- Records each HTML and PNG request (port, path, User-Agent) in
  `icon-network-web/state.json`; `GET /state` returns it without being
  recorded. No outbound relay.
- `e2e prepare_icon_network` makes the KeePass fixture pointing at these origins.

### login_branch_fixture

Server discovery and CAPTCHA-contract checks for the login screen. Registers
`_bitwarden` and `_vaultwarden` TXT records for `kg-ios-login-branches.local`
on the LocalOnly interface: no DNS configuration changes, no LAN
advertisements. Records are removed on exit.

```sh
integration/iosNativeE2eTest/e2e login_branch_fixture
```

- Gateway `https://127.0.0.1:8256` forwards to the main server (8222).
  With the gate on, a password grant for `ios-login-branches@kg-ios-login-branches.local`
  gets `HCaptcha_SiteKey` until `captchaResponse` is `Client-Secret-E2e-2026`;
  the field is removed before forwarding. `GET /` shows the secret with a Copy
  button. Not a real CAPTCHA. No WebSocket relay.
- Control `http://127.0.0.1:8255`: `GET /` returns state; `POST /` with
  `{"owner": "keyguard-ios-native-e2e-login-branches", "dnsMode": "invalid" | "foreign" | "same", "captcha": true}`;
  `POST /stop` with the same `owner` stops the fixture.
- Check the records with `dns-sd -lo -Q _bitwarden.kg-ios-login-branches.local TXT`.
- Refuses to start if `login-branch-web/` already exists in the session.
  Evidence omits passwords, bodies, authorization and query strings.

### Remote storage: WebDAV and S3

Remote KeePass and backup journeys use the same servers as
`integration/webdavE2eTest` and `integration/s3E2eTest`. Install the versions
pinned in `.github/workflows/check_webdav_e2e.yaml` (hacdias/webdav) and
`check_s3_e2e.yaml` (versitygw), and check the release archives against the
published SHA-256. Keep storage in the session folder.

WebDAV on `http://127.0.0.1:8235`, user `ios-e2e`, password `WebDav-E2e-2026!`.
Write `<session>/remote/webdav.yml` and run `webdav --config <session>/remote/webdav.yml`:

```yaml
address: 127.0.0.1
port: 8235
tls: false
directory: <session>/remote/webdav
permissions: CRUD
users:
  - username: ios-e2e
    password: WebDav-E2e-2026!
```

S3 on `http://127.0.0.1:8236`, region `us-east-1`, bucket `ios-native-e2e`,
access key `ios-e2e-access`, secret `S3-E2e-Secret-2026`:

```sh
versitygw --port 127.0.0.1:8236 --access ios-e2e-access --secret S3-E2e-Secret-2026 \
  --region us-east-1 posix <session>/remote/s3
```

For virtual-hosted S3, run a second gateway on `https://127.0.0.1:8237` with
`--virtual-domain ios-native-e2e.localhost`, `--cert <session>/tls/server.pem`,
`--key <session>/tls/server.key` and storage `<session>/remote/s3-tls`. The
session certificate covers `ios-native-e2e.localhost` and its subdomains.

Create the bucket and check wrong-credential rejection with an independent S3
or WebDAV client before opening the app.

## Server fixtures and oracles

Server scripts log in with the synthetic accounts in `lib/config.py` and read IDs
from the server; none stores server-generated IDs. All requests go through
`lib/bitwarden.py`: loopback only, no proxies, no redirects, tokens and keys in
memory. Decryption uses Python's standard library and OpenSSL, never Keyguard
code. Private state and decrypted captures are written owner-only (0600) to the
session folder.

Fixtures that write to an item the app created use a **lease**: a JSON file in
the session that you write by hand after creating the item natively. It names
the item's server ID and records native state (open editors, a queued offline
edit). The script refuses to write unless the lease allows it, records its own
progress in the lease before every write, and then checks the exact expected
decrypted item and that every other item is unchanged.

Many leased fixtures also take `--before`: a vault snapshot taken before the
native item was created. The leased ID must not be in it, so an original item
can never be leased. Capture it with `e2e vault > <session>/<name>.json`.

Keep the app's password checks (breach lookups) off for all of these.

### vault

Prints an account's independently decrypted items, or its Sends, as JSON.
Use it to capture baselines for the other scripts.

```sh
integration/iosNativeE2eTest/e2e vault [--account main] [--sends] > FILE
```

- Sync requests send the `Bitwarden-Client-Version` header; without it
  Vaultwarden omits newer item types such as SSH keys.
- The output holds synthetic secrets: keep it in the session folder.

### organization

Reads the `org` account's organization (`iOS E2E Shared Vault`): its collections,
with names decrypted through the RSA-unwrapped organization key, and its ciphers.
`--member` reads as `org-member`. Two setup modes each run once:
`--add-secondary-collection` adds a `Secondary` collection to the fresh
one-collection, one-cipher organization; then `--prepare-restricted-member`
invites `org-member` to `Default` only, read-only with hidden passwords, and
confirms it with the RSA-wrapped organization key.

```sh
integration/iosNativeE2eTest/e2e organization --output FILE
integration/iosNativeE2eTest/e2e organization --add-secondary-collection --output FILE
integration/iosNativeE2eTest/e2e organization --prepare-restricted-member --output FILE
integration/iosNativeE2eTest/e2e organization --member --output FILE
```

- Needs: the `org` and `org-member` accounts (bootstrap creates both; the
  organization comes from `bw org`).
- Setup modes record the attempt in `organization-fixture-state.json` before
  writing and refuse to run again. The member invite is auto-accepted because
  the main server has no mail; setup stops if it is not. No mail is sent.
- Setup checks that shared data is unchanged. Native sign-in and permission
  behavior still need UI evidence.

### organization_environment

Leases one environment item in `Default`, referencing the shared Login's
password, and switches `org-member`'s Hide passwords flag on that collection.
`setup` creates the item and clears the flag; `hide` sets it again for denial
tests; `cleanup` sets it and deletes only the leased item.

```sh
integration/iosNativeE2eTest/e2e organization_environment setup|hide|cleanup
```

- Needs: both `e2e organization` setup modes.
- Sign the member out of the app before every mode. Track native sign-in by hand
  in `organization-fixture-state.json`: `"nativeMemberSignedIn": true` while
  signed in, `"nativeMemberCleanupPending": true` while sign-out is owed. Any of
  these set in either state file blocks all modes.
- State, including the owner, member and main-account baselines, is in
  `organization-environment-fixture-state.json`. `setup` runs once; `cleanup`
  requires the exact owner and member baselines. Every mode checks that the
  main account, the member's role, read-only flag, groups and scope, and the
  other shared data are unchanged.

### environment

Decodes an environment item from a saved snapshot: joins the hidden
`keyguard.environment.v1.part.N` chunks in order, checks the SHA-256 field and
parses the version-1 document. Prints variable names only. Offline.

```sh
integration/iosNativeE2eTest/e2e environment SNAPSHOT [--name "iOS E2E Environment"]
```

- Other scripts import `decode_environment(item)`.

### environment_variant

Writes a future-format document to the leased environment item, then restores
it. `future-field` adds an unknown top-level key; `future-source` adds a
variable with an unknown source kind next to a known literal.

```sh
integration/iosNativeE2eTest/e2e vault > <session>/environment-empty-document-server.json
integration/iosNativeE2eTest/e2e environment_variant future-field|future-source [--baseline FILE]
# Check the app's handling.
integration/iosNativeE2eTest/e2e environment_variant restore
```

- Lease `environment-advanced-fixture-state.json`, after creating an empty
  environment `iPhone E2E Environment Advanced` in the main account:

  ```json
  {"itemId": "<server ID>", "name": "iPhone E2E Environment Advanced",
   "itemCleanupPending": true, "editorOpen": false,
   "newOwnershipDraftOpen": false, "referenceSourceEditorOpen": false}
  ```

- `--baseline` (default `environment-empty-document-server.json`) is the item's
  exact decrypted value, or a snapshot list that contains it. A variant runs
  only when the item still equals it.
- Close every native environment draft first. Restoration data goes to
  `environment-server-variant-state.json` before each write. Run `restore` after
  every variant; it refuses to overwrite an edit made in between.

### bitwarden_peer

Applies a fixed edit to the leased Login as another client would, while the app
holds a queued offline edit of the same item. Cases: `different-field` and
`same-field` (notes), `password` (also appends password history), `totp`,
`hidden` (removes the only hidden field). Each case runs once.

```sh
integration/iosNativeE2eTest/e2e bitwarden_peer capture CASE [--before FILE]
# Take the server offline, edit natively, terminate the app, restore the server.
integration/iosNativeE2eTest/e2e bitwarden_peer peer CASE
```

- Lease `bitwarden-offline-fixture-state.json`, after creating the Login
  `iPhone E2E Bitwarden Offline` (with one hidden field `E2E hidden secret` =
  `BW-Hidden-Base-2026!` for the `hidden` case):

  ```json
  {"fixtureName": "iPhone E2E Bitwarden Offline", "itemId": "<server ID>",
   "itemCleanupPending": true, "editorOpen": false,
   "serviceRestorationPending": false, "appTerminatedForPeer": false,
   "offlineEditPending": false, "peerWritePending": false, "phases": []}
  ```

  Set `editorOpen` while a native editor is open and `serviceRestorationPending`
  while the server is unreachable; both block every action. `peer` requires
  `offlineEditPending` and `appTerminatedForPeer`. Clear them after native
  resolution and set the phase's `resolvedVerified` before the next `capture`.
- `--before` defaults to `bitwarden-offline-source-before.json`.
- `peer` requires the remote item to equal the captured baseline.
- Outage: `bw start` keeps the database in RAM, so never stop or pause the
  container. Disconnect only that container from its Docker network and
  reconnect it in a `finally`, then check `/alive`. Pausing buffers requests and
  gives no real conflict.

### watchtower_positive

Sets three github.com URIs, with fixed match modes, on the leased Login
(`configure`), or removes them (`clear`). The domain is in the app's bundled
2FA and passkey catalogs, so Watchtower flags the item. The website is never
contacted.

```sh
integration/iosNativeE2eTest/e2e watchtower_positive configure [--before FILE]
# Sync and check the Watchtower categories in the app.
integration/iosNativeE2eTest/e2e watchtower_positive clear
```

- Lease `watchtower-positive-fixture-state.json`, after creating the Login
  `iPhone E2E Watchtower Categories` without URIs:

  ```json
  {"ownedItems": [{"kind": "login", "name": "iPhone E2E Watchtower Categories",
                   "uuid": "<server ID>"}],
   "itemCleanupPending": true, "editorOpen": false, "uriVariantWritePending": false}
  ```

- `--before` defaults to `watchtower-positive-source-before.json`.
- Each mode refuses unless the current URIs are exactly the other mode's result.

### website_breach_fixture

Sets `https://adobe.com` and a 2010 password-change date on the leased Login, so
the cached public breach list flags the website. Runs once per lease. HIBP and
the website are never contacted.

```sh
integration/iosNativeE2eTest/e2e website_breach_fixture [--before FILE]
```

- Lease `website-breach-fixture-state.json`, after creating the Login
  `iPhone E2E Website Breach` without URIs or custom fields:

  ```json
  {"fixtureName": "iPhone E2E Website Breach", "itemId": "<server ID>",
   "itemCleanupPending": true, "editorOpen": false,
   "serverPreparationPending": false, "serverPrepared": false}
  ```

- `--before` defaults to `website-breach-source-before.json`.
- Record the app's breach cache before native checks. Changing the password
  natively should clear the account warning while the website's breach stays.

### prepare_email_twofa

Enables email 2FA on the `send` account: requests the activation mail, reads its
code from the mail sink and checks that a password login is then challenged
for email 2FA. Contacts only the mail server and the sink API on loopback.

```sh
integration/iosNativeE2eTest/e2e prepare_email_twofa [--output FILE]
```

- Needs: `e2e smtp_sink` running, and an empty `send` account without email 2FA
  (purge its Sends first).
- Refuses to run when the state file (default `email-twofa-fixture-state.json`)
  exists. The state holds an owner access token for read-only checks and
  cleanup: keep it in the session.
- Wrong code, resend, Back and successful login run natively.

### two_factor

Prints the current authenticator code of the `2fa` account from the secret
bootstrap saved in `accounts/2fa-totp.json`. Independent RFC 6238 (SHA-1, six
digits, 30 s).

```sh
integration/iosNativeE2eTest/e2e two_factor [--account 2fa]
```

- Vaultwarden rejects a reused step, and enabling 2FA already used one: take a
  fresh code for each successful login.
- Other scripts import `totp(secret, step)`, `secret(key)` and
  `login(key, provider, code, remember)`.

### otp

Self-test of the offline HOTP/TOTP, Steam and Mobile-OTP oracle: RFC 4226
counters, RFC 6238 SHA-1/256/512 vectors and an OpenSSL-checked Mobile-OTP
value. Steam follows PyOTP; Mobile-OTP follows the original client.

```sh
integration/iosNativeE2eTest/e2e otp
```

- Import `code(raw, timestamp)` and `codes_during(raw, start, end)`. The latter
  accepts only periods that intersect the recorded native operation (at most
  120 s), not a skew window. `e2e otp_web` uses `code`.

### gpg_oracle, gpg_v6_oracle

Decode a GPG item's direct or chunked `keyguard.gpg.*` fields from a saved
snapshot and inspect the keys offline. `gpg_oracle` uses GnuPG: public and
private key metadata must match each other and the stored fingerprint, and
every self-signature must verify. `gpg_v6_oracle` is for v6 keys, which GnuPG
cannot list: it uses stateless Sequoia `sq` to compare fingerprints,
algorithms, packet versions, identities and expiry, and round-trips a signed,
encrypted message.

```sh
integration/iosNativeE2eTest/e2e gpg_oracle SNAPSHOT --output DIR [--name "iPhone E2E GPG Advanced"]
integration/iosNativeE2eTest/e2e gpg_v6_oracle SNAPSHOT --output NEW_DIR [--name ...]
```

- Needs: `gpg`/`gpgconf`, or `sq`.
- No user keyring, key store or network. GnuPG runs in a short temporary home
  under `/private/tmp` (socket path limit) that is removed with its agent.
- `--output` holds private armor (0600). Use a new folder per key revision;
  differing evidence is never overwritten (`gpg_v6_oracle` requires a new folder).

### keepass

Prints a synthetic KDBX database's groups, entries (decrypted values) and
attachment hashes with PyKeePass.

```sh
integration/iosNativeE2eTest/e2e keepass DATABASE [--password P] [--keyfile KEY]
```

- Runs in the harness virtualenv. Omit `--password` for keyfile-only databases.

### verify_export_json

Compares a decrypted native `vault.json` export with the main account: remote
IDs, common fields, credentials and TOTP, URIs, card/identity/SSH fields,
custom fields (including GPG and environment transport), attachment metadata,
folders and password history. The single exported attachment file must match
its proof.

```sh
integration/iosNativeE2eTest/e2e vault > <session>/export-baseline.json
# Export natively; decrypt the ZIP with 7-Zip, or the age file with age.
integration/iosNativeE2eTest/e2e verify_export_json EXPORT/vault.json \
  --baseline <session>/export-baseline.json --output PROOF \
  [--attachment-proof FILE]
```

- `--attachment-proof` (default `attachment-native-export-proof.json`):
  `{"file": "<name>", "bytes": <size>, "sha256": "<hex>"}` of the seeded
  attachment, recorded from a native attachment export.
- The live server must still equal the baseline. Absent notes and card brands
  compare equal to empty strings.

### verify_import_corpus

Checks a native import on the `import` account: items added since `--before`
must match CASE's source values, and earlier items must be unchanged. Cases:
`bitwarden-json`, `bitwarden-protected-pbkdf2`, `bitwarden-protected-argon2id`,
`bitwarden-csv`, `keepassxc-csv` (pinned repository corpus), `keyguard-zip`
(decrypted legacy export in `exports/legacy-keyguard-decrypted/`), and the cases
in `browser-import-fixtures.json`, `manager-import-fixtures.json` and
`archive-manager-import-fixtures.json`.

```sh
integration/iosNativeE2eTest/e2e vault --account import > <session>/import-start.json
# Import natively.
integration/iosNativeE2eTest/e2e verify_import_corpus CASE --before <session>/import-start.json
integration/iosNativeE2eTest/e2e verify_import_corpus CASE2 \
  --before <session>/import-CASE-destination-decrypted.json \
  --before-extended <session>/import-CASE-extended.json
```

- Writes `import-CASE-destination-raw.json`, `-destination-decrypted.json`,
  `-extended.json` (folders, archive flag, URI match modes, passkeys, history)
  and `-server-proof.json`. Chain them as the next `--before`.
- `--after-prefix` uses a saved capture instead of a live read.
- For `keyguard-zip`, the attachment is downloaded through the `sso` server's
  TLS proxy (`e2e sso_tls`), authenticated and compared byte for byte.
- Failed item checks are listed in the proof and exit nonzero. Timestamps are
  not compared: imports create new records. Password history must stay empty.

### verify_archive_imports

Runs `verify_import_corpus` for an archive case and then checks its attachment:
exactly one attachment among the items the import created, MAC-authenticated,
decrypted and equal to the manifest's name, size, SHA-256 and content.

```sh
integration/iosNativeE2eTest/e2e verify_archive_imports CASE \
  --before-prefix <session>/import-PREVIOUS --owned-id ID [--owned-id ID ...]
```

- Needs: `e2e prepare_archive_imports`, `e2e sso_tls`.
- `--owned-id`: server IDs of the items the import created (read them from the
  diff of the before and after captures).
- Writes `archive-manager-CASE-after-*` and adds `attachmentProof` to
  `import-CASE-server-proof.json`.

### verify_backup_repository

Decrypts and validates a backup repository with independent tools: the age CLI
for age metadata and indexes (a wrong password must fail) and 7-Zip for AES-256
ZIP archives. For every indexed snapshot it checks manifest, item and
attachment counts and the attachment bytes against the recorded sizes.

```sh
integration/iosNativeE2eTest/e2e verify_backup_repository REPOSITORY \
  --password SYNTHETIC --output DIR
```

- Needs: the age CLI (on `PATH` or at `build/ios-native-e2e/tools/age`) and `7zz`.
- Reads only a dedicated repository: under a `Keyguard E2E` Files folder or
  the session folder.
- Writes `index.json`, `SNAPSHOT-vault.json`, `SNAPSHOT-manifest.json` and
  `proof.json` to `DIR`. Attachment size metadata that differs from the
  plaintext is listed in `proof.json` and exits nonzero.

### verify_backup_fixture

Compares a backup's first snapshot (a `verify_backup_repository` output folder)
with its sources. Every backed-up item must come from a `--server` capture or a
`--kdbx` entry with the same UUID.

```sh
integration/iosNativeE2eTest/e2e verify_backup_fixture DIR \
  --server <session>/main.json [--server ...] [--extended <session>/import-X-extended.json] \
  [--kdbx DATABASE PASSWORD [KEYFILE]] [--attachment-proof FILE ...]
```

- `--server`: decrypted item lists (`e2e vault --account KEY` output or import
  captures) taken while the backup was made. Common fields, credentials, URIs,
  card/identity/SSH and custom fields must match; native tags count as `Tag`
  fields.
- `--extended`: archive flags, URI match modes, passkeys and password history.
- `--kdbx`: local databases in the simulator's Files folder. Title, username,
  password and URL must match; a non-Login entry must be the synthetic test
  card (4111111111111111, 123). Their attachments are hashed from the database.
- `--attachment-proof` (repeatable, default
  `attachment-native-export-proof.json`): `{"file", "bytes", "sha256"}` per
  server attachment file name.
- Writes `DIR/fixture-values-proof.json`.

### verify_backup_restore

Compares the `backup` account, filled by a native restore, with a backup
snapshot: normalized values as a multiset, folders, archive and trash flags,
passkeys, password history and the decrypted bytes of every attachment
(downloaded through the `sso` server's TLS proxy).

```sh
integration/iosNativeE2eTest/e2e verify_backup_restore DIR
```

- Needs: `e2e sso_tls`; `DIR` from `verify_backup_repository`.
- Native tags become `Tag` fields; empty and null linked values, and absent and
  null optional values, compare equal; IDs and timestamps are recreated.
- Writes `backup-restore-value-differences.json` and
  `backup-restore-roundtrip-proof.json`. Exits nonzero when current values
  differ, or when password history was not fully restored (the partial proof is
  kept).

### verify_passkey_prf

Recomputes WebAuthn PRF outputs from the leased passkey's stored secret and
compares them with the responses `e2e passkey_prf_web` recorded:
HMAC-SHA256(secret, SHA-256("WebAuthn PRF" || 0x00 || input)), checked first
against the W3C WebAuthn Level 3 test vector.

```sh
integration/iosNativeE2eTest/e2e verify_passkey_prf --item-id ID --credential-id B64URL \
  [--responses FILE]
```

- Lease `passkey-prf-fixture-state.json`, written before native registration:

  ```json
  {"itemId": "<server ID>", "fixtureName": "iPhone E2E Passkey PRF",
   "passwordChecksMustStayOff": true, "itemCleanupPending": true}
  ```

- The Login must hold one passkey for RP `localhost`, user
  `ios-prf@keyguard.test`, and its hidden `keyguard.passkeys` field.
- `--responses` defaults to `passkey-prf-web/responses.jsonl`.
- A registration without PRF results is recorded as capability-only; an
  `invalid-map` request that still returns a signed credential is recorded as
  a client contract violation.
- Writes `passkey-prf-independent-oracle-proof.json`.

## Files and key fixtures

Scripts that copy into the simulator take `--device` (default `$E2E_DEVICE`; never `booted`). They write to On My iPhone > Keyguard E2E, found with `xcrun simctl get_app_container <device> com.apple.DocumentsApp groups`. They never touch the Keyguard app's containers. Generated inputs, manifests, leases and proofs go into the session folder with mode 600. Every fixture value is synthetic.

### prepare_fixtures

Creates the base documents in the session's `fixtures/` folder and copies them into Keyguard E2E, creating that folder: three repository KDBX files (password `1`), an invalid KDBX, an RSA key, an invalid key, text/tampered-text/JSON/PNG/ZIP documents, a wordlist, an HTML page, and valid and invalid `.env` files. Prints the SSH key fingerprint. Run it first: the other Files scripts need the folder.

```sh
integration/iosNativeE2eTest/e2e prepare_fixtures --device "$E2E_DEVICE"
```

Tools: `ssh-keygen`. Copies overwrite documents of the same name in the Files folder. ZIP timestamps change on every run, so don't rerun it mid-session just to add documents. To serve the wordlist and HTML page, use `python3 -m http.server 8234 --bind 127.0.0.1 --directory "$(readlink build/ios-native-e2e/current)/fixtures"`.

### prepare_keepass_variants

Creates a KDBX that needs a password and a keyfile. It has three folder levels, duplicate child names under different parents, a protected field, the three `prepare_fixtures` attachments, and the recycle bin turned on. Adds equivalent XML 1.00, XML 2.0 and hex keyfiles, plus an XML keyfile with a bad checksum. PyKeePass opens the database with each valid keyfile and rejects the bad one.

```sh
integration/iosNativeE2eTest/e2e prepare_keepass_variants --device "$E2E_DEVICE"
```

Tools: PyKeePass (harness venv). Refuses an existing session database or Files document.

### prepare_import_corpus

Copies the repository's pinned import corpus (`common/src/desktopTest/resources/import-corpus`) into Files as `e2e-corpus-<id>.*`. Checks each file against `checksums.sha256` before and after the copy. Writes `import-corpus-fixtures-proof.json`.

```sh
integration/iosNativeE2eTest/e2e prepare_import_corpus --device "$E2E_DEVICE"
```

Refuses to overwrite Files documents.

### prepare_browser_imports, prepare_manager_imports, prepare_archive_imports

Handwritten synthetic exports, not real exporter captures. Each file's expected items are written by hand in a manifest, independent of the app's parsers. `verify_import_corpus` and `verify_archive_imports` read these manifests.

- `prepare_browser_imports`: Chrome (UTF-8 BOM), Firefox and Safari CSV files, plus an invalid-header CSV. Writes `browser-import-fixtures.json`.
- `prepare_manager_imports`: 1Password, Dashlane, Keeper, LastPass, NordPass and Proton Pass CSV files, and Enpass JSON. Covers archive/trash flags and malformed-row skips. Writes `manager-import-fixtures.json`.
- `prepare_archive_imports`: 1PUX with an attachment, Dashlane ZIP and payment CSV, Proton Pass JSON and ZIP, and an unsupported Proton encrypted envelope. Writes `archive-manager-import-fixtures.json`. Inputs are kept in `archive-manager-fixtures/`. The lease `archive-manager-fixture-state.json` records each owned filename and hash before the copy. The script refuses to run while that lease is pending or prepared.

```sh
integration/iosNativeE2eTest/e2e prepare_browser_imports --device "$E2E_DEVICE"
integration/iosNativeE2eTest/e2e prepare_manager_imports --device "$E2E_DEVICE"
integration/iosNativeE2eTest/e2e prepare_archive_imports --device "$E2E_DEVICE"
```

All three refuse to overwrite Files documents.

### prepare_archive_previews

Creates five ZIP attachment-preview fixtures in `archive-preview-fixtures/`: mixed AES-256 and plain entries, ZipCrypto, a stored entry with a deliberate CRC error, an invalid central directory, and a valid LZMA entry. 7-Zip and Python's `zipfile` check encryption, extracted bytes and the CRC failure. Then the script copies the files into Files. The archive password is `Archive-E2e-2026!`, recorded in `fixture-manifest.json`.

```sh
integration/iosNativeE2eTest/e2e prepare_archive_previews --device "$E2E_DEVICE"
```

Tools: `7zz`. Refuses an existing lease `archive-preview-fixture-state.json`, an existing output folder, or an existing Files document. The lease records the hashes of all earlier Files documents. The script checks that they are unchanged afterwards.

### prepare_gpg_warnings

Creates a weak RSA1024 key, an expired RSA2048 key and a revoked RSA2048 key. Adds clearsigned messages, an encrypted message, and a signature made after the key expired, using a temporary renewal kept outside the vault. GnuPG verifies each result (`VALIDSIG` plus `EXPKEYSIG`/`REVKEYSIG`, exact decryption). The three private keys go into Files as `e2e-gpg-warning-<kind>.asc`. Other output goes to `gpg-warning-fixtures/`.

```sh
integration/iosNativeE2eTest/e2e prepare_gpg_warnings --device "$E2E_DEVICE"
```

Tools: `gpg`, `gpgconf`. Keyrings live in short temporary homes under `/private/tmp`, because of the agent socket path limit. They are removed with their agents. The script uses no keyserver and doesn't change the system clock. Refuses an existing lease `gpg-warning-fixture-state.json`, an existing output folder, or an existing Files document. Earlier Files documents are compared against the recorded baseline.

### prepare_client_certificate_variants

Signs four client certificates for the session's `tls/client.key` and packs each as PKCS#12 (password `P12-E2e-2026!`):

- expired, not yet valid (starts one hour after creation) and expiring soon, all signed by the session CA;
- one signed by a separate untrusted CA.

Also adds a `.pfx` with an empty password and a 4 MiB + 1 byte size-limit file. OpenSSL checks PKCS#12 parsing, chain and validity, and the hashes of the copied files. Inputs go to `client-certificates/variants/`. Writes `client-certificate-variants-proof.json` and `client-certificate-extra-fixtures-proof.json`.

```sh
integration/iosNativeE2eTest/e2e prepare_client_certificate_variants --device "$E2E_DEVICE"
```

Tools: `openssl`. Refuses an existing variants folder. Only reads the session's CA, server and client files. Run the not-yet-valid test before its `notBefore`.

### prepare_ssh_certificates

Reads one native SSH key item named `iPhone E2E SSH Advanced` from the server with the independent oracle. Checks its public/private pair and RSA-3072 size with `ssh-keygen`. Creates a local Ed25519 CA (`ssh-advanced/local-ca`) and signs five certificates: valid, expired, future, host, and one for a different key. Writes `ssh-advanced-certificate-oracle.json`. Checks that the server data didn't change while it ran.

```sh
integration/iosNativeE2eTest/e2e prepare_ssh_certificates --item-id <server item id> [--account main]
```

Tools: `ssh-keygen`. Create the item in the app first: RSA, 3072 bits. Then read its ID from the server. The lease `ssh-advanced-fixture-state.json` allows one attempt, and `ssh-advanced/` must not exist. The script makes no SSH connection and no server change.

### prepare_ssh_handoff

Signs an OpenSSH user certificate (principal `e2e`, valid for 2 hours) for an unencrypted RSA private key exported to Files. The CA is `ssh-advanced/local-ca` unless you pass `--ca`. The script parses the certificate and checks that it embeds the key's public key. OpenSSL checks the Ed25519 CA signature. Then the script writes the certificate to Files under the name you give.

```sh
integration/iosNativeE2eTest/e2e prepare_ssh_handoff --device "$E2E_DEVICE" \
  --private-key <file in Files> --certificate <new file name> [--ca <CA private key>]
```

Tools: `ssh-keygen`, `openssl`. Refuses an existing certificate file, an existing `ssh-file-handoff/` folder, and a pending or finished lease `ssh-file-handoff-fixture-state.json`. Checks that the private key and the CA are unchanged afterwards. Writes `ssh-file-handoff-certificate-proof.json`, and records the fingerprint in the lease.

### build_ssh_receiver

Builds `SSHReceiver/ReceiverApp.swift` into a simulator-only `ssh://` URL receiver in the session's `ssh-url-receiver/`, ad-hoc signs it and verifies the signature. Writes `build-proof.json`. Never installs the app. The receiver accepts only `ssh://e2e@127.0.0.1:2222`, writes a private receipt and makes no SSH connection.

```sh
integration/iosNativeE2eTest/e2e build_ssh_receiver
```

Tools: Xcode (`xcrun swiftc`, `codesign`). Uses the Xcode that `xcrun` selects; set `DEVELOPER_DIR` to choose another. Refuses a non-empty folder without its `owner.json`.

### prepare_autofill_domain

Checks that `keyguard-native-e2e.127.0.0.1.nip.io`, and each `--additional-host`, resolves only to 127.0.0.1. Then signs a two-day P-256 leaf for those names with the session CA, into `autofill-web/domain-tls/` by default; its proof is `autofill-web/domain-tls-proof.json`. The session server certificate already covers the `nip.io` name and its subdomains; use this for other host names and pass the leaf to a server with `--certificate` and `--private-key`. With `--directory`, the proof goes inside that folder.

```sh
integration/iosNativeE2eTest/e2e prepare_autofill_domain \
  [--directory <new folder>] [--additional-host sub.keyguard-native-e2e.127.0.0.1.nip.io]
```

Tools: `openssl`, DNS that resolves nip.io. Refuses an existing output folder. Checks that the session's CA and server files are unchanged.

### prepare_icon_network

Creates `e2e-icon-network.kdbx` (password `Icon-Network-E2E-2026!`) and `manifest.json` in a new folder. The database has one Login for `https://127.0.0.1:8253/login`, the `icon_network_web` origin. Each database gets a fresh root group UUID, because Keyguard rejects a second account with the same UUID.

```sh
integration/iosNativeE2eTest/e2e prepare_icon_network "$(readlink build/ios-native-e2e/current)/icon-network"
```

Tools: PyKeePass (harness venv). Refuses an existing folder.

### prepare_legacy_backup

Copies an existing native backup repository into a new folder in the legacy format: metadata, index, snapshots and blobs re-encrypted with 7-Zip ZIP AES-256. Snapshots and blobs get random keys. Metadata and index use `Backup-Legacy-E2e-2026!`. This is a constructed fixture, not a capture from an older app. Every archive is tested with the correct password and a wrong one. Writes `backup-legacy-fixture-proof.json`.

```sh
integration/iosNativeE2eTest/e2e prepare_legacy_backup <repository folder> <new folder>
```

Tools: `7zz`. Refuses an existing target. Checks that the source files are unchanged.
