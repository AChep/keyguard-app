---
title: Items, fields & organizing
description: Item types, custom fields, folders, favorites, archive, trash, and the per-item re-prompt.
category: guides
order: 1
---

Everything in your vault is an **item**. Keyguard supports six types:

- **Login** — username, password, one-time password, and any number of URLs;
- **Card** — payment cards;
- **Identity** — names, addresses, and contact details;
- **Note** — free-form secure text;
- **SSH key** — key pairs used by the [SSH agent](/docs/ssh-agent/).
- **GPG key** — key pairs used by the [GPG agent](/docs/gpg-agent/).

## Custom fields

Any item can carry extra fields beyond the built-in ones. Four field types
are available:

- **Text** — a plain visible value;
- **Hidden** — concealed like a password, revealed on demand;
- **Boolean** — a checkbox;
- **Linked** — a reference to one of the item's built-in fields (for
  example, the login's username), useful when a site's form needs a value
  under a different field name during autofill.

Some custom-field names carry special meaning — `Tag`, `WiFi SSID`, and
friends — see [Item types & extras](/docs/item-extras/).

> Items carrying WiFi credentials get a dedicated **WiFi view** with a QR
> code: scan it with another device to join the network without typing the
> password. The field conventions that enable it are described in
> [Item types & extras](/docs/item-extras/#wifi-credentials).

## Organizing

- **Folders** can be nested: use `/` in a folder name to create a hierarchy,
  like `Work/Servers`. Move items with the **Move to folder** action.
- **Favorites** pin the items you use most.
- **Archive** tucks away items you want to keep but don't want in everyday
  lists — archived items can be restored at any time.
- **Trash** holds deleted items until you restore or permanently delete them.
  (Bitwarden's own server purges trashed items after **30 days**; local
  KeePass vaults keep them until you remove them.)
- **Tags** label items across folders — see [Tags](#tags) below.

For bulk work, long-press to **multi-select** items and apply batch actions —
move several items at once, or **merge** duplicates into a single item
(handy when Watchtower reports duplicate entries).

## Tags

Tags label items independently of folders — an item can carry any number of
them, and the [search](/docs/search/) can filter on them. How a tag is
stored depends on the account type: [KeePass](/docs/keepass/) uses the
database's native tags, while Bitwarden items use a
[custom-field convention](/docs/item-extras/#tags).

## Per-item protection

Sensitive items can demand an extra step: enable the **authentication
re-prompt** on an item and Keyguard asks you to authenticate again whenever
the item is viewed or autofilled.

## Password history

Login items keep a **password history**, so a password you replaced —
deliberately or not — is never simply gone. Open an item's menu and choose
**View password history**.
