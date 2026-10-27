---
title: One-time passwords
description: "Store authenticator keys in login items and use TOTP, HOTP, Steam and mOTP codes."
section: vault
type: feature
order: 40
platforms: [android, wear-os, linux, macos, windows]
related: [items, autofill, wear-os]
---

A login item can hold an authenticator key. Keyguard then shows the item's
current one-time password next to its username and password, and the key
syncs with the item to your other devices.

:::note
One vault unlock protects both the password and its second factor. For your
most critical accounts, keep the second factor in a separate app or on a
hardware key.
:::

## Add an authenticator key

1. Open the login item and select the edit (pencil) icon.
2. In **Authenticator key**, enter a full `otpauth://`, `steam://` or
   `motp://` URI, or only the secret key.
3. Select **Save**. The item shows the current code.

To read the key from a QR code instead, select the scan button in the
**Authenticator key** field:

| Option | Platforms | Reads the QR code from |
| --- | --- | --- |
| **Scan QR code** | Android | The camera |
| **Load from a file** | Android, Linux, macOS, Windows | An image file, such as a screenshot |

### From Google Authenticator

Keyguard reads the QR codes that Google Authenticator exports. It fills the
field with the first account in the code and skips the rest.

1. In Google Authenticator, start an export and select only the account to
   move.
2. In the login's **Authenticator key** field, scan the QR code that Google
   Authenticator shows.
3. Select **Save**.

Repeat for each account. A pasted `otpauth-migration://` link isn't
converted, so scan the code instead.

## Use a code

The item shows the current code, a countdown and the next code. The code's
menu has these actions:

- **Copy one-time password** copies the current code.
- **Copy secret key** copies the authenticator key.
- **Show as Barcode** shows the key as a QR code, so another authenticator
  app can scan it. See
  [QR codes and large type](/docs/items/#qr-codes-and-large-type).

When autofill fills a login, Keyguard copies its one-time password to the
clipboard. To stop this, turn off
**Auto-copy one-time passwords** in **Settings > Autofill**.

## Code types

| Type | Key format | Code |
| --- | --- | --- |
| TOTP | `otpauth://totp/…`, or a bare secret key | SHA-1 (default), SHA-256 or SHA-512; 1–9 digits (default 6); custom period (default 30 seconds) |
| HOTP | `otpauth://hotp/…` | Counter-based, with the same algorithms and digits as TOTP |
| Steam Guard | `steam://…` | 5 characters, every 30 seconds |
| mOTP | `motp://…` | 6 characters, every 10 seconds, with PIN support |
