---
title: Import from another password manager
description: "Move your passwords into Keyguard through a Bitwarden account, a KeePass database or Credential Exchange."
section: backup
type: how-to
order: 10
platforms: all
related: [bitwarden/self-hosted, keepass, credential-exchange, export]
---

To move your items into Keyguard, import them into the Bitwarden account or
KeePass database that Keyguard opens.

### Before you begin

- A Bitwarden account or KeePass database to import into. Keyguard stores items
  in one of the two. See [Connect to a Bitwarden server](/docs/bitwarden/self-hosted/)
  or [KeePass databases](/docs/keepass/).

## Import into a Bitwarden account

The Bitwarden web vault reads the export formats of most password managers.

1. In your old password manager, export your data.
2. Sign in to your server's web vault:
   [vault.bitwarden.com](https://vault.bitwarden.com),
   [vault.bitwarden.eu](https://vault.bitwarden.eu), or your self-hosted
   server.
3. Select **Tools > Import data**.
4. Select your old password manager's format.
5. Import the exported file.
6. In Keyguard, add the account, as in
   [Connect to a Bitwarden server](/docs/bitwarden/self-hosted/). If you added
   it before, sync it. The items appear in your vault.

## Import into a KeePass database

1. In your old password manager, export your data.
2. In a KeePass app, such as [KeePassXC](https://keepassxc.org), import the
   export into a new database.
3. Open the database file in Keyguard, as in
   [KeePass databases](/docs/keepass/).

## Import from another Android app

If your old password manager is on the same Android device and supports
Credential Exchange, follow
[Importing from another app](/docs/credential-exchange/#importing-from-another-app).

## Switch from the official Bitwarden apps

In Keyguard, sign in to the same account and server. Your vault syncs, and
Keyguard runs side by side with the official apps.

## Verify

In Keyguard, search for one of the imported items. It appears in the results.
