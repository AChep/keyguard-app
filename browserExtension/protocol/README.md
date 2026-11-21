# Keyguard browser protocol, version 1

How the Keyguard browser extension talks to the Keyguard desktop app. This file is
the specification; the Rust, Kotlin and TypeScript implementations follow it, and
their tests read [`vectors/noise_xx.json`](vectors/noise_xx.json). The Kotlin and
TypeScript tests also read the messages of [`fixtures/`](fixtures/).

```
extension ⇄ native messaging ⇄ keyguard-browser-host ⇄ CLI agent socket ⇄ agent ⇄ app
    └──────────────── Noise_XX_25519_ChaChaPoly_SHA256, end to end ────────────────┘
```

The host and the agent relay Noise frames without reading them. Only the extension
and the app see plaintext.

Conventions:

- "Base64" is RFC 4648 section 4: standard alphabet, with `=` padding. WebAuthn
  JSON is the exception and keeps its base64url.
- Byte strings in this file are UTF-8. `||` is concatenation.
- Lengths are in bytes unless they say characters.

## 1. Native messaging

The browser frames each message as a 32-bit native-endian length, then UTF-8 JSON.
The host accepts messages of at most 128 KiB from the extension, and browsers
accept at most 1 MB from the host. Every message below fits: a 65,535-byte frame
is 87,380 base64 characters.

Both directions carry Noise frames:

```json
{"type": "noise", "data": "<base64>"}
```

`data` decodes to 1 to 65,535 bytes. The host relays it unchanged and in order.

The host also reports its connection to the app:

```json
{"type": "status", "status": "ready"}
```

To connect, the host opens the CLI agent's socket, found as the `keyguard` CLI
finds it (`KEYGUARD_CLI_SOCKET`, then the platform default). Frames on that socket
are a 32-bit big-endian length, then the bytes. The host sends one JSON frame:

```json
{"type": "browser", "origin": "<origin>", "hostRevision": 1}
```

