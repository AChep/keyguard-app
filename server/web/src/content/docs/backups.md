---
title: Automatic backups
description: "Save versioned snapshots of your vault to a folder, a WebDAV server or S3-compatible storage after each change."
section: backup
type: feature
order: 40
platforms: all
related: [export, migrating]
---

Keyguard keeps versioned snapshots of your whole vault in a folder, on a WebDAV
server or in S3-compatible storage. It writes a new snapshot each time the
vault changes.

## Set up backups

1. In **Settings > Backups**, select **Set up backups**.
2. Select a destination: **Local folder**, **WebDAV server** or
   **S3-compatible storage**.
3. Enter the location. For a local folder, select **Choose folder…**.
4. Select **Continue**.
5. Optionally, enter a backup password and confirm it.
6. Select **Continue**.
7. Choose **Include attachments** and **Keep snapshots**.
8. Select **Continue**.
9. Review the setup, then select **Enable backups**. Keyguard checks that it
   can read and write the destination.

## Run a backup now

In **Settings > Backups**, select **Run backup now**. Keyguard writes a
snapshot right away.

## Turn off backups

1. In **Settings > Backups**, select **Turn off automatic backups**.
2. Select **OK**. Keyguard stops future backups and forgets the destination,
   password and attachment choice. The existing backup files stay in place.

## Recover data from a backup

Keyguard imports a snapshot of a backup into one account.

1. Start the import:
   - For the backup set up on this device, in **Settings > Backups**, select
     **Import from backup**. With several accounts, select the account.
   - For any other backup, on the screen of the account the items go to,
     select **Import from another app**. Under **Keyguard backup**, select
     where the backup is, then select its folder or enter its server.
2. If the backup has a password, enter it.
3. Select the snapshot. The newest one is selected.
4. Review the items, then select **Import**.

A snapshot holds every account, and all its items go to the one account.
Items in the trash return to the trash. An environment's references to other
items are unset; set them again after the import.

