---
title: Item types & extras
description: Custom-field conventions that unlock extra UI — WiFi credentials with QR codes, tags, and how GPG key items sync.
category: reference
order: 6
---

Bitwarden's data model has a fixed set of item types, so Keyguard adds a
few **custom-field conventions** on top of it.

## WiFi credentials

An item shows up in a WiFi credentials form when both of these hold:

- the item is a **login** with a username set, or has a custom field with one
  of the following names:
  - `WiFi SSID`;
  - `SSID`;
- the item has one of the following custom fields:
  - `WiFi Authentication Type` — either `WPA`, `WEP`, or `nopass` if the
    network is open;
  - `WiFi Hidden` — either `true` or `false`.

The WiFi credentials UI includes a **QR code** that you can scan with another
device to quickly join the network.

## Tags

A tag is defined by a custom field that meets both criteria:

- the custom field's name is exactly `Tag`;
- the custom field's value is visible (not hidden).

The value of this qualifying field is used as the tag's name. The same rules
apply when Keyguard writes a tag back to an item.

_Tip: tags are searchable — see the [search reference](/docs/search/) for how to query them._

## GPG keys

The [GPG key](/docs/gpg-keys/) item type is a custom-field convention too. For
compatibility with Bitwarden's fixed data model, GPG keys are stored on the
server as **secure notes** whose key material lives in three custom fields:

- `keyguard.gpg.public_key.armored` — the armored public key;
- `keyguard.gpg.private_key.armored` — the armored private key, if any;
- `keyguard.gpg.fingerprint` — the fingerprint of the primary key.

This is what lets them sync through any Bitwarden-compatible server without a
custom item type.
