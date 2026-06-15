---
title: Sync & conflict resolution
description: How offline edits sync back, and how Keyguard merges conflicting edits field by field.
category: accounts
order: 4
---

Keyguard keeps a local copy of your vault, so you can view, add, and edit items
without a connection. Changes sync back when you are online again — and if the
same item was edited in two places in the meantime, Keyguard merges the edits
instead of blindly keeping one version.

> A conflict can happen when you edit an item on a device without an active
> internet connection and then edit the same item on another device.

## How merging works

Under the hood this is a **three-way merge**. Alongside your local copy,
Keyguard keeps the last version of each item it synced from the server and
uses it as the merge **base** — comparing both sides against that base is
what tells Keyguard *which* side actually changed a field, rather than
guessing from timestamps.

Keyguard splits the item into separate fields and merges them one by one:

- a field edited on **one side only** (locally *or* remotely) keeps that
  change;
- a field edited on **both sides** takes the remote (server) version;
- items **added or removed** from a list are replayed onto a new base item.

### Examples

- You edit the **Username** on one device and the **Password** on another —
  both changes are kept and merged together.
- You edit the **Username** on one device and the same **Username** on another
  — the change from the server wins.
- You add a **custom field** on one device and a different custom field on
  another — both are kept: the item ends up with the two new fields.
