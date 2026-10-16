---
title: Credential Exchange
description: "Move passwords, passkeys and other items between Keyguard and another app on the same device."
section: backup
type: feature
order: 30
platforms: [android]
related: [migrating, export, passkeys]
---

Credential Exchange moves items between Keyguard and another password manager
on the same Android device. Keyguard implements the FIDO Alliance's
[Credential Exchange Format v1.0](https://fidoalliance.org/specs/cx/cxf-v1.0-ps-errata-20260309.html)
in both directions, over the transfer that Android provides.

### Before you begin

- A Google Play build of Keyguard, on a device with a recent version of Google
  Play services.
- Another app on the same device that supports Credential Exchange.
- To export from Keyguard, Android 14 or later.

## Importing from another app

1. On the screen of the account the items go to, select
   **Import from another app**.
2. Select **Start import**. Android shows the apps that can share credentials.
3. Select the app to import from, and approve the transfer in that app.
4. Back in Keyguard, review the items. All items are selected; clear the ones
   you don't want.
5. Select **Import**. Keyguard saves the selected items, and the folders they
   need, to the account.

Keyguard doesn't remove duplicates. Importing the same items twice creates two
copies of every item and folder.

## Export to another app

A transfer starts in the app that receives the items.

1. In the other app, start an import from another app. Android shows the apps
   that can share credentials.
2. Select the Keyguard account to send.
3. If Keyguard is locked, unlock it.
4. Review the items and the kinds of credential each one sends.
5. Select **Transfer**.

A transfer sends one account, and each account is a separate entry. To send
another account, run the transfer again.

Each entry shows the account's name, or **Keyguard** for an account without
one. To tell such accounts apart, give each a name with **Change name**. See
[Account name and color](/docs/multi-account/#account-name-and-color).

Keyguard doesn't offer accounts with **Hide items** turned on. See
[Hiding an account](/docs/multi-account/#hiding-an-account). While the vault is
locked, Android sees only a cached list of accounts that holds no credentials.

## What transfers

Both directions carry passwords, passkeys, one-time passwords, cards,
identities, notes, SSH keys and custom fields. Items keep their folder,
favorite status, tags, and website and app matching.

The receiving app chooses which kinds of credential it asks for, and Keyguard
sends only those. Kinds it doesn't ask for are left out without a warning. An
app that asks only for passwords and passkeys gets no cards, notes, identities,
SSH keys or custom fields.

## Skipped items

The review screen counts what Keyguard skips.

| Data | Skipped on |
| --- | --- |
| Attachments | Both directions |
| [GPG keys](/docs/gpg-keys/) | Export |
| Archived items and items in the trash | Export |
| Password history | Export. Only the current password transfers. |
| Linked custom fields, and custom fields with no value | Export |
| Regular-expression URLs and Keyguard's `cmd://` URLs | Export |
| API keys, Wi-Fi credentials, generated passwords and item references | Import. Keyguard has no matching item type. |

### Passkeys

A passkey is skipped when:

- Its key isn't ECDSA on the P-256 curve. The format carries the private key
  without naming its algorithm, so both sides assume this one. This applies in
  both directions.
- It has no user handle. The format requires one, and a passkey synced from a
  server can lack it.
- Its signature counter isn't zero. The format requires these to be left out.
  This rule applies to exports only; an imported passkey gets a counter of
  zero. See [How Keyguard handles passkeys](/docs/passkeys/#how-keyguard-handles-passkeys).
- Its credential ID, private key or relying party ID is missing or can't be
  decoded.

### SSH keys

An SSH key transfers only if its private key converts to the form the format
requires, and only for RSA and Ed25519 keys. A passphrase-protected private
key can't convert, nor can one whose public key is missing or of another type.
The same rule applies in both directions.

### One-time passwords

Standard TOTP codes transfer, and so do Steam Guard codes, in both directions.
The format doesn't support HOTP or mOTP.
