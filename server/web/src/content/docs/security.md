---
title: Security model
description: "How Keyguard protects the local copy of your vault, what it can't protect against, and its known limitations."
section: security
type: concept
order: 30
platforms: all
related: [lock-and-unlock, security-settings, watchtower, report-vulnerability, architecture]
---

Keyguard keeps an encrypted copy of your vault on each device. The security
model is the set of layers that protect that copy, and the limits of that
protection.

**Settings > Other > Data safety** sums up these layers in the app.

## Threat model

| Situation | Your vault |
| --- | --- |
| Someone copies Keyguard's files, or takes the device while the vault is locked | Protected. They need the app password or another unlock method you set up. |
| Your Bitwarden server or your KeePass storage is breached | Protected by your account's own encryption. See [Account encryption](#account-encryption). |
| Someone uses the device while the vault is unlocked | Not protected. They can see what you can. |
| Malware runs as your user while the vault is unlocked | Not protected. It can read Keyguard's memory, the screen and the clipboard. |
| An attacker controls the operating system, or the device is rooted | Not protected. They can capture the app password as you enter it. |
| Someone knows your app password | Not protected. Treat it like the master password of an account. |

## App password

The app password never touches the disk or the network. Keyguard derives a
secret key from it and a random 512-bit salt with Argon2id: 64 MB of memory,
3 passes and 4 lanes. Argon2id is memory-hard, which makes brute-force
guessing expensive.

App passwords set in older versions of Keyguard use PBKDF2 with 10,000
iterations, which is far cheaper to guess. Changing the app password moves it
to Argon2id. See
[Change the app password](/docs/lock-and-unlock/#change-the-app-password).

## Encrypted database

The derived key encrypts Keyguard's local database in the SQLCipher format.
The database holds, among other data:

- vault items
- account data, including keys and storage credentials
- generator history, wordlists and email forwarder settings
- usage history

### Data readable while locked

Keyguard keeps a few things outside the encrypted database, so it can use them
while the vault is locked. None of them are secret:

- The public halves of your SSH and GPG keys, with fingerprints and optional
  names. The [SSH agent](/docs/ssh-agent/) and [GPG agent](/docs/gpg-agent/)
  read them to find a key while the vault is locked. Using a key still needs an
  unlocked vault and your approval.
- Your accounts' names, so Android can list your accounts for
  [Credential Exchange](/docs/credential-exchange/).
- The [Blocked URIs](/docs/blocked-uris/) set to work while the vault is locked.
- Settings and some UI state.

## Account encryption

Your Bitwarden server keeps your items encrypted with your account's keys.
Keyguard decrypts them on the device, as the official apps do. A KeePass
database stays encrypted with its own password and key file wherever you store
it.

Keyguard has no server of its own that stores your vault. The license server
never receives vault data. See [License keys](/docs/license-keys/).

## Vault key in memory

By default, the key that opens the database exists only in memory. Unloading
the app locks the vault. The **Persist vault key on a disk** option writes the
key to disk instead, which trades security for fewer unlocks. See
[Auto-lock](/docs/lock-and-unlock/#auto-lock).

Biometric unlock uses keys protected by the operating system. YubiKey and
security key unlock encrypt the vault key with a secret that only your key can
compute.

## Known limitations

- **Memory.** While the vault is unlocked, decrypted items stay in memory.
- **Clipboard.** A copied value stays until
  [Automatically clear clipboard](/docs/security-settings/#automatically-clear-clipboard)
  removes it. Keyguard marks copied passwords as sensitive, but some clipboard
  managers record them anyway.
- **Auth re-prompt.** It's a check in the app, not extra encryption. See
  [Per-item protection](/docs/items/#per-item-protection).
- **Audits.** Keyguard hasn't had an independent security audit or penetration
  test. Those are very expensive and the maintainer can't currently sponsor it.

## Vault content checks

[Watchtower](/docs/watchtower/) checks the contents of your vault, such as
leaked and reused passwords. Most checks run on the device. See
[How the checks respect your privacy](/docs/watchtower/#how-the-checks-respect-your-privacy).

## Android permissions

Keyguard asks for an Android permission when a feature first needs it.
**Settings > Other > Permissions** lists each one, and turns it on or off.

| Permission | Used to |
| --- | --- |
| **Camera** | Scan QR codes |
| **Post notifications** | Show download progress. Android 13 and later. |
| **Local network** | Reach self-hosted Bitwarden, WebDAV and S3 servers on your local network |
| **Write external storage** | Save exports to the Downloads folder. Android 10 and earlier. |

The screen also lists the other permissions the app holds.

## Crash reports and source code

On Android, Keyguard sends crash reports through Firebase Crashlytics, only
after you turn on **Send crash reports**. The desktop apps don't send crash
reports. The source code is open on
[GitHub](https://github.com/AChep/keyguard-app), and you can
[report a vulnerability](/docs/report-vulnerability/) privately.
