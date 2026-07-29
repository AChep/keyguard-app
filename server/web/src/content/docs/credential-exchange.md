---
title: Credential Exchange (CXF)
description: Move passwords, passkeys and more directly between Keyguard and another app (Android).
category: accounts
order: 7
---

The **Credential Exchange Format (CXF)** defines what a password, a
passkey, a one-time password or a payment card looks like on the wire — and the
**Credential Exchange Protocol (CXP)** defines how two apps hand that data to
each other.

Keyguard implements CXF v1.0 in both directions, over the transfer
mechanism Android provides.

## Requirements

Credential exchange is an **Android** feature. Keyguard's desktop apps and Wear
OS do not take part in either direction.

- The transfer is brokered by **Google Play services**, so it is unavailable on
  builds distributed without them (the F-Droid build, for example) and on
  devices whose Play services is too old.
- Keyguard offers itself as a **source** of credentials on **Android 14 and
  newer**.

## How a transfer works

A transfer happens between two apps that support CXF on the **same device**. 
The receiving app asks the system for credentials, you pick the app to take them from, that app
asks you to confirm, and the data goes straight across into the receiving app's
own storage.

Both apps have to be installed, and both have to have implemented their half of
the exchange. Keyguard remains available as a source while its vault is
**locked**, using a cached account list that contains no credentials. If you
choose Keyguard while it is locked, Keyguard asks you to unlock the vault before
it reads or shows any items.

## Exporting to another app

A transfer is always started from the app that will *receive* the data. 
When that app asks for your credentials and you choose Keyguard, you then get a review screen naming the app that made the request, listing the items about to leave with the kinds of credential each one contributes.

A transfer covers **a single account**: the picker lists a separate entry per
account, and the one you choose there is the only account Keyguard reads. To
move a second account you run the transfer again and pick that one. Accounts
with [**Hide items**](/docs/multi-account/#hiding-an-account) turned on are not
offered at all. Trashed and [archived](/docs/items/#organizing) items are never
sent either.

## Importing from another app

Open the account you want the items to land in, and choose **Import from
another app** in its quick actions. Android shows you which installed apps can
share credentials; pick one, approve the transfer in that app, and Keyguard
brings you back to a review screen listing what it received — logins, passkeys,
one-time passwords, cards, identities, notes, SSH keys and folders, each with a
count. All importable items are selected initially; you can deselect any you do
not want. The counts and folders update to reflect the selection, and pressing
**Import** writes only the selected items and the folders needed to organize
them.

Imports are not matched against what you already have. Running the same
transfer twice gives you a second copy of every item and folder it brings
across.

## What transfers

| | Out of Keyguard | Into Keyguard |
| :-- | :-- | :-- |
| Passwords | Yes | Yes |
| Passkeys | Yes | Yes |
| One-time passwords | Yes | Yes |
| Cards | Yes | Yes |
| Identities | Yes | Yes |
| Notes | Yes | Yes |
| SSH keys | Yes | Yes |
| Custom fields | Yes | Yes |
| Folders, favorites, tags, website & app matching | Yes | Yes |

The **receiving app chooses which kinds it asks for**, and Keyguard sends only
those. A kind it did not ask for is left out quietly: that is not treated as a
skip, so nothing on the review screen reports it. An app that asks only for
passwords and passkeys gets no cards, notes, identities, SSH keys or custom
fields.

## What gets skipped

Some things are skipped:

- **Attachments.**
- **[GPG keys](/docs/gpg-keys/).**
- **Saved previous passwords** — an item's password history stays behind; the
  current password still crosses.
- **API keys, files, Wi-Fi credentials, generated passwords and item
  references** — credential kinds other apps may send that Keyguard does not
  model.
- **Linked custom fields**, and custom fields with no value — an item's other
  fields still cross.
- **Regular-expression matches** and Keyguard's own `cmd://` uris — dropped from
  an item's website & app matching, since neither is an address the other app
  could use. See [match detection](/docs/url-matching/#match-detection-modes).

### Some passkeys

Leaving Keyguard, a passkey is left out when:

- its key is not **ECDSA on the P-256 curve**. CXF v1.0 carries the private key
  with no algorithm named beside it, so that one profile is the only thing the
  other side can assume — in both directions.
- it has **no user handle**. The format requires that member, and a credential
  synced from a server that treats it as optional can arrive without one.
- its **signature counter is not zero**. The format requires those to be
  excluded; that counter is the mechanism a website uses to notice a
  [cloned authenticator](/docs/passkeys/#replay-and-clone-resistance). This one
  is an export rule only — an imported passkey is stored with a zero counter.
- one of its stored fields — credential id, private key, relying-party id — is
  missing or cannot be decoded.

### Some SSH keys

An SSH key crosses only if its private key can be converted into the form the
format asks for, and only for **RSA** and **Ed25519**. A **passphrase-protected**
private key cannot be converted; nor can one whose public half is missing or of
another type. Both directions apply the same rule.

### Some one-time passwords

Standard **TOTP** codes are supported, and Keyguard also supports **Steam Guard** codes
in both directions through the format's extensible algorithm value. **HOTP** and
**mOTP** are not supported by the format.

## Related

- [Credential Exchange Format v1.0](https://fidoalliance.org/specs/cx/cxf-v1.0-ps-errata-20260309.html)
