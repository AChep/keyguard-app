---
title: Sync & conflict resolution
description: How offline edits sync back, and how Keyguard merges conflicting edits field by field.
category: accounts
order: 5
---

Keyguard keeps a local copy of your vault, so you can view, add, and edit items
without a connection. Changes sync back to the cloud when you are online again;
local providers such as KDBX do not need active Internet conenction. If the
same item was edited in two places in the meantime, Keyguard merges both edits
field by field.

> A conflict can happen when you edit an item on a device without an active
> internet connection and then edit the same item on another device.

## How merging works

Keyguard uses a **three-way merge**. Alongside your local copy, the app keeps the
last version of each item synced from the server and uses it as the merge
**base**. Comparing both sides against that base identifies which side changed
each field.

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
