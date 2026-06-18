---
title: KeePass (KDBX)
description: Open, edit, and create KeePass databases in Keyguard — what maps to what, and what to watch out for.
category: accounts
order: 3
---

Keyguard can work with **KeePass (KDBX)** databases:
all your passwords live in a single, highly-encrypted file stored directly on
your device. You have complete control over your data — keep the file on your
computer or a USB drive, or sync it yourself with a service like Syncthing,
Google Drive, or Dropbox.

> **Beta.** The KeePass implementation is in beta and is not yet fully
> compatible with standard KeePass clients. Make sure you have a verified
> backup of your database before using it.

## Opening and creating databases

When adding an account, choose KeePass and either **open an existing
database** or **create a new one**. The database is a local file picked
through the system file picker — Keyguard does not fetch KDBX files from a
URL or cloud service itself. New databases are created in the **KDBX 4**
format.

To unlock a database you enter its **master password** and, if the database
uses one, select its **key file**.

## Editing

KeePass support is read-write: you can add, edit, and delete items and
folders, and Keyguard saves the changes back into the `.kdbx` file. Because
the file is plain storage rather than a server, treat external syncing with
care — let your sync tool finish before editing the same database on another
device, and keep [backups](/docs/backups/).

## How KDBX maps to Keyguard

Keyguard presents KeePass data through the same model it uses for Bitwarden:

| KDBX                          | Keyguard                                   |
| :---------------------------- | :----------------------------------------- |
| Groups                        | Folders                                    |
| Entries                       | Items (logins, and other types)            |
| Tags                          | Item tags; a `Favorite` tag marks a favorite |

## What does not apply

Some of Keyguard's features are built on Bitwarden's server platform and have
no KeePass equivalent:

- **Organizations and collections** — KDBX has no concept of them;
- **Sends** — a Bitwarden platform feature.

On **Wear OS**, a KeePass database is a one-time sync: the watch receives a
copy from the phone, and choosing a local database from the watch itself is
not supported.
