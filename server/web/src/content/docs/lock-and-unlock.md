---
title: Locking & unlocking
description: The app password vs. your account password, biometric and YubiKey unlock, auto-lock settings, and what to do if you forget a password.
category: get-started
order: 2
---

Keyguard involves **two different passwords**, and knowing which is which
saves a lot of confusion:

- your **account credentials** — the Bitwarden master password (or the KDBX
  database password) that protects the vault itself. That vault lives
  **outside Keyguard** — on the Bitwarden server, or in the `.kdbx` file —
  and the same credential works in the web vault and any other client;
- the **app password** — created when you first set up Keyguard, it locks
  everything Keyguard keeps on this device: the [local copy of your vault](docs/security/).

The app password never gets stored on the device nor sent over the network —
it is used to generate a secret key that encrypts the local data. Because it
is local, each of your devices can have a different one, and you can change
it any time via **Change app password** without touching your accounts.

## Unlock options

Besides typing the app password, you can unlock with:

- **Biometrics** — fingerprint or face unlock on Android, Touch ID on macOS;
  enable it during setup or later in the security settings;
- **YubiKey** (Android) — unlock with a YubiKey over **USB** or **NFC**,
  using HMAC-SHA1 challenge-response. Keyguard provisions a key slot when
  you set it up.

Individual items can additionally require re-authentication before they are
viewed or autofilled — see the
[authentication re-prompt](/docs/items/#per-item-protection). When such an
item is opened, Keyguard shows a **Confirm access** prompt that accepts your
app password or biometrics.

## Auto-lock

The security settings control when the vault locks itself:

- **Lock after a delay** — from *immediately* to *never*, after inactivity;
- **Lock when screen turns off** — locks as soon as the screen goes dark;
- **Persist vault key on a disk** — with this off, the vault also locks
  whenever the app is unloaded from memory.

> **Security note on persisting the vault key.** With the option on, the
> key that unlocks your local data is written to the device's internal
> storage (Android) or disk (Desktop), so the vault stays unlocked even after the app is unloaded from
> memory. That is a deliberate trade-off — as the app itself warns, if the
> device's storage is compromised, the attacker gains access to
> the local vault data. Leave it off when that risk matters more to you
> than the convenience of fewer unlocks.

## Lock vs. sign out

**Locking** keeps everything on the device, encrypted — unlocking brings you
right back. **Signing out** of an account removes its data from Keyguard. 

## If you forget a password

- **The app password** — there is no recovery; choose **Erase app data** on
  the unlock screen and set Keyguard up again. Nothing on the server/file is
  affected: add the account and your vault re-syncs. The only thing truly lost
  is local changes that were never synced.
- **Bitwarden master password** — recovery options (hint, emergency access, organization recovery) 
  live with your server — check the web vault.
- **KDBX password** — there is no recovery by design.

Keep the password safe, and [configure backups](/docs/backups/) just in case.