`origin` is the extension origin of [section 2](#2-noise). The host then waits for
the agent's JSON answer and writes nothing until it arrives. After `{"ok": true}`,
each frame in either direction is one Noise frame of 1 to 65,535 bytes. The
agent's answer sets the status:

| `status` | Agent answer | Meaning |
|---|---|---|
| `ready` | `{"ok": true}` | Connected to the app. A new session starts. |
| `app_unavailable` | No socket, no answer within 30 seconds, `unavailable`, or any error not listed here. Also sent when an open connection closes. | The app isn't running, or it closed the connection. |
| `update_required` | `unsupported_command` (the app predates browser connections), or `update_required` (the app doesn't accept `hostRevision`) | The app and the host don't match. Update the app, or reinstall the connector from it. |
| `integration_disabled` | `integration_disabled` | The browser integration is off in the app. |
| `unverified_host` | `unverified_host` | The app refused the host: it isn't the binary bundled with the app. |
| `too_many_sessions` | `too_many_sessions` | The app already serves as many browser sessions as it allows. |

A refusal is `{"ok": false, "error": "<code>"}`.

Rules:

- A session lasts from `ready` until the next status message or until the port
  disconnects. Each `ready` needs a new handshake.
- While not connected, the host retries every 2 seconds, except after reporting
  `update_required` or `unverified_host`. It sends `ready` for every new
  connection, and any other status when it changes.
- The host drops `noise` messages that arrive while it isn't connected.
- The extension ends a session by disconnecting the port. The browser then stops
  the host, which closes the connection to the app.
- Each side ignores messages with an unknown `type`. The extension treats an
  unknown `status` as not connected.
- The host exits when the browser closes its input, after reporting
  `update_required` or `unverified_host` (so the extension's reconnect starts a
  fresh connector binary), or when it can't write to the app. It also exits on a
  message it can't read: one over 128 KiB, one that isn't a JSON object, or a
  `noise` message whose `data` isn't Base64 of 1 to 65,535 bytes.
- The host refuses to start without the arguments a browser passes: a Chromium
  origin, optionally followed by flags such as Windows' `--parent-window=<handle>`,
  or the absolute path of a Firefox manifest followed by the add-on ID.

## 2. Noise

The frames of a session, in order:

1. Extension → app: one byte, `0x03`, the suite ID.
2. The three handshake messages.
3. Noise transport messages, in both directions.

### Suite ID

The app accepts only `0x03`. On any other value it ends the session without a
reply. A future suite gets a new ID and a new prologue version.

### Parameters

| | |
|---|---|
| Protocol name | `Noise_XX_25519_ChaChaPoly_SHA256`, Noise revision 34 |
| Initiator | the extension |
| Responder | the app |
| Prologue | `"keyguard-browser/v1\n" \|\| origin` |
| Handshake payloads | empty |
| Largest Noise message | 65,535 bytes |

The prologue starts with 20 bytes, ending in `0x0a`, followed by the extension
origin, with no terminator:

- **Chromium browsers:** `chrome-extension://<id>/`, where `<id>` is 32 letters
  `a` to `p`. The browser passes it to the host as its first argument. The
  extension gets it from `chrome.runtime.getURL("")`.
- **Firefox:** the add-on ID, 1 to 255 printable ASCII characters without spaces
  or `/`. Firefox passes it to the host as its second argument. The extension
  gets it from `browser.runtime.id`.

The app takes the origin from the host, which takes it from the browser. If the
two sides disagree, the initiator fails to decrypt message 2.

### Keys

- The extension creates one X25519 static key pair on install. It keeps the key
  pair and the pinned app key in the IndexedDB of its extension origin. Content
  scripts run in the page's origin and can't open it; browsers expose
  `storage.local` to them.
- An extension keeps a stored record of a later format as it is: it neither
  uses nor replaces it, and starts no session.
- The app has one static key pair, shared by all extensions.

### Handshake

| Message | Tokens | Length |
|---|---|---|
| 1, extension → app | `e` | 32 |
| 2, app → extension | `e, ee, s, es` | 96 |
| 3, extension → app | `s, se` | 64 |

- A handshake message with a payload ends the session.
- **Pinned app key.** A paired extension compares the app's static key with the
  key it pinned. It does this after reading message 2 and before writing
  message 3. On a mismatch, it doesn't send message 3, ends the session and tells
  the user. It pairs again only when the user asks.
- An unpaired extension accepts the app's key. Pairing confirms it.
- If [`hello`](#hello) reports a paired session but the extension pinned no app
  key, as when it couldn't store the pin, the extension replaces its static key
  once and starts over unpaired.
- At `hello`, the app looks up the extension's authenticated static key in its pairings.
  A known key makes a paired session. An unknown key makes an unpaired session,
  which allows only `hello`, `pair_commit` and `pair_reveal`.
- While the vault is unlocked, a session is paired only if the vault confirms
  its pairing, see [Storage](#storage). Otherwise it is unpaired, and the
  extension pairs again.
- The app ends a session whose handshake doesn't finish within 20 seconds.
- The handshake hash `h` is 32 bytes. Pairing uses it.

### Transport

- Each frame after the handshake is one transport message of at most 65,535
  bytes. Its plaintext is at most 65,519 bytes.
- Each direction counts nonces from 0. There's no rekey. A new session starts a
  new handshake.
- A message that fails to decrypt ends the session.

## 3. Plaintext framing

The plaintext of each transport message is a flags byte followed by data.

| Flags bit | Meaning |
|---|---|
| 0 (`0x01`) | More fragments of this message follow. |
| 1 to 7 | Reserved, must be 0. |

- A message is the data of consecutive fragments, up to and including the first
  fragment with bit 0 clear. The fragments of one message are never interleaved
  with another message in the same direction.
- A fragment carries 1 to 65,518 bytes of data.
- A message is a UTF-8 JSON object of at most 1,048,576 bytes (1 MiB), in at most
  32 fragments. A sender may split it at any byte, even inside a UTF-8 sequence.
  Its arrays and objects nest at most 64 deep.
- The receiver ends the session on a reserved bit, an empty fragment, an
  oversized message, a 33rd fragment, or a message that isn't a UTF-8 JSON object.
  The app also ends it on a message that nests deeper than 64.

## 4. Messages

| Kind | Direction | Shape |
|---|---|---|
| Request | extension → app | `{"id": <id>, "type": "<request>", ...fields}` |
| Response | app → extension | `{"id": <id>, "ok": true, ...fields}` |
| Error response | app → extension | `{"id": <id>, "ok": false, "error": "<code>"}` |
| Event | app → extension | `{"event": "<event>", ...fields}` |

- `id` is an integer from 1 to 2^53 − 1, unique among the session's requests in
  flight. The extension counts up from 1. A request or response without a valid
  `id`, or a request with the `id` of one in flight, ends the session.
- Each request gets one response, unless the session ends first. Responses may
  come in any order, since a request can wait for a prompt.
- At most 8 requests per session are in flight. The app answers more with `busy`.
  A request stops counting before its response is sent. `hello` and `cancel`
  don't count: the app answers them before it reads the next message.
- The extension sends `hello` first and waits for its response.
- After the checks that end the session, `hello` and `cancel` are answered as
  their sections say. Any other request gets the first of these that applies:
  1. No successful `hello` yet: `invalid_request`.
  2. 8 requests in flight: `busy`.
  3. No `type`, or one that isn't a string: `invalid_request`.
  4. An unknown `type`: `not_supported`.
  5. A capability the session didn't agree on: `not_supported`.
  6. The [gate](#gates).
  7. The request's fields: `invalid_request`. Then the request's own checks.
- Both sides ignore unknown fields and unknown events.
- Optional fields (marked `?`) are omitted when absent.
- [`cancel`](#cancel) stops one request in flight. Ending the session stops all
  of them, without answers.

### Gates

| Gate | Requirement |
|---|---|
| none | Any session. |
| unpaired | An unpaired session; a paired one gets `invalid_request`. |
| paired | A paired session; an unpaired one gets `unpaired`. |
| unlocked | Paired, and the vault is unlocked; otherwise `vault_locked`. |
| approval | Unlocked, and the user approves the release. An approval window may cover later releases of the same item on the same site to the same pairing; locking the vault ends it. Items with master-password re-prompt always ask, and the user confirms their identity. A `fill` or `totp` of a handle that isn't matched, or in a frame of another site, always asks, see [`fill`, `totp`](#fill-totp). |

### Pages and sites

A request names a page by its `url`: an absolute `http` or `https` URL with a
host. Any other `url` gets `invalid_request`. The site of a page is its scheme
and registrable domain, such as `https://example.com` for
`https://login.example.com/`. An IP address, or a host without a registrable
domain, is a site of its own. The port doesn't count.

### Private windows

A request that names a page carries `private_window: true` when the page is in
a private window, such as an incognito one. The extension omits the field
otherwise. An app that doesn't know the field ignores it. The app stores no
browsing data of such a request:

- The app's request log keeps nothing of it.
- A release by `fill`, `totp` or `passkey_get` isn't a use, see
  [`suggest`](#suggest).

What the user saves is stored as usual: the login of `save`, the rule of
`never_save`, the passkey of `passkey_create`, and the URI that the prompt of
`fill` or `totp` adds.

Requests that carry it: `suggest`, `search`, `fill`, `totp`, `generate`,
`save`, `never_save`, `passkey_get` and `passkey_create`.

### Blocked URIs

Blocked URIs are the user's rules in the app that keep Keyguard away from
some pages. A rule blocks autofill and saving, or saving only. A page is
*blocked* when an enabled rule of autofill and saving matches it. *Saving is
blocked* on a page when any enabled rule matches it, so on every blocked page
too. A rule of saving only never applies to passkeys.

## 5. Requests

### `hello`

Gate: none.

| Request field | Type | |
|---|---|---|
| `protocol` | integer | Highest protocol version the extension speaks. This document is `1`. |
| `capabilities` | string[] | Optional features the extension uses. |

| Response field | Type | |
|---|---|---|
| `protocol` | integer | The version of this session. |
| `capabilities` | string[] | The requested capabilities that the app supports. |
| `paired` | boolean | Whether the session is paired, see [Handshake](#handshake). |
| `vault` | `"locked"` \| `"unlocked"` | |

`protocol` is the highest version a side speaks. The app answers the lower of the
two highest versions, or `not_supported` if it no longer speaks that version. The
extension asks the user to update when it doesn't speak the answered version.

The app answers a `hello` after a successful one with `invalid_request` and
changes nothing; a failed `hello` may be retried.

Capabilities in version 1:

| Capability | Requests |
|---|---|
| `passkeys` | `passkey_get`, `passkey_create` |
| `save` | `save`, `never_save` |

A request of a capability missing from the response gets `not_supported`.

If `paired` is `false` while the extension holds a pairing, the app removed it.
The extension forgets the pairing and offers to pair again.

If the app cannot read the format of its pairing records, it answers `hello`
with `update_required`. The extension keeps its key and its pinned app key and
asks the user to update the desktop app. The same error applies if a session
first encounters the format when the vault unlocks.

### `pair_commit`, `pair_reveal`

Gate: unpaired. The app always prompts, see [Pairing](#8-pairing).

| `pair_commit` field | Type | |
|---|---|---|
| `commit` | base64, 32 bytes | The commitment to the extension's nonce. |
| `name` | string, 1 to 64 characters | The name the extension suggests for itself. |

Response: `nonce`, base64, 32 bytes: the app's nonce.

| `pair_reveal` field | Type | |
|---|---|---|
| `nonce` | base64, 32 bytes | The extension's nonce. |

Response: `vault`, `"locked"` or `"unlocked"`. The session is paired from then on.

### `cancel`

Gate: none.

| Request field | Type | |
|---|---|---|
| `target` | integer | The `id` of the request to stop. |

Response: no fields. The app answers at once, whatever `target` names.

- If `target` is in flight, the app stops serving it: it takes its prompt or
  window off the screen and answers it with `cancelled`. A `passkey_create`
  stopped before the app starts to store the passkey stores nothing, and a
  stopped `save` closes its window before it shows.
- A `cancel` doesn't undo a pairing the user approved: once the app stores it,
  `pair_reveal` answers `{vault}`, not `cancelled`.
- If `target` names no request in flight, unknown or already answered, nothing
  happens.
- A `cancel` sent before `hello`, or without an integer `target`, gets
  `invalid_request`.

The extension sends `cancel` when it stops waiting for an answer: the page
aborts a passkey call, its frame navigates or closes, the inline menu or the
save bar that asked closes, or the tab of a fill without either closes or
leaves the page's origin. It fails the request at once and ignores the late
answer.

### `unlock`

Gate: paired. No fields.

Response: `vault`, `"locked"` or `"unlocked"`.

`unlock` shows the app's unlock prompt and answers once the vault is unlocked. It
answers at once if the vault is already unlocked. If the user closes the prompt,
it answers `user_denied`; if the prompt times out, `approval_timeout`. If the
user approves but the vault is still locked, it answers `{vault: "locked"}`. If
the unlocked vault doesn't confirm the session's pairing, `unpaired`. A second
`unlock` while the prompt is open gets `busy`. The session's passkey requests
that wait for the vault share this prompt.

### `suggest`

Gate: unlocked. No prompt.

| Request field | Type | |
|---|---|---|
| `url` | string | Absolute URL of the frame that holds the field. The background takes it from the sender, not from page content. |
| `kinds` | string[]? | `"login"`, `"passkey"`. Default: `["login"]`. Unknown kinds are ignored. |
| `rp_id` | string? | The relying party ID. Required with `"passkey"`. It must be valid for the origin of `url`, as for [passkeys](#passkey_get-passkey_create); otherwise `security`. |
| `private_window` | boolean? | `true` for a page of a [private window](#private-windows). |

| Response field | Type | |
|---|---|---|
| `blocked` | boolean | The page is [blocked](#blocked-uris); `items` is empty. |
| `save_blocked` | boolean | Saving is [blocked](#blocked-uris) on the page. `true` whenever `blocked` is. |
| `truncated` | boolean | More than 50 items matched. |
| `items` | item[] | At most 50, in the order below. |

The items are the logins that match the page, of the accounts the user didn't
hide, and not in the trash or the archive. With `"passkey"`, the logins with a
passkey for `rp_id` follow, whether they match the page or not. With only
`"passkey"`, the items are those logins alone. With empty `kinds`, `items` is
empty: the response tells only `blocked` and `save_blocked`.

Items used on this device come first, most recent use first. A use is a
release by `fill` or `totp`, another autofill by the app, or a passkey
sign-in. A request of a [private window](#private-windows) makes no use. The
other items follow: the best match first, then the logins of `"passkey"`. The
50 are taken after this order.

| Item field | Type | |
|---|---|---|
| `handle` | string | See [Handles](#7-handles). |
| `name` | string | |
| `username` | string? | Absent when empty. |
| `account` | string | The account the item belongs to, for display. |
| `has_password` | boolean | |
| `has_totp` | boolean | The item has a time-based one-time password. |
| `reprompt` | boolean | Releasing it always prompts. |
| `passkeys` | passkey[]? | With `"passkey"`: the item's discoverable passkeys for `rp_id`, which answer a request without `allowCredentials`. May be empty. |
| `last_used` | boolean? | `true` on the first item when it has a use on this device. Absent otherwise. |
| `password_hidden` | boolean? | `true` when the item's collections hide its password. Absent otherwise. The extension doesn't copy the password; `fill` still releases it. |

| Passkey field | Type | |
|---|---|---|
| `handle` | string | A passkey handle, for `passkey_get`. |
| `user_name` | string | |
| `user_display_name` | string? | |

### `search`

Gate: unlocked. No prompt.

| Request field | Type | |
|---|---|---|
| `query` | string | 2 to 100 code points once trimmed; otherwise `invalid_request`. |
| `url` | string | As for `suggest`. |
| `private_window` | boolean? | `true` for a page of a [private window](#private-windows). |

| Response field | Type | |
|---|---|---|
| `blocked` | boolean | The page is [blocked](#blocked-uris); `items` is empty. |
| `truncated` | boolean | More than 20 items matched. |
| `items` | item[] | At most 20. |

The items are the logins that `suggest` could list, on any site, whose name,
username or URI host holds `query`, ignoring case. Those that match the page
come first, then those whose name starts with `query`, then the rest. Each
group is sorted by name.

An item is a `suggest` item without `passkeys` or `last_used`, plus:

| Item field | Type | |
|---|---|---|
| `matched` | boolean | A URI of the item matches the page by the app's URI match rules. A name alike the page doesn't count. On an `http` page, see [Handles](#7-handles). |

### `fill`, `totp`

Gate: approval.

| Request field | Type | |
|---|---|---|
| `handle` | string | An item handle from `suggest` or `search`. |
| `url` | string | The URL to fill, from the sender. It must be on the same [site](#pages-and-sites) as the URL of the request that issued the handle, and not be [blocked](#blocked-uris); otherwise `not_allowed`. |
| `top_origin` | string? | The origin of the tab's top frame, such as `https://example.com`, when the frame of `url` has another origin. The background takes it from the browser, never from page content: from the tab's URL or, when that isn't `http` or `https`, from the sender of a message of the top frame's content script, as for an `about:blank` popup. It sends no `fill` or `totp` when neither tells. Anything but an `http` or `https` origin gets `invalid_request`. |
| `private_window` | boolean? | `true` for a page of a [private window](#private-windows). |

A frame of another site, whose `top_origin` isn't on the site of `url`:

- Always prompts, even when an approval window covers the item on the site.
  The approval is never remembered.
- The prompt warns and names both sites.

An approval window covers a frame of the same site, such as
`https://login.example.com` in `https://example.com`, as it covers a top frame.

A handle that isn't matched, see [Handles](#7-handles):

- Always prompts, even when an approval window covers the item on the site.
  The approval is never remembered. Items with master-password re-prompt still
  need the user's identity.
- The prompt warns that the item isn't saved for the site. It offers to add the
  scheme and host of `url` to the item, unchecked, unless the user can't edit
  the item.
- If the user checks it and approves, the app adds the URI once the release
  succeeds. A failure to add it doesn't fail the release. The extension never
  writes to the vault.

`fill` response, with each field absent when empty:

| Field | Type |
|---|---|
| `username` | string? |
| `password` | string? |

`totp` response: `code`, string.

The item must have a time-based one-time password; otherwise `invalid_request`.
A counter-based one (HOTP) isn't a TOTP.

An item that is gone, or that moved to the trash, the archive or a hidden
account, gets `invalid_request`. The app answers with the item as it is once the
user approves.

### `generate`

Gate: paired.

| Request field | Type | |
|---|---|---|
| `url` | string? | The page the password is for. |
| `private_window` | boolean? | `true` for a page of a [private window](#private-windows). |

Response: `password`, string, made with the settings of the app's password
generator. The vault may be locked.

### `save`

Gate: unlocked.

| Request field | Type | |
|---|---|---|
| `url` | string | |
| `username` | string? | |
| `password` | string? | |
| `previous_password` | string? | |
| `private_window` | boolean? | `true` for a page of a [private window](#private-windows). |

Response: no fields, once the window is on screen. The app opens a window with a
new item, or with the matching item and the new password. The user saves in the
app; the extension never writes to the vault.

- The matching item is a login with a URI that matches the page and the same
  `username`. An absent or empty `username` matches nothing, so the window opens
  a new item.
- Of several, the one whose password is `previous_password` wins, or else the
  page's best match. An absent or empty `previous_password` doesn't pick between
  them.
- An item with master-password re-prompt, or one the user can't edit, never
  matches.
- A session has one save window at a time. Another `save` while it is open gets
  `busy`.
- A page where saving is [blocked](#blocked-uris) gets `not_allowed`.
- If the window closes before it shows, the app answers `user_denied`; if it
  doesn't show within the prompt timeout, `approval_timeout`.

### `never_save`

Gate: unlocked. Capability: `save`.

| Request field | Type | |
|---|---|---|
| `url` | string | The page, as for `save`. |
| `private_window` | boolean? | `true` for a page of a [private window](#private-windows). |

Response: no fields. The app doesn't prompt. It adds a rule that blocks saving
only, for the registrable domain of `url`:

| Rule field | Value |
|---|---|
| Name | The host of `url`. |
| Description | Empty. |
| URI | The scheme and host of `url`, such as `https://login.example.com`. |
| Match | Base domain. |
| Enabled | Yes. |
| Exposed | Yes: the rule applies while the vault is locked. |

- If saving is already [blocked](#blocked-uris) on the page, the app adds
  nothing and answers the same.
- If the app can't store the rule, it answers `not_allowed`.

### `passkey_get`, `passkey_create`

Gate: paired, then as for approval. Capability: `passkeys`.

While the vault is locked, the app first shows its unlock prompt, as `unlock`
does: closing it answers `user_denied`, and a timeout `approval_timeout`. Once
the vault is unlocked, a session whose pairing it doesn't confirm gets
`unpaired`. If the vault locks before the app answers, `vault_locked`.

| `passkey_get` field | Type | |
|---|---|---|
| `origin` | string | The page's origin, such as `https://example.com`. The background takes it from the sender, never from page content. |
| `options` | object | `PublicKeyCredentialRequestOptionsJSON` (WebAuthn Level 3). |
| `mediation` | string | `"silent"`, `"optional"`, `"conditional"` or `"required"`. |
| `same_origin_with_ancestors` | boolean | |
| `handle` | string? | A passkey handle from `suggest`, the user's pick in the inline menu. |
| `private_window` | boolean? | `true` for a page of a [private window](#private-windows). |

Response: `credential`, an `AuthenticationResponseJSON`.

| `passkey_create` field | Type | |
|---|---|---|
| `origin` | string | As for `passkey_get`. |
| `options` | object | `PublicKeyCredentialCreationOptionsJSON`. |
| `same_origin_with_ancestors` | boolean | |
| `private_window` | boolean? | `true` for a page of a [private window](#private-windows). |

Response: `credential`, a `RegistrationResponseJSON`, with `"none"` attestation.

The app builds `clientDataJSON` itself, with `origin`. Before it prompts, it
checks:

1. `same_origin_with_ancestors: false` gets `not_supported`: version 1 doesn't
   serve cross-origin calls.
2. `options` must parse as WebAuthn JSON; otherwise `invalid_request`.
3. `origin` must be `https`, or `http://localhost` with an optional port. It
   must be written as a browser serializes an origin: a lowercase scheme, a
   lowercase ASCII host (Punycode), no default port, no path, not even a
   trailing `/`.
4. The RP ID is `rpId` of `passkey_get`, or `rp.id` of `passkey_create`, or
   else the host of `origin`. It must be that host or a registrable domain
   suffix of it. Otherwise `https://<RP ID>/.well-known/webauthn` must list
   `origin` among its related origins (WebAuthn Level 3, section 5.11).
5. If 3 or 4 fails, `security`.
6. Once the vault is unlocked, a [blocked](#blocked-uris) page gets
   `not_supported`. A rule that blocks saving only doesn't apply. The app
   checks again after the user picks or approves, before it signs or stores.

`passkey_get`:

- `mediation: "silent"`, or an unknown value, gets `not_supported`.
  `"conditional"` needs a `handle`; without one, `invalid_request`.
- With a `handle`, its passkey must still be in its item; otherwise
  `invalid_request`, as for `fill`. It must be for the RP ID of the request and
  allowed by `allowCredentials`; otherwise `not_allowed`. The approval gate
  applies to the item on the [site](#pages-and-sites) of `origin`, as for `fill`.
- Without a `handle`, the candidates are the passkeys for the RP ID that
  `allowCredentials` names, or without it, the discoverable ones. With none, the
  app answers `not_supported`. Otherwise it opens a window where the user picks
  one. The pick approves the release, as the prompt of `fill` does.

`passkey_create`:

- An empty `pubKeyCredParams` means ES256 (`-7`) and RS256 (`-257`), as
  WebAuthn says. If the app supports none of the resulting list, it answers
  `not_supported`. Version 1 supports ES256.
- The app opens a window where the user picks the login that stores the
  passkey. The user can add a login there first.
- An `attestation` that WebAuthn doesn't know counts as `"none"`, the default.
- After the pick, a passkey of `excludeCredentials` in the vault gets
  `invalid_state`. As WebAuthn requires, the app reveals it only after the user
  consents.
- A login the user can't edit gets `not_allowed`, before the app asks for the
  user's identity.

The windows of both requests:

- Closing a window answers `user_denied`; if the user doesn't pick within the
  prompt timeout, `approval_timeout`.
- "Use another device" answers `not_supported`.
- A session has one passkey window at a time. Another request that needs one
  gets `busy`.
- Ending the session closes the window.

User verification:

- Unless `userVerification` (of `options`, or of `authenticatorSelection` for
  `passkey_create`) is `discouraged`, the user confirms their identity in a
  prompt, even after a pick in the window or when an approval window covers the
  item. Without `userVerification`, it is `preferred`, as in WebAuthn. An
  unlock during the request counts, and then the app doesn't ask again.
- The authenticator data reports a verified user when the user unlocked the
  vault during the request or confirmed their identity, unless
  `userVerification` is `discouraged`.
- An item with master-password re-prompt always prompts for `passkey_get`, and
  the user confirms their identity. When the user picks it in the window, the
  window asks for the master password, and the app doesn't ask again.

The extension sends the request while the vault is locked too: the app's unlock
prompt comes first.

The extension answers the page:

| Code | The extension |
|---|---|
| `not_supported` | Lets the browser handle the call. |
| `invalid_state` | Rejects with `InvalidStateError`. |
| `security` | Rejects with `SecurityError`. |
| Any other | Rejects with `NotAllowedError`, also when the user closes the unlock prompt. |

A conditional `passkey_get` is the exception: after a failed pick, the page's call
stays pending and the inline menu shows the error. `not_supported` hands the call
to the browser, and so does the session ending or losing `passkeys` while the call
waits.

The extension also lets the browser handle the call when:

- the user turned passkeys off in the extension, or the session lacks `passkeys`;
- the extension isn't paired, or the app isn't running;
- the frame has an ancestor of another origin. The extension doesn't send
  `same_origin_with_ancestors: false`;
- `mediation` is `"silent"`, which allows no prompt. The extension doesn't send
  it;
- the session ends before the app answers.

A page that aborts the call, or a frame that navigates or closes, makes the
extension send `cancel` for the request.

## 6. Events

The app sends events to paired sessions only.

| Event | Fields | |
|---|---|---|
| `vault` | `state`: `"locked"` \| `"unlocked"` | The vault's state changed. |
| `revoked` | none | The user removed this pairing. The app ends the session next; the extension forgets the pairing. |
| `disabled` | none | The user turned the integration off. The app ends the session next. |

## 7. Handles

- A handle is an opaque string of at most 64 characters.
- The app creates handles at random for each session. They die with the session.
- An item handle is bound to its account, item and [site](#pages-and-sites),
  and to whether the item matched the page. A handle from `suggest` is
  matched, unless `suggest` lists the item only for a passkey of `rp_id`; a
  handle from `search` is matched when its item's `matched` is `true`. A
  `fill` or `totp` treats a matched handle as not matched when its item no
  longer matches `url`, such as after an edit of its URIs. A passkey handle is
  bound to its account, item, credential and RP ID.
- On an `http` page, an item is matched only by a URI that matches the page
  and starts with `http://`. A listing by `suggest` doesn't count. So an
  `https` login isn't matched on the `http` page of its host: its release
  always prompts and warns. Adding the page adds `http://` and the host, and
  the item is matched there from then on.
- An item keeps its handles on a site for the whole session: one matched, one
  not. The app may forget the oldest handles of a long session.
- An unknown or expired handle gets `invalid_request`.

## 8. Pairing

Pairing runs in an unpaired session. The extension commits to its nonce before it
learns the app's, and the app sends its nonce before it learns the extension's.
A party in the middle runs two handshakes with different hashes. It can't choose
nonces that make both codes match, so it succeeds with a chance of 1 in 10^6.

1. The extension picks a random 32-byte `extension_nonce` and sends
   `pair_commit {commit, name}`, where:

   ```
   commit = SHA-256("keyguard-browser-commit/v1\n" || extension_nonce)
   ```

2. The app picks a random 32-byte `app_nonce` and answers `{nonce: app_nonce}`.
3. The extension sends `pair_reveal {nonce: extension_nonce}` and shows the code.
4. The app checks `commit` in constant time. On a mismatch it answers `security`
   and drops the attempt. Otherwise it shows the code and asks the user to
   compare it with the browser and to name the browser. The name is prefilled
   from the verified browser process, or else from `name`.
5. The app answers:
   - `{vault}` once the user confirms. It stores the extension's static key and
     the name under an ID of its own, see [Storage](#storage). The extension
     pins the app's static key.
   - `not_allowed` if it can't store the pairing.
   - `user_denied` if the user declines.
   - `approval_timeout` if the prompt times out.

The code:

```
first, second = the two static public keys, sorted as unsigned bytes
info = "keyguard-browser-sas/v1" || first || second || app_nonce || extension_nonce
okm  = HKDF-SHA256(salt = h, ikm = "" (0 bytes), info = info, length = 4)
code = big-endian uint32(okm) mod 1,000,000, as 6 decimal digits with leading zeros
```

HKDF is RFC 5869. This is the derivation of
`feature/p2p-sync/.../enroll/SasDerivation.kt` with an empty PSK and the browser's
own domain strings; as there, the committing side's nonce comes last. The
commitment domain ends in `\n`; the info prefix doesn't.

Rules:

- Each `pair_commit` takes fresh nonces on both sides. A failed attempt can be
  retried in the same session with a new `pair_commit`.
- Pairing needs an unlocked vault. While the vault is locked, `pair_commit` and
  `pair_reveal` get `vault_locked`, and a lock during the prompt answers
  `user_denied`. The extension asks the user to unlock the app and retries.
- `pair_reveal` without a pending `pair_commit` gets `invalid_request`.
- The app shows one pairing prompt per session at a time. A `pair_commit` while
  a `pair_reveal` is unanswered gets `busy`.
- The prompt is never remembered.
- A session whose pairing the vault doesn't confirm pairs again the same way. The
  new pairing replaces the old one.

A party in the middle could retry `pair_commit` until the app's code matches,
and drop each miss before `pair_reveal`. So across all sessions, at most 3
`pair_commit`s whose prompt the user hasn't seen count at a time; the app
answers more with `busy`. A `pair_commit` counts for an hour, or until its
`pair_reveal` shows a prompt that the user answers or that times out. An attempt
that is replaced, abandoned, revealed with a wrong nonce or while locked, or
whose session ends, counts for the full hour.

### Storage

The app stores each pairing twice:

- Outside of the vault: the ID, name, static key and origin. Sessions use it
  while the vault is locked.
- In the vault: the ID, static key and origin. The vault keeps it on this
  device only: it isn't synced or exported.

While the vault is unlocked, a pairing counts only if both records match on ID,
static key and origin. A process that can write the user's files can add an
outside record, but while the vault is unlocked, that record pairs nothing. A
new or reset vault confirms no pairing, so each extension pairs again.

- Pairing writes the vault's record first. If either write fails, pairing fails.
- Removing a pairing deletes both records. While the vault is locked, it deletes
  the outside one; the next write of the vault's records drops the other.
- A record of a newer schema is read-only: the app reads no pairings from it and
  never overwrites it. It reports `update_required` when checking a pairing
  against that store. Only a confirmed removal sends `revoked`.

## 9. Errors

| Code | Meaning |
|---|---|
| `vault_locked` | The request needs an unlocked vault. |
| `update_required` | The desktop app cannot read its browser pairing format. Keep the pairing and retry after updating the app. |
| `unpaired` | The session isn't paired. |
| `user_denied` | The user declined. |
| `approval_timeout` | The prompt timed out. |
| `not_allowed` | A valid request that the app refuses, such as a handle used on another site, a [blocked](#blocked-uris) page, a `save` where saving is blocked, or a pairing or rule it can't store. Also an unexpected failure in the app. |
| `security` | A security check failed: a commitment mismatch, or an origin or RP ID that fails validation. |
| `not_supported` | An unknown request, a capability or protocol version the app lacks, or an unsupported case such as a cross-origin passkey call. For a passkey request, the app hands the call back to the browser: no passkey matches, the user chose another device, or the page is [blocked](#blocked-uris). |
| `invalid_state` | A passkey of `excludeCredentials` is already in the vault. |
| `invalid_request` | A missing or mistyped field, an unknown handle, a request out of order. |
| `too_large` | The response would exceed 1 MiB. |
| `busy` | 8 requests are in flight; the session's `unlock` prompt is open, its `pair_reveal` is unanswered, or its save window or passkey window is open; or 3 pairing attempts are unseen, see [Pairing](#8-pairing). |
| `cancelled` | The extension cancelled the request. |

The extension treats an unknown code as a failure without retrying.

## 10. Vectors and fixtures

[`fixtures/`](fixtures/) holds complete messages of [section 4](#4-messages), with
realistic values:

- `<type>.json`, one per request type: `request` and its `response`.
- `events.json`: each event.
- `errors.json`: an error response of each code.

The app's tests read each request as the app does, write each response, event
and error, and compare them with the files as JSON values. The extension's tests
type-check each file against its TypeScript types, excess properties included,
and run it through the extension's checks. They catch a missing or unread file,
and a disagreement on the shapes the files hold. An optional field is covered
only when one file holds it and another omits it.

[`compatibility/v1.json`](compatibility/v1.json) freezes the v1 requests,
responses, events and errors. Keep this snapshot unchanged when updating the
current fixtures. The app tests decode its requests and check that serialized
replies retain the v1 fields. The extension tests replay its responses, events
and errors through a protocol session. Add a separate snapshot for each later
protocol version.

[`vectors/noise_xx.json`](vectors/noise_xx.json) holds:

- `cacophony`: the Cacophony vector of `Noise_XX_25519_ChaChaPoly_SHA256`, as
  shipped with snow 0.10.0. Senders alternate, starting with the initiator.
- `keyguard`: a session of this profile with fixed keys and ephemerals: the
  prologue of `chrome-extension://abcdefghijklmnopabcdefghijklmnop/`, the three
  handshake messages, then `hello` and `pair_commit` with their responses. Each
  message names its `sender`, since the extension sends twice in a row after the
  handshake. Framed messages also carry their JSON as `text`. `pairing` holds the
  nonces, the commitment, the code's `info` and HKDF output, and the code.

The suite ID frame isn't part of the vectors. Bytes are lowercase hex. Nothing in
the file is a real key.

`util/crypto` generates the file through the app's Noise code, and its tests fail
if the file is stale. To rewrite it after a deliberate change, run from
`util/crypto/rust`:

```sh
cargo test -p keyguard-crypto-core --lib -- --ignored write_browser_vectors
```
