---
title: Multiple accounts
description: "Connect several Bitwarden and KeePass accounts and see their items in one merged vault."
section: accounts
type: concept
order: 40
platforms: all
premium: true
related: [bitwarden/self-hosted, keepass, sync-and-conflicts, premium]
---

Keyguard holds several accounts at once and shows their items in one vault. An
account is a Bitwarden account on any server, or a KeePass database.

## The merged vault

The vault list shows the items of every account together, and
[Search](/docs/search/) covers all of them. Filters narrow the list to one
account, folder or item type. [Bulk actions](/docs/bulk-actions/) work across
accounts.

## The account screen

**Settings** lists your accounts, and selecting one opens its screen. The
account screen shows only that account's items, folders and settings.

## Account name and color

Each account has a name and a color, set with **Change name** and
**Change color** on the account screen. The color marks the account in the
account list and in the vault's account filter.

## Hiding an account

The **Hide items** switch on the account screen removes the account's items
from the main screens of the app. The account stays signed in, keeps syncing,
and stays in the account list. Its own screen still lists its items.

Hiding suits accounts you rarely open, such as an archive or a shared family
vault.

## Copying items between accounts

The **Copy to…** item action copies items into another account. The
destination is an account, plus a folder, and on Bitwarden an organization and
its collections.

The copy is a new, independent item: the original stays in its account, and
editing one doesn't change the other. Attachments aren't copied.
