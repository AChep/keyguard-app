---
title: Passkeys
description: "How passkeys work, why they resist phishing, and how Keyguard stores and signs with them."
section: autofill
type: concept
order: 20
platforms: [android, wear-os, linux, macos, windows]
related: [passkey-provider, credential-exchange, troubleshoot-autofill]
---

A passkey signs you in to a website or app without a password. It's a
discoverable credential from [Web Authentication](https://www.w3.org/TR/webauthn-2/)
(WebAuthn), the W3C standard built on FIDO2: the authenticator finds it for the
site, so you don't enter a username.

## How passkeys work

Each passkey is a key pair created for one site, called the relying party. The
site keeps the public key. The private key stays with the authenticator, here
your Keyguard vault.

At registration, the site sends a random challenge and its relying party ID
(RP ID), which comes from its domain. The authenticator creates a key pair
scoped to that RP ID. The site stores the public key and a credential ID.

At sign-in, the site sends a fresh challenge. The authenticator finds the
passkey for the RP ID, confirms that you're present, and signs the challenge
together with the page's origin. The site checks the signature with the stored
public key.

## Attack resistance

| Attack | Why a passkey resists it |
| --- | --- |
| Server breach | The site stores only public keys, which can't sign in. |
| Phishing | The signature covers the page's origin. A look-alike domain has a different RP ID, so the authenticator doesn't offer the real site's passkey there. |
| Credential stuffing | Each site gets its own key pair. Nothing is reused. |
| Replay | Each sign-in signs a fresh challenge, so a captured response fails the next time. |

## What Keyguard stores

For each passkey, Keyguard stores the private key, credential ID, RP ID, user
handle, username, display name, creation date and signature counter. The site
keeps only the public key and the credential ID.

A passkey is part of a login item. It's encrypted with the rest of your vault
and syncs with that item's account. Bitwarden accounts store it in Bitwarden's
passkey format, and KeePass databases in the fields that KeePassXC uses.

## How Keyguard handles passkeys

- **Algorithm.** Keyguard creates and signs only with ES256 keys (ECDSA on the
  P-256 curve). If a site doesn't accept ES256, Keyguard shows "This site does
  not support the passkey algorithm provided by Keyguard."
- **Attestation.** Keyguard uses "none" attestation: it sends no attestation
  statement.
- **Signature counter.** WebAuthn defines a
  [signature counter](https://www.w3.org/TR/webauthn-2/#sctn-sign-counter) to
  reveal cloned authenticators. Keyguard creates passkeys with a counter of
  zero and never increments it. Copies of a vault on several devices can't keep
  one counter in step.
- **Backup eligibility.** Keyguard marks its passkeys as backup eligible, which
  tells the site that a passkey can sync between devices.
- **Use in apps and browsers.** Keyguard can answer passkey requests from the
  system. See [Use Keyguard as a passkey provider](/docs/passkey-provider/).
- **Transfer.** [Export your vault](/docs/export/) and
  [Credential Exchange](/docs/credential-exchange/) both carry passkeys with
  their items.
- **Watchtower.** The **Available Passkeys** check in
  [Watchtower](/docs/watchtower/) lists logins for sites that support passkeys
  but have none.
