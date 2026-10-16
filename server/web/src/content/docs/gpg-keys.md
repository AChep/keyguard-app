---
title: GPG keys
description: "The GPG Key item: identifiers, algorithms, edits, import and export, GPG tools and keyservers."
section: developer
type: reference
order: 50
platforms: all
related: [gpg-agent, generator, item-extras, watchtower, git-signing]
---

A **GPG Key** item holds an OpenPGP certificate and, optionally, its private
key. This page lists what Keyguard reads from a key, what you can do with it,
and how Keyguard uses keyservers.

## Storage

- On a Bitwarden server, a GPG key item is a secure note with custom fields.
  [Custom field conventions](/docs/item-extras/) lists them.
- Keyguard stores an imported private key without its passphrase. It asks for
  the passphrase once, during import. From then on, the vault's encryption
  protects the key.

## Certificate versions

An OpenPGP certificate is a primary key with its subkeys, user IDs and
metadata, bound by self-signatures. Keyguard supports two versions.

| Version | Standard | User IDs |
| --- | --- | --- |
| v4 | RFC 4880 | At least one active user ID |
| v6 | RFC 9580 | Optional. A Direct Key self-signature holds the key-wide policy. |

## Identifiers

| Identifier | What it is | Format in Keyguard |
| --- | --- | --- |
| **Fingerprint** | Hash over the public key material and creation time; identifies the whole certificate | 40 hex digits for v4, 64 for v6; upper-case, grouped in fours |
| **Key ID** | The low 64 bits of a v4 fingerprint, or the high 64 bits of a v6 fingerprint | 16 upper-case hex digits |
| **Keygrip** | libgcrypt's hash of the raw public parameters; addresses a key inside the agent | Upper-case hex, byte-identical to `gpg --with-keygrip` |

## Capabilities

Keyguard reads the OpenPGP key flags of the primary key and every subkey. An
item shows what the certificate as a whole can do.

| Capability | Meaning |
| --- | --- |
| **Sign** | The key can create signatures |
| **Encrypt/decrypt** | The key can be an encryption recipient |

## Key details

| Data | What Keyguard reads |
| --- | --- |
| Primary key and subkeys | Fingerprint, keygrip, key ID, algorithm, bit strength, capabilities, revocation state and expiration date |
| User IDs | The `Name (comment) <email>` form. Keyguard shows the email addresses, and also reads bare-email user IDs. |
| Dates | Creation and expiration, from the key's self-signature |
| Revocation | Revoked keys and subkeys are marked as revoked |

## Edits

These edits work with v4 and v6 certificates. They're on the item's edit
screen. Open **User IDs** to manage identities. **Change GPG key expiry** is also in the item's menu.

| Edit | Rule |
| --- | --- |
| **Change GPG key expiry** | Needs the private key |
| **Add User ID** | Needs the private key. Keeps the current primary identity. |
| **Make primary** | Selects the identity displayed by default. You can change it later. Needs the private key. |
| **Replace…** | Adds the replacement and revokes the selected identity. Needs the private key. |
| **Revoke…** | Needs the private key. A v4 certificate must keep an active user ID; a v6 certificate can revoke its last one. |

Select **Name and email** or **Exact User ID** to choose the input format.
Revoked identities remain under **Identity history**.
If a signature's timestamp blocks an edit, Keyguard shows when to retry. Check your devices' clocks, then select **Retry** after that time.

After an edit, save the item, then upload or redistribute its public key. Cancelling the item editor discards the changes.

## Algorithms

Keyguard recognizes these public-key algorithms by their OpenPGP algorithm IDs.

| Algorithm | Notes |
| --- | --- |
| RSA | Signing and encryption |
| DSA | Legacy signing |
| ElGamal | Legacy encryption |
| ECDSA | NIST-curve signing |
| EdDSA, Ed25519 | Edwards-curve signing (legacy ID 22 and RFC 9580 native ID 27) |
| ECDH | Elliptic-curve encryption |
| X25519, X448 | RFC 9580 native encryption |
| Ed448 | RFC 9580 native signing |

Keyguard shows any other algorithm generically.

## Import

The add-item flow accepts ASCII-armored and binary keys. To create a new key
instead, use the [Generator](/docs/generator/).

| Input | Result |
| --- | --- |
| Public key | Stored as a certificate |
| Private key | Stored with its certificate. For a passphrase-protected key, Keyguard asks for the passphrase and reports a wrong one. |
| The certificate already in the item | Merged. Existing secret material, certifications and revocations stay. |
| A different certificate, or malformed data | Rejected. The item doesn't change. |
| More than one key in a file | Rejected. Import the keys one at a time. |
| Empty input or an unsupported format | Rejected, with a separate error for each |

## Export and copy

| Action | Result |
| --- | --- |
| Export the public key | A `.public.asc` file |
| Export the private key | A `.private.asc` file |
| Export both | A `.zip` with both files |
| **Copy public key** | The armored public key |
| **Copy fingerprint** | The fingerprint |
| **Copy unencrypted private key** | The armored private key, without a passphrase |

## Tools

To encrypt, decrypt, sign or verify with these keys inside Keyguard, see
[GPG tools](/docs/gpg-tools/). To use them from GnuPG or Android apps, see
[GPG agent](/docs/gpg-agent/).

## Keyservers

Keyguard can look up and publish public keys on a keyserver. **Keyserver** and
**Keyserver protocol** in **Settings > Developer > GPG agent** choose the
server.

| Protocol | Default or suggested server | Search by |
| --- | --- | --- |
| VKS (verifying keyserver, keys.openpgp.org API) | `https://keys.openpgp.org` | Fingerprint, key ID or email |
| HKP (HTTP Keyserver Protocol) | `https://keyserver.ubuntu.com` | Fingerprint, key ID, email or free text |

Lookups accept v4 and v6 fingerprints. The keyserver must support the
certificate's version to store and return it.

keys.openpgp.org serves keys by fingerprint, and shares an email identity only
after its owner confirms it. VKS has no free-text search, so Keyguard sends
free-text queries to `https://keyserver.ubuntu.com` over HKP. VKS rate-limits
email lookups.

| Action | Where | What it does |
| --- | --- | --- |
| **GPG keyserver search** | **Settings > Developer > GPG agent** | Finds a key on the keyserver, so you can import it |
| **Upload GPG public key** | Item menu | Publishes a public key. On a VKS keyserver, you pick the email addresses that get a verification email; the key becomes searchable by each one after it's confirmed. |
| **Verify GPG public key** | Item menu | Records whether a stored key is found and verified, found but unverified, not found, or revoked |
| **Refresh GPG public key** | Item menu | Updates the item's public key, metadata and keyserver status from the keyserver |
| **Auto-refresh public keys** | **Settings > Developer > GPG agent** | At the interval you set, updates each GPG key item that holds only a public key: its key, metadata and keyserver status. Runs while the app is active and the vault is unlocked. |