To read the files by hand, decrypt `.zip.age` files with [age](https://age-encryption.org/), then extract the ZIP.
Use an AES-256 ZIP tool for snapshots, attachments and older `.zip` files.
Without a backup password, every file is a regular ZIP archive.

1. Decrypt `repo.zip.age` with the backup password and open `repo.json`. Save
   its `keys.index` value to a file, such as `key.txt`.
2. In `indexes/`, take the archive with the highest number. Decrypt a
   `.zip.age` index with `age -d -i key.txt`. Open an older `.zip` index with
   the backup password. It holds `index.json`.
3. In `index.json`, under `snapshots`, find the snapshot to recover. Note its
   `path` and `encryption.keyBase64` values.
4. Open the snapshot archive at that `path`. Enter the `keyBase64` value as
   the password.
5. Open `vault.json`. It has the format described in
   [Export your vault](/docs/export/).

To recover an attachment:

1. In the snapshot's `manifest.json`, find the attachment's `blobId`.
2. In `index.json`, under `blobs`, find that ID. Note its `path` and
   `encryption.keyBase64` values.
3. Open the blob archive at that `path`, with the `keyBase64` value as the
   password. It holds the file as `attachment.bin`. The manifest gives its
   `fileName`.

## When backups run

Backups are change-triggered. After the vault changes, Keyguard waits about 5
seconds, so a burst of edits becomes one snapshot, then writes a backup.
Backups run only while the vault is unlocked.

## What's included

A snapshot holds the entire vault, every account at once: items with their
folders, collections and organizations. **Include attachments** adds attached
files, but only those stored on the server.

Sends aren't included.

## Destinations

| Destination | Where it is | Notes |
| --- | --- | --- |
| **Local folder** | A folder on this device or a connected storage provider | A synced cloud-drive folder gives you an off-device copy |
| **WebDAV server** | A WebDAV server | The server must support moving files, unless you turn off **Prefer WebDAV transactions** in **Settings > Other** |
| **S3-compatible storage** | A bucket on Amazon S3 or another S3-compatible service | The service must support conditional writes |

## Encryption

The backup password is optional.

- New password-protected repositories encrypt metadata with age, using scrypt and the backup password.
  Indexes use age with a random key stored in that metadata, so a backup derives a key from the password once.
  Snapshots and attachments use AES-256 ZIP encryption with separate random passwords.
- Without a password, backups are stored unencrypted.

:::caution
An unencrypted backup exposes your whole vault to anyone who can read the
files. Set a backup password for backups in a shared folder or in the cloud.
:::

The backup password is separate from your app and account passwords. If you
lose it, the backup can't be decrypted. Every device that shares a repository
must use the same password.

Older encrypted repositories keep their ZIP format until you select **Upgrade backup encryption** in **Settings > Backups**.
The action appears once a backup run finds an older repository.
Stop backups on other devices and update Keyguard everywhere first.

The upgrade creates and verifies `repo.zip.age`, then replaces `repo.zip` with a compatibility marker containing no keys or password verifier.
A backup runs right after.
Existing indexes, snapshots and attachments stay unchanged. Future indexes use `.zip.age`.

Keyguard reads both index formats.
If interrupted, run the action again. Older Keyguard versions cannot use the upgraded repository.

Kept encrypted ZIP indexes retain weaker password protection.
Anyone who cracks their password can also decrypt the new age archives using that password.
Storage providers may also retain the old `repo.zip` in version history or trash.

The encryption mode is fixed when the repository is created. To switch between
encrypted and unencrypted, start over with a new backup location.

## Snapshot retention

**Keep snapshots** sets how many snapshots Keyguard keeps: 5, 10, 30, 60, 90,
or **Never clear**. The default is 30. Keyguard prunes older snapshots beyond
the limit, and removes attachments that no kept snapshot uses.

Keyguard spreads the kept snapshots over time, so a burst of edits doesn't push
out older history:

| Age | What Keyguard keeps |
| --- | --- |
| Newest | Always kept, whatever its age |
| Last 24 hours | The most recent snapshots, in the room left over |
| 1 to 7 days | About one per day: the newest snapshot of each day |
| 1 to 4 weeks | About one per week |
| Older than about 31 days | Dropped, except the newest snapshot |

All of this fits inside your limit. With a small limit, Keyguard keeps the
newest snapshot first, then the weekly ones, then the daily ones. A larger
limit spends the extra room on recent snapshots. The default keeps about one
month of history.

## Repository structure

A new encrypted backup location holds these files. Upgraded repositories can also contain older `.zip` indexes. Unencrypted repositories use `.zip` throughout:

```text
├─ repo.zip.age
├─ repo.zip
├─ indexes/
│  ├─ 00000000000000000001-<ID>.zip.age
│  └─ 00000000000000000002-<ID>.zip.age
├─ snapshots/
│  ├─ 20260528104211-<ID>.zip
│  └─ 20260528121804-<ID>.zip
└─ blobs/
   └─ 4f/
      └─ 9a/
         └─ 4f9a<...>.zip
```

| Path | Contents |
| --- | --- |
| `repo.zip.age` | Encrypted repository metadata, including the index key |
| `repo.zip` | Compatibility marker with no keys or password verifier; prevents older clients from treating the folder as empty |
| `indexes/` | Numbered indexes that list the snapshots and blobs, with the password of each one. The highest number is the newest. |
| `snapshots/` | One archive per snapshot, named by its local date and time, holding `vault.json` and `manifest.json` |
| `blobs/` | One archive per attachment, under a random ID, holding `attachment.bin` |

When both `.zip` and `.zip.age` exist for the same index, Keyguard uses the `.zip.age` copy.
An unreadable `.zip.age` copy never causes a fallback to its `.zip` twin.

An attachment is stored once as a blob and shared by every snapshot that
references it. With a backup password, the metadata uses the backup password, the indexes
use the index key stored in the metadata, and each snapshot and blob has its
own password, kept in the encrypted index.
