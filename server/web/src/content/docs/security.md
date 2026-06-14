---
title: Security & privacy
description: How Keyguard handles your data.
category: help
order: 1
---

Keyguard is built so that your data stays yours.

## Watchtower

Watchtower continuously analyzes your vault and surfaces issues so you can act
on them:

- Pwned (leaked) passwords
- Reused passwords
- Vulnerable accounts and weak passwords
- Inactive two-factor authentication
- Inactive passkeys
- Unsecured websites
- Duplicate items
- Incomplete and expiring items

The [Watchtower guide](/docs/watchtower/) covers every check in detail —
including how the pwned-password check uses k-anonymity so your passwords
never leave the device.

## Data safety

How the local copy on your device is protected, layer by layer:

- **App password never touches the disk or the network.** When you set
  up Keyguard you choose an [app password](/docs/lock-and-unlock/); a secret
  key is derived from it with **Argon2id** (64 MB of memory, 3 passes) —
  a memory-hard function built to make brute-forcing expensive.
- **Encrypted vault.** The derived key encrypts the app's local database. 
  Inside it: the local data, your account sign-in tokens, generator history 
  and wordlists, usage history, and Watchtower
  results. Without the key, the file on disk is opaque.
- **Biometric unlock goes through the hardware keystore.** On Android, the
  key material needed to unlock is encrypted by an AES key that lives in
  the **Android Keystore** and is released only after a successful
  **Class 3 ("strong") biometric** — Keyguard never sees your fingerprint
  or face, and the wrapped key is useless on another device.
- **The vault key stays in memory by default.** Unless you enable the
  [persist vault key](/docs/lock-and-unlock/#auto-lock) option, the key that
  opens the database exists only in RAM — unloading the app locks the
  vault. The option's trade-off is documented both here and in the app
  itself.
- **A small side database holds only non-secret data.** Next to the main
  database, Keyguard keeps a second store for the few things that should be
  readable without unlocking the vault: the *public* halves of your SSH
  keys — fingerprints and optional names included — so the
  [SSH agent](/docs/ssh-agent/) can answer "which keys do you have?" while
  locked (using a key still requires an unlocked vault and your approval),
  and the autofill **block-list**, so blocked sites stay blocked. Some of the UI state and
  preferences are also public.
- **Crash reports are opt-in**, and the app's source code is
  [open for inspection](https://github.com/AChep/keyguard-app).

## Reporting a vulnerability

Normally, issues can be filed directly in the public GitHub issue tracker, but if you believe there is a security impact, 
please contact me at keyguard@artemchep.com instead.

The email subject format should be: `[Security Vulnerability] <Title>`. 
Please provide detailed steps to reproduce the security vulnerability and its possible impact.
I will most likely respond within 48 hours and will make every effort to quickly resolve the issue.

If you would like to encrypt your report, please use the PGP key with long ID `0x18E5090AEF7FB228A18DBD2FFAC37D0CF674043E`, available in the public keyserver pool.
