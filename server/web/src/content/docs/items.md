---
title: Items and fields
description: "What an item holds: item types, custom field types, and the folders, tags, archive and trash that organize them."
section: vault
type: concept
order: 10
platforms: all
related: [totp, generator, item-extras]
---

An item is one entry in your vault, such as a login or a payment card. Each
item has a type, the built-in fields of that type, and optional custom fields,
tags and attachments.

## Item types

| Type | Holds |
| --- | --- |
| **Login** | Username, password, one-time password, URLs and passkeys |
| **Card** | Payment card details |
| **Identity** | Names, addresses and contact details |
| **Note** | Free-form secure text |
| **SSH Key** | A key pair for the [SSH agent](/docs/ssh-agent/) |
| **GPG Key** | An OpenPGP key. See [GPG keys](/docs/gpg-keys/). |

## Custom fields

Any item can hold extra fields beyond the built-in ones.

| Field type | Value |
| --- | --- |
| **Text** | A visible value |
| **Text (concealed)** | A value hidden like a password until you reveal it |
| **Boolean** | A checkbox |
| **Linked** | A reference to a built-in field, such as the login's username. Autofill can then fill that value under another field name. |

Some field names have a special meaning. An item with Wi-Fi fields, for
example, shows a QR code that another device scans to join the network. The
names and formats are listed in [Custom field conventions](/docs/item-extras/).

## Organizing

- **Folders** group items. In a Bitwarden account, a `/` in a folder name
  creates a hierarchy: `Work/Servers` sits inside `Work`. KeePass databases
  nest their groups instead.
- **Favorites** are the items you use most, listed together.
- **Archive** holds items you want to keep but not see in everyday lists.
  Archived SSH and GPG keys are unavailable to agents. Archived items can be restored.
- **Trash** holds deleted items until you restore or permanently delete them.
  Bitwarden's server purges trashed items after 30 days. KeePass databases
  keep them until you delete them.
- **Tags** label items independent of their folder. An item can carry any
  number of tags, and [Search](/docs/search/) finds them with `tag:`. KeePass
  databases store tags natively. Bitwarden items store them in custom fields,
  as listed in [Tags](/docs/item-extras/#tags).

To move, tag or merge many items at once, see
[Bulk actions](/docs/bulk-actions/).

## Linked items

An item can link to other items in the same account, such as a login to the
email account it recovers through. The editor's **Linked items** section adds
them with **Add more**.

The item view lists the targets under **Linked items**. Each target lists the
items that link to it under **Referenced by**. A link can't point to an item in
another account or in the trash. The view skips a link whose target is gone.

Links work in Bitwarden and KeePass accounts. Their storage format is in
[Item links](/docs/item-extras/#item-links).

## Attachments

Any item can hold arbitrary files, such as a recovery code image or a key file. See [Attachments](/docs/attachments/).

## Per-item protection

An item with **Auth re-prompt** turned on asks you to authenticate again
before Keyguard reveals, copies or autofills its secrets. You confirm with
your app password, or with biometrics if you turned them on. The secrets then
stay available until you close the item.

Auth re-prompt is a check in Keyguard's interface, not extra encryption. The
item is encrypted like the rest of the vault, and an unlocked vault decrypts it
like any other item. The check stops someone who picks up your unlocked
device. It doesn't stop malware, or anyone who knows your app password.

The setting is a flag saved with the item, and each app decides whether to
honor it. Keyguard on Wear OS doesn't ask. Other Bitwarden and KeePass apps can
show the item without asking.

## Password history

A login keeps its earlier passwords, so a replaced password isn't lost. The
item's **View password history** action lists them.

## Type from memory

The menu of a login's password has **Type from memory**. You enter the password
without looking at it, and Keyguard compares it with the saved one on the
device. Nothing is stored or sent.

## Field actions

A field's menu holds actions for its value, besides copying it.

| Field | Action | Platforms |
| --- | --- | --- |
| Website URI | **Launch browser**, and **Launch main page** for a URI that isn't a site's main page | All |
| Website URI | **Check the website in known data breaches**. See [Watchtower](/docs/watchtower/). | All |
| `http://` URI | **Auto-fix insecure URL** switches it to `https://` if the site responds there | All |
| Website URI | **How to delete an account?** and **How to get my data?**, from the bundled [JustDeleteMe](https://justdeleteme.xyz/) and [JustGetMyData](https://justgetmydata.com/) lists | All |
| App URI | **Launch app**, if the app is installed | Android |
| Other URI | **Launch with…** opens the URI in an app that handles it | Android |
| Phone number | **Call** and **Text** | Android |
| Email | **Email** opens your email app | All |
| Visible field | **Create a send**. See [Sends](/docs/bitwarden/sends/). | All |

### QR codes and large type

Most fields' menus can show the value as a scannable code or in large type,
for another device or person to read. Both keep the screen on.

**Show as Barcode** shows a QR code, or a **Code 39 1D**, **Code 93 1D**,
**Code 128 1D** or **PDF417** barcode. Keyguard remembers the format. An
authenticator key offers only a QR code.

**Show in Large Type** shows each character large and numbered, with digits
and symbols in their own colors. Select a character to mark your place.

| Action | Values up to | Platforms |
| --- | --- | --- |
| **Show as Barcode** | 1,024 characters | All |
| **Show in Large Type** | 128 characters | All |
| **Show in Large Type and Lock vault** | 128 characters. Also locks the vault, so you can hand your phone to someone. | Android |
