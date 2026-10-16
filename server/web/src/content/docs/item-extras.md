---
title: Custom field conventions
description: "Custom-field names that Keyguard reads as Wi-Fi credentials, tags, item links and GPG keys."
section: reference
type: reference
order: 40
platforms: all
related: [items, gpg-keys, search]
---

Keyguard reads these custom-field names as extra item data. Other clients show
them as plain custom fields.

## GPG keys

On a Bitwarden server, a GPG key item is stored as a secure note with these
fields.

| Field | Type | Value |
| --- | --- | --- |
| `keyguard.gpg.private_key.armored` | Hidden | Armored private key, if the item has one |
| `keyguard.gpg.public_key.armored` | Text | Armored public key |
| `keyguard.gpg.fingerprint` | Text | Fingerprint of the primary key |

A key longer than 3,500 bytes is split into numbered fields, such as
`keyguard.gpg.public_key.armored.v1.part.1`, plus a
`keyguard.gpg.public_key.armored.v1.sha256` checksum field.

## Item links

Keyguard stores each of an item's [Linked items](/docs/items/#linked-items) as
a text custom field. KeePass databases store them as entry string fields with
the same names.

| Field | Value |
| --- | --- |
| `keyguard.link.1`, `keyguard.link.2`, … | `keyguard://cipher/<ITEM_ID>`, the ID of the linked item in the same account |

Numbers start at 1, without gaps. Keyguard hides these fields from the item's
custom fields. A field with an invalid value stays a regular custom field.

## Tags

In a Bitwarden account, Keyguard stores each tag as a custom field. KeePass
databases use KeePass tags instead.

| Field | Value |
| --- | --- |
| `Tag` | The tag's name, in a text field |

Keyguard writes tags back in the same format.

## Wi-Fi credentials

An item shows a Wi-Fi QR code when it has a network name and at least one of the
other two fields. A login's username then shows as **SSID**.

| Field | Value |
| --- | --- |
| `WiFi SSID` or `SSID` | Network name. A login's username, if set, takes its place. |
| `WiFi Authentication Type` | `WPA`, `WEP`, or `nopass` for an open network |
| `WiFi Hidden` | `true` or `false` |
