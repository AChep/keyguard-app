---
title: Bulk actions
description: "Select several vault items, then move, rename, tag, merge, archive, trash or export them in one step."
section: vault
type: feature
order: 100
platforms: all
related: [items, watchtower]
---

Select several items to change them in one step. Selection works in the
merged list of all your accounts, in a single account, and in Watchtower's
lists.

### Before you begin

- Actions that change items follow the [Premium](/docs/premium/) rules.

## Select items

1. Long-press an item. On desktop, you can also select it with the right
   mouse button. A selection bar appears.
2. Select more items to add them, or select **Select all**.
3. Select an action in the selection bar.

To leave the selection, select **Clear selection**.

## Merge duplicates

1. Select two or more items of the same type.
2. Select **Merge into…**. The **Merge items** screen opens.
3. Edit the merged item as needed.
4. Choose what happens to the original items: **Keep origin items** (the
   default), **Move origin items to archive** or **Move origin items to trash**.
5. Select **Save**.

The merged item combines the URLs, custom fields, tags and passkeys of all
selected items. Every other field takes its most common value. Attachments
aren't merged: they stay on the original items.

Watchtower's **Duplicate Items** list groups likely duplicates, so you can
select and merge them there. See [Watchtower](/docs/watchtower/).

## Actions

The selection bar shows only the actions that fit the selected items.

| Action | What it does |
| --- | --- |
| **Add to favorites**, **Remove from favorites** | Adds the items to favorites, or removes them |
| **Enable auth re-prompt**, **Disable auth re-prompt** | Turns the authentication re-prompt on or off |
| **Change names** | Shows one name field per item |
| **Change tags** | Edits the tags of all selected items |
| **Change passwords** | Shows one password field per login. Only when every item is a login. It reads **Add passwords** when no item has a password. |
| **Merge into…** | Merges the items into one. Only for two or more items of the same type. |
| **Create a send** | Creates a send from values you pick in the items |
| **Copy to…** | Copies the items to another account, folder or organization. Attachments aren't copied. |
| **Move to folder** | Moves the items to a folder. Only when all items are in one account. |
| **Configure Watchtower alerts** | Chooses which Watchtower alerts apply to the items |
| **Export…** | Exports the selected items |
| **Archive**, **Restore from archive** | Moves the items to the archive, or back |
| **Trash**, **Restore from trash** | Moves the items to the trash, or back |
| **Delete forever** | Deletes the items permanently. Only when every item is in the trash or not synced yet. |

With one item selected, the bar also offers **Edit** and, for a login with
earlier passwords, **View password history**.
