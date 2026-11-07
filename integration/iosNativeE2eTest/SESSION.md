# Running a test session

How to run a native iOS test session with this harness, by hand or as an agent.
[README.md](README.md) documents each command.

## Rules

- Use a dedicated simulator. The E2E app has its own bundle ID and App Group,
  but the shared keychain access group follows the shipping entitlements.
- Use only the synthetic accounts in [`lib/config.py`](lib/config.py). Never
  sign in to a personal vault or a real service.
- Every server and fixture binds to `127.0.0.1`. Keep external password and
  breach checks off unless a journey needs them, and turn them off again after.
- Vaultwarden data lives in RAM. Stopping a server deletes its accounts, so
  leave servers running until the session ends.
- The session folder holds UI trees, screenshots, tokens and synthetic vault
  content. It is under the ignored `build/` folder; keep it local.

## Start

Run from the repository root. Requires Xcode, XcodeGen, Docker, Python 3 and a
JDK (for `server/bw/bw` account commands).

```sh
export E2E_DEVICE=<UDID>   # xcrun simctl list devices available
integration/iosNativeE2eTest/e2e smtp_sink &   # only for the mail server's accounts
integration/iosNativeE2eTest/e2e bootstrap
```

`bootstrap` creates `build/ios-native-e2e/<date>/` and points
`build/ios-native-e2e/current` at it. Every command writes there by default;
set `E2E_SESSION` to use another folder. Rerun `bootstrap` at any time: it
skips what exists.

Start fixture servers in their own terminals when a journey needs them. For
example, SSO sign-in needs `e2e oidc` and `e2e sso_tls`.

Build and install the app and runner as described in
[README.md › Build](README.md#build), then in separate terminals:

```sh
integration/iosNativeE2eTest/e2e driver serve
xcodebuild … test-without-building          # the runner, see README.md
```

Then prepare and check the baseline:

```sh
integration/iosNativeE2eTest/e2e baseline
integration/iosNativeE2eTest/e2e smoke
```

`baseline` creates the local vault, adds the main account, turns on the
Development premium switch and creates the baseline login. `smoke` checks it
natively and on the server, then locks, unlocks and relaunches the app.

## Driving the app

- Read a fresh `tree` before each tap and match labels from it. A tree taken
  while a sheet or menu animates can still describe the view underneath.
- A successful tap does not prove a change. Check the next tree, a screenshot
  or the server.
- Without `--index`, the driver prefers a hittable match. A failed or
  non-hittable target is an error, not a tap.
- An empty text field's value can equal its placeholder. Compare the separate
  placeholder and value attributes.
- Text commands fail when no field has focus. A system prompt can take focus;
  dismiss it and focus the field again.
- `tree` abbreviates long values; `value` reads the full value.
- Menus may need scrolling even when clipped entries appear in the tree.
  SwiftUI can expose a whole row while only its control accepts a tap: for a
  Menu in a Form row, tap the visible label's frame; for a switch, `tapPoint`
  near its trailing edge. Read the value afterwards.
- A file name editor can select its whole base name. Type the replacement
  directly and read the value before saving.
- System alerts belong to SpringBoard: use `systemTree`, `systemTap` and
  `systemType`. A coordinate tap lets XCTest's interruption handler choose.
- Some Files controls report not hittable. Use `tapPoint` on the control seen
  in a fresh screenshot, then check the result.
- `accessibilityAudit` returns issues in `issues`; success does not mean none
  were found. Triage each one against the tree and a screenshot.
- Restore orientation, settings and test switches after each journey.

## Leases

Fixture scripts that write to a server only touch items the app created during
the session. The script that needs one documents its lease file: create the
item in the app, write the lease JSON into the session folder, and run the
script. Scripts refuse to run without a lease, with an editor still open, or
when the server state differs from the lease. After the journey, delete only
the leased items and check that nothing else changed.

## Evidence and report

Keep the report in the session folder, for example `report.md`, next to the
evidence it cites. For each journey, record:

- an ID and the steps taken;
- the expected and observed result;
- the evidence files (trees, screenshots, oracle output);
- the verdict: passed, failed (with a defect entry) or blocked by the
  environment.

List defects and environment limits separately, each with a reproduction and
evidence. Driver commands are journaled to `actions.jsonl` without typed text
or screenshot bytes. An interactive driver session alone is not a passing
suite; check server persistence and sync with the oracles.

## End

Restore the app settings you changed, delete leased items and stop the
fixture processes. Stop the servers with `server/bw/bw stop --port <port>` only
when no journey needs their data.

## Extending the harness

- Put long-running fixtures in `servers/`, scripts that create or change test
  data in `fixtures/`, read-only checks in `oracles/` and session steps in
  `core/`. `e2e help` lists them.
- Use `lib/`: `config` for accounts and ports, `session` for paths and private
  files, `bitwarden` for server access, `journey` for UI scripts.
- Never hardcode server-generated IDs, dates or session paths. Look IDs up from
  the server or from a lease.
- Oracles stay independent of Keyguard: Python, OpenSSL and the pinned
  `requirements.txt` only.
- Add a short section to README.md for each new command.
