---
title: Migrating to Keyguard
description: How to bring your passwords into Keyguard — and how to take them with you if you leave.
category: get-started
order: 3
---

Keyguard can store your data either in a
[Bitwarden account](https://bitwarden.com) or in a [KeePass (KDBX) file](https://keepass.info/help/kb/kdbx.html).

## Coming from another password manager

Pick the path that matches where your vault will live:

### Into a Bitwarden account

Use the **Bitwarden web vault's importer**: log in to your server's web vault
([bitwarden.com](https://vault.bitwarden.com), [bitwarden.eu](https://vault.bitwarden.eu), or your self-hosted instance), open **Tools →
Import data**, and pick your old manager's export format — Bitwarden supports
dozens. Once imported, sign in to that account in Keyguard and your items
sync down automatically.

### Into a KeePass database

If you prefer a local, server-free vault, export your data from your old
manager into a **KDBX** file (most managers and converters can produce one)
and simply [open it in Keyguard](/docs/keepass/).

### Directly from another Android app

First choose whether you want to use Keyguard with a Bitwarden account or 
a local KDBX database.

Then, if your old password manager is installed on the same Android device and
supports the [credential exchange](/docs/credential-exchange/) standard: open
the account you want the items to land in, choose **Import from another app**,
and pick it from the list — logins, passkeys, one-time passwords, cards,
identities, notes, SSH keys and folders are supported.

## Coming from the official Bitwarden apps

There is nothing to migrate — sign in with the same account and server, and
the same vault appears. Keyguard can run side by side with the official apps.

## Leaving Keyguard

Your data is never locked in:

- **Bitwarden accounts** stay compatible with the official clients and
  the web vault at all times.
- **KeePass databases** remain ordinary `.kdbx` files that other KeePass
  apps can open.
- Keyguard can also **export** your items as a password-protected, encrypted
  ZIP archive containing JSON data — optionally including attachments. Find
  it via the export action, and see the
  [data export feature page](/features/data-export/) for details.
- On Android, another app can **import one account's items directly** through
  [credential exchange](/docs/credential-exchange/) — a narrower copy than the
  archive above: no attachments, no [GPG keys](/docs/gpg-keys/), no archived
  items, and only the credential kinds the other app asks for.
