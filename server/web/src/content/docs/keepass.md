---
title: KeePass databases
description: "Open, create and edit KeePass (KDBX) databases in Keyguard, and how their entries map to items."
section: accounts
type: feature
order: 30
platforms: [android, wear-os, linux, macos, windows]
stage: beta
related: [multi-account, sync-and-conflicts, backups]
---

Keyguard opens, creates and edits KeePass (KDBX) databases. The database is a
single encrypted file that you store and sync yourself, on this device, a WebDAV
server or S3-compatible storage.

### Before you begin

- A verified backup of your database. Keyguard's KeePass support isn't yet
  fully compatible with other KeePass clients.

## Open a database

1. In **Settings**, select **Add account**, then **KeePass**.
2. Under **Database location**, select **Local file**, **WebDAV** or **S3**.
   For WebDAV and S3, fill in the form from
   [Storage locations](#storage-locations) and select **Save**.
3. Select **Open a database**. For a local file, choose the `.kdbx` file.
4. Enter the database password, select **Select a key file**, or do both, as
   your database requires.
5. Select **Sign in**. The database's items appear in the vault.

## Create a database

1. In **Settings**, select **Add account**, then **KeePass**.
2. Under **Database location**, select **Local file**, **WebDAV** or **S3**.
   For WebDAV and S3, fill in the form from
   [Storage locations](#storage-locations) and select **Save**.
3. Select **Create a database**. For a local file, choose where to save it.
4. Enter a password for the new database. Optionally, select a key file with
   **Select a key file**.
5. Select **Sign in**. Keyguard creates the file in the KDBX 4 format and opens
   the empty database.

## Edit a database

Add, edit and delete items and folders as in any other account. Editing follows
the [Premium](/docs/premium/) rules. Keyguard writes the changes back into the
`.kdbx` file.

If the file changed elsewhere, Keyguard merges both versions as described in
[Sync and conflict resolution](/docs/sync-and-conflicts/).

:::caution
Keyguard writes to the file directly, without a server in between. Let your
sync tool finish before you edit the database on another device, and set up
[Automatic backups](/docs/backups/).
:::

## Storage locations

| Location | What you enter |
| --- | --- |
| **Local file** | A file on this device. Sync it with your own tool, such as Syncthing. |
| **WebDAV** | **WebDAV URL** of the `.kdbx` file to open or create, plus a username and password if the server needs them. |
| **S3** | **Bucket**, **Object key** ending in `.kdbx`, **Access key ID** and **Secret access key**. Leave **Endpoint URL** empty for Amazon S3. Keep **Path-style URLs** on for most self-hosted servers. |

In the WebDAV and S3 forms, **Browse** picks the location on the server. The
credentials are for the storage server. Don't enter your database password
there.

Keyguard saves a WebDAV database to a temporary file, then moves it over the
database. If the server can't move files, Keyguard uploads the database
directly. If saving still fails, turn off **Prefer WebDAV transactions** in
**Settings > Other**. Keyguard then always uploads directly.

## KDBX mapping

| KDBX | Keyguard |
| --- | --- |
| Groups | Folders |
| Entries | Items |
| Tags | Item tags. A `Favorite` tag marks a favorite. |
| No equivalent | Organizations, collections and sends. KeePass accounts don't have them. |

## Limitations

| Platform | Limitation |
| --- | --- |
| Wear OS | The watch can't open a database itself. See [Wear OS](/docs/wear-os/#limitations). |
