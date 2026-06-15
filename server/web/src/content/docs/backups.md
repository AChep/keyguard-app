---
title: Backups
description: How Keyguard's automatic backups work — what's saved, where, encryption, retention, and how to recover.
category: accounts
order: 5
---

Keyguard can keep an automatic, versioned backup of your vault so you always
have a recent copy to fall back on. Set it up once under **Settings → Automatic
backups** and it runs quietly in the background from then on.

## What's included

A single snapshot captures your **entire vault — every account at once**:
logins, cards, identities, notes, SSH keys, and so on, along with the folders,
collections, and organizations they belong to. Item **attachments** are
included too (you can turn this off), though only attachments stored on the
server are captured.

Not included: **Bitwarden Sends**.

## Automatic backups

Backups are **change-triggered**, not on a fixed schedule. Whenever your vault
changes, Keyguard waits about five seconds — long enough to fold a burst of
edits into one snapshot — then writes a new backup. Backups run only while your
vault is **unlocked**.

### Run a backup now

On the Automatic backups screen, **Run backup now** writes a snapshot immediately.

## Where backups are stored

When you enable backups you choose a location:

| Location | Where it is | Notes |
| :-- | :-- | :-- |
| **Folder** | A local folder in the file system | Point it at a synced folder (e.g. a cloud-drive folder) for off-device copies |
| **WebDAV** | A WebDAV server | - |

When you add a location, Keyguard runs a quick read/write check to make sure it's usable.

## Encryption

You can set an optional **backup password** when enabling backups:

- **With a password**, the whole repository is encrypted with AES-256.
- **Without a password**, backups are stored **unencrypted**.

> If your backups live anywhere shared or in the cloud, set a backup password.
> An unencrypted backup exposes your entire vault to anyone who can read the
> files.

Two things to remember:

- The backup password is **separate** from your account/master password, and if
  you lose it the backup **cannot be decrypted**. Keep it somewhere safe.
- The encryption mode is **fixed once the repository is created**. To switch
  between encrypted and unencrypted, start fresh with a new backup location.

## Keeping older copies

Keyguard keeps a rolling set of snapshots, so you can return to an earlier state
and not just the latest one. Choose how many to keep — **5, 10, 30, 60, 90, or
never clear** (the default is 30). Beyond the limit, older snapshots are pruned
(the newest is always kept), and attachments no longer referenced by any kept
snapshot are cleaned up.

### How snapshots are bucketed

Pruning is not simply "keep the newest *N*." Because backups are
change-triggered, a burst of edits can write several snapshots within minutes,
so keeping only the most recent ones would collapse your history down to the
last hour. Instead Keyguard **buckets snapshots by age** and keeps a
representative one from each bucket, so the copies you retain fan out over time:

- The **newest snapshot is always kept**, whatever its age.
- **Last 24 hours** — the most recent snapshots fill whatever room is left over,
  so very recent states stay dense.
- **Previous week** (1–7 days old) — about **one per day**: the newest snapshot
  in each day-wide bucket.
- **Rest of the month** (1–4 weeks old) — about **one per week**.
- **Older than ~31 days** — dropped (the single newest snapshot aside).

Everything still fits inside the limit you chose, and the buckets set the
priority when that limit is tight. A small limit favors spreading the kept
copies across time — newest first, then the weekly buckets, then the daily ones
— over piling up near-identical snapshots from the last hour; a larger limit
spends the extra room on the most recent snapshots. So the default of 30 keeps a
tail that reaches back about a month — dense for the last day, daily for a week,
then weekly — rather than 30 copies all from this afternoon.

## Repository structure

A backup location holds a small, self-contained repository of ZIP files:

```text
├─ repo.zip
├─ indexes/
│  ├─ 000000000001-a1b2.zip
│  └─ 000000000002-c3d4.zip
├─ snapshots/
│  ├─ 2026-05-28T10-42-11Z-a1b2.zip
│  └─ 2026-05-28T12-18-04Z-c3d4.zip
└─ blobs/
   └─ 4f/
      └─ 9a/
         └─ 4f9a...e7.zip
```

A few properties of the layout worth knowing:

- **Snapshots are small; attachments are deduplicated.** An attachment is
  stored once as a blob and shared across snapshots — each snapshot only
  records which blobs it references.
- **Blob names reveal nothing** — blobs are stored under random ids, not
  file names.
- **Encryption is per-object.** With a backup password set, the metadata,
  snapshots, and blobs are each AES-256-encrypted ZIPs, and the per-object
  keys live inside the encrypted index zips.

## Restoring a backup

There is **no one-click restore inside the app yet** — recovery is currently
manual, using the layout above.
