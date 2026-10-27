---
title: Wear OS
description: "Use your vault on a Wear OS watch: setup, what works on the watch, and its limits."
section: platforms
type: feature
order: 20
platforms: [wear-os]
related: [totp, platform-support]
---

Keyguard has a Wear OS app. The watch keeps its own vault, with its own app
password, so you can read items and one-time passwords without your phone.

### Before you begin

- A watch with Wear OS 3 or later.
- To add an account from your phone: Keyguard installed on the paired Android
  phone.

## Set up Keyguard on the watch

1. Install Keyguard on your phone and on your watch.
2. On the watch, open Keyguard.
3. Enter an app password.
4. Select **Create a vault**.
5. In **Settings**, select **Add account**.
6. Select **Bitwarden** or **KeePass**.
7. Select **Login on phone**. Keyguard opens **Watch setup** on your phone.
8. On the phone, sign in to Bitwarden, or open your KeePass database. The
   account appears on the watch, but not in the phone's vault.

For a Bitwarden account, you can select **Manual login** instead and enter your
credentials on the watch.

## Use Keyguard as a credential provider

Apps on the watch can sign in with passwords and passkeys from Keyguard. This
needs Wear OS 5 (Android 14) or later.

1. On the watch, in **Settings > Autofill**, select **Credential provider**.
   The watch's system settings open.
2. Select Keyguard.

## Accounts on the watch

| Account | Add it with | After setup |
| --- | --- | --- |
| Bitwarden | **Login on phone** or **Manual login** | Syncs with the server on its own |
| KeePass | **Login on phone** | Keeps a one-time copy of the database from the phone |

## Features on the watch

| Feature | What you can do |
| --- | --- |
| **Vault**, folders and **Favorites** | Browse items and read their fields, including the current one-time password. Selecting a field shows it full screen, with a QR code. |
| Attachments | Preview images, text, Markdown and ZIP archives |
| **Send** | View sends |
| **Generator** | Generate passwords, passphrases and PIN codes |
| **Credential provider** | Sign in to apps on the watch with saved passwords and passkeys |

## Limitations

| Area | Limitation |
| --- | --- |
| Editing | You can't add or edit items on the watch. Edit them on the phone or desktop. |
| Copying | You can't copy values on the watch. |
| Search | The watch has no search or filters. |
| KeePass | Changes to the database after setup don't reach the watch. You can't choose a local database on the watch. |
| SSO | Neither login method offers SSO, so accounts that sign in only with SSO can't be added. |
| Two-factor authentication | **Manual login** doesn't support YubiKey, Duo, Duo (Organization) or FIDO2 Web Authentication. Use **Login on phone**. |
| Credential provider | It can't create passkeys or save new passwords. |
| F-Droid | The F-Droid repo has no watch app. Install it from Google Play, or install the watch APK from GitHub Releases. |

## Troubleshooting

### "No paired Android phone found"

The watch can't reach a phone with Keyguard installed. Install Keyguard on the
paired phone, check that the watch is connected to it, then select
**Login on phone** again.
