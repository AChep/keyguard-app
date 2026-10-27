---
title: Sync and conflict resolution
description: "How offline edits sync back to the server, and how Keyguard merges conflicting edits field by field."
section: accounts
type: concept
order: 50
platforms: all
related: [multi-account, keepass, backups]
---

Keyguard keeps a local copy of your vault, so you can view, add and edit items
without a connection. A conflict happens when the same item changes in two
places before they sync, such as on two devices while one is offline.

## Offline changes

Changes made offline stay on the device and sync to the server when you're
online again. For a KeePass account, the database file plays the server's role,
and a local file needs no connection.

## How merging works

Keyguard resolves a conflict with a three-way merge. Alongside your local copy,
it keeps the last version of each item synced from the server, and uses it as
the merge base. Comparing both sides against the base shows which side changed
each field.

Keyguard merges the item field by field:

- A field edited on one side only, local or remote, keeps that change.
- A field edited on both sides takes the remote version.
- Entries added to or removed from a list, such as custom fields, are replayed
  onto the merged item.

Without a base version, the remote item wins. On Bitwarden accounts, a
password, authenticator key or hidden custom field that loses a merge moves to
the item's password history.

## Examples

| Device A | Device B | Result |
| --- | --- | --- |
| Edits the username | Edits the password | Both changes are kept |
| Edits the username | Edits the same username | The edit that reached the server first wins |
| Adds a custom field | Adds a different custom field | The item has both new fields |
