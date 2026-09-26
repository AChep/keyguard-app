---
title: One-time passwords (TOTP)
description: Store authenticator keys in your login items — QR scanning, supported code types, and auto-copy.
category: guides
order: 3
---

A login item can carry an **authenticator key**, turning Keyguard into your
two-factor code generator: the current code sits right next to the username
and password it belongs to, with a countdown and a copy button, and it syncs
to every device — including your [watch](/docs/wear-os/).

## Adding a key to an item

While editing a login item, set the **Authenticator key** in one of three
ways:

- **Scan the QR code** the website shows you, using the camera;
- **Load from a file** — pick a screenshot or photo of the QR code;
- **Paste it** — either a full `otpauth://` URL or just the raw secret key.

Since adding a key edits the item, it follows the same
[premium rule](/docs/premium/) as other vault edits — generating and copying
codes is free.

## Supported code types

| Type            | Notes                                                       |
| :-------------- | :----------------------------------------------------------- |
| **TOTP**        | SHA-1, SHA-256, or SHA-512; 1–9 digits (default 6); custom period (default 30 s) |
| **HOTP**        | Counter-based variant, same algorithms                       |
| **Steam Guard** | `steam://` secrets; the 5-character Steam code format        |
| **mOTP**        | `motp://` secrets, with PIN support                          |

## Using the codes

The item view shows the current code with a **countdown timer**, a preview
of the upcoming code, and one-tap copy. There's also an option to 
**Auto-copy one-time passwords** — when autofill fills a login, the 
matching code is copied to the clipboard automatically, ready to paste 
into the verification field.

> Storing the second factor next to the password means one vault unlock
> protects both. That's a deliberate trade-off — convenience against
> separation of factors. For your most critical accounts, consider keeping 
> the second factor in a separate app or on a hardware key.
