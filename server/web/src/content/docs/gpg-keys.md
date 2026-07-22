---
title: GPG keys
description: The GPG key item type and its supported algorithms, identifiers, generating and importing keys, the built-in OpenPGP tools, and keyserver lookup.
category: reference
order: 10
---

Keyguard stores OpenPGP keys in a dedicated **GPG key** item type and can use
them to sign, verify, encrypt, and decrypt. This page is a technical reference
for what Keyguard actually parses, stores, and supports. For the day-to-day
setup see the [GPG agent setup](/docs/gpg-agent/) guide.

OpenPGP (the standard behind GnuPG/PGP, defined by RFC 4880 and the newer RFC
9580) describes a **certificate** — colloquially "a public key" — as a primary
key plus zero or more subkeys, one or more user IDs, and metadata bound
together by self-signatures.

A GPG key item itself stores three things:

- the **armored public key**;
- the **armored private key**, if you provided one;
- the **fingerprint** of the primary key.

> **Private keys are stored unencrypted** — when you import a passphrase-protected
> private key, Keyguard asks for the passphrase once, then re-encodes the key
> **without** it. The vault's own encryption is what protects it from then on.

For compatibility with Bitwarden's fixed data model — see 
[Item types & extras](/docs/item-extras/#gpg-keys) for the
exact convention.

## Identifiers

Keyguard derives and displays three identifiers per key, all matching what
GnuPG shows:

| Identifier | What it is | Format in Keyguard |
| --- | --- | --- |
| **Fingerprint** | Hash over the public key material and creation time; identifies the whole certificate | Upper-case hex, grouped in fours |
| **Key ID** | The low 64 bits of the fingerprint (the "long" key ID) | 16 upper-case hex digits |
| **Keygrip** | libgcrypt's hash of the raw public parameters; used to address a key inside the agent | Upper-case hex, byte-identical to `gpg --with-keygrip` |

## Capabilities

Each key and subkey is inspected for its OpenPGP key flags and shown as one or
both of:

- **Sign** — the key can create signatures;
- **Encrypt** — the key can be an encryption recipient.

Capabilities are aggregated across the primary key and every subkey, so an item
reports what the certificate as a whole can do.

## Subkeys, user IDs, and validity

For the primary key and every subkey, Keyguard extracts the fingerprint,
keygrip, key ID, algorithm, bit strength, sign/encrypt capabilities, revocation
state, and expiration date.

**User IDs** are parsed in the standard `Name (comment) <email>` form; the email
addresses are pulled out for display, with a fallback for bare-email user IDs.
**Creation** and **expiration** dates are read from the key's self-signature,
and **revoked** keys and subkeys are marked as such.

## Algorithms

Keyguard recognises the following public-key algorithms when parsing a key
(by their OpenPGP algorithm IDs, so they survive across library versions):

| Algorithm | Notes |
| --- | --- |
| RSA | Sign and/or encrypt |
| DSA | Legacy signing |
| ElGamal | Legacy encryption |
| ECDSA | NIST-curve signing |
| EdDSA / Ed25519 | Edwards-curve signing (legacy ID 22 and RFC 9580 native ID 27) |
| ECDH | Elliptic-curve encryption |
| X25519 / X448 | RFC 9580 native encryption |
| Ed448 | RFC 9580 native signing |

Any other algorithm is shown generically. Note that being able to *parse* a key
does not mean every operation supports it — see the limits under
[GPG agent](#operations-with-the-gpg-agent) below.

## Generating a key

The [generator](/docs/generator/) creates a new GPG key in one of two
profiles — modern **Ed25519 + X25519**, or **RSA** — see the generator's
[GPG keys section](/docs/generator/#gpg-keys) for what each profile
produces and the current limitations.

## Importing a key

The add-item flow accepts existing keys in either **ASCII-armored** or **binary**
form, and both **public** and **private** key material:

- A **public key** is parsed and stored as-is.
- A **private key** is parsed; if it is passphrase-protected, Keyguard prompts
  for the passphrase, then stores it unencrypted (see the note above). A wrong
  passphrase is reported as such.

Malformed input, empty input, and unsupported formats are reported distinctly.

## Exporting and copying

From a GPG key item you can:

- **export** the public key (`.public.asc`), the private key (`.private.asc`),
  or both together in a `.zip`;
- **copy** the public key, the fingerprint, or the unencrypted private key.

## The GPG tools

Keyguard includes standalone OpenPGP tools that operate on **text** or **files**
using the keys in your vault:

| Operation | Modes |
| --- | --- |
| **Sign** | Cleartext (inline) or detached signature |
| **Verify** | Inline or detached signature |
| **Encrypt** | To one or more recipient public keys |
| **Decrypt** | With a private key from the vault |

Signatures use SHA-256 over canonical text. Verification reports whether a
signature is **valid**, **invalid**, or **missing the public key**, and adds
warnings when the signing key is **revoked** or **expired**, or when the
**signature itself has expired**.

## Operations with the GPG agent

On desktop Linux, macOS, and Windows, Keyguard can act as a drop-in
**gpg-agent** so that a local `gpg` (for example, when signing Git commits) uses
keys from your vault. See the [GPG agent setup](/docs/gpg-agent/) guide for
configuration.

## Keyservers

Keyguard can look up and publish public keys on a keyserver. Two protocols are
supported:

| Protocol | Default / suggested server | Search by |
| --- | --- | --- |
| **VKS** (verifying keyserver, keys.openpgp.org API) | `https://keys.openpgp.org` | Fingerprint, key ID, or email |
| **HKP** (HTTP Keyserver Protocol) | `https://keyserver.ubuntu.com` | Fingerprint, key ID, email, or free text |

keys.openpgp.org is a **verifying** keyserver: it serves key material freely by
fingerprint, but only distributes the identity (email) information after the
address owner confirms it, and it does not do free-text search — Keyguard picks
the right lookup automatically and can fall back to HKP for free-text queries.
Email lookups against VKS are rate-limited.

You can:

- **search** a keyserver and import a result;
- **upload** a public key to publish it;
- **verify** a stored public key against the keyserver, recording whether it is
  *found & verified*, *found but unverified*, *not found*, or *revoked*.

A background worker can **auto-refresh** the published state of your keys on a
schedule you set.

## Related

- [GPG agent setup](/docs/gpg-agent/) — serve vault keys to a local `gpg`, and sign Git commits.
- [Generator](/docs/generator/) — create new GPG keys.
- [Watchtower](/docs/watchtower/) — audit keys for weaknesses.
- [Item types & extras](/docs/item-extras/) — other item conventions.
