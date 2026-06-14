---
title: Migrating to Keyguard
description: How to bring your passwords into Keyguard — and how to take them with you if you leave.
category: get-started
order: 3
---

Keyguard is a **client**, not a separate vault format: your data lives in a
[Bitwarden account](https://bitwarden.com) or a [KeePass (KDBX) file](https://keepass.info/help/kb/kdbx.html), and Keyguard works with it. That
shapes how migration works — there is no CSV or JSON file import in the app
itself.

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
