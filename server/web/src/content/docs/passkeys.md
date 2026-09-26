---
title: Passkeys
description: What passkeys are, how WebAuthn registration and sign-in work, and why passkeys resist phishing and breaches that defeat passwords.
category: reference
order: 7
---

A **passkey** lets you sign in to a website or app without a password. Under the
hood it is a [public key credential](https://www.w3.org/TR/webauthn-2/#sctn-intro)
defined by **WebAuthn** — the W3C
[Web Authentication](https://www.w3.org/TR/webauthn-2/) standard, built on FIDO2.
"Passkey" is just the consumer-friendly name for a *discoverable* WebAuthn
credential: one your authenticator can find and offer on its own, so you don't
even type a username.

A passkey is an asymmetric **cryptographic key pair**. The private key remains
in your authenticator (your Keyguard vault); the website receives only the
public half.

## Asymmetric key pairs

Each passkey is a
[credential key pair](https://www.w3.org/TR/webauthn-2/#sctn-terminology): a
**private key** that stays with the authenticator and a **public key** the
authenticator hands to the website. Authentication is a challenge the private key
signs and the public key verifies — the private key is never transmitted or
stored by the server.

## Creating a passkey (registration)

When you register, the website — the
[Relying Party](https://www.w3.org/TR/webauthn-2/#sctn-terminology) — runs the
[registration ceremony](https://www.w3.org/TR/webauthn-2/#sctn-registering-a-new-credential):

1. The site asks the browser to create a credential, passing a random
   **challenge** and its **RP ID** (an identifier derived from its own domain).
2. The authenticator generates a brand-new key pair **scoped to that RP ID**,
   keeps the private key, and returns the **public key** plus a **credential
   ID** (and, optionally, an [attestation](https://www.w3.org/TR/webauthn-2/#sctn-attestation)
   statement about itself).
3. The server stores the public key and credential ID against your account.

Because the key pair is generated per site, every passkey you create is unique —
sites share no credentials to leak or reuse.

## Signing in (authentication)

Later sign-ins run the
[authentication ceremony](https://www.w3.org/TR/webauthn-2/#sctn-verifying-assertion):

1. The site sends a fresh, single-use **challenge** and its RP ID.
2. The authenticator finds the credential scoped to that RP ID, confirms you are
   present (and, usually, verifies you — see below), and signs the
   [authenticator data](https://www.w3.org/TR/webauthn-2/#sctn-authenticator-data)
   together with a hash of the client data (which includes the challenge and the
   page's origin) using the **private key**.
3. The server verifies that signature with the **public key** it stored at
   registration. A valid signature proves you hold the private key — without it
   ever leaving your device.

## What's stored where

| Keyguard | The website's server |
| :-------------------------------------- | :------------------- |
| Private key                             | Public key           |
| Credential ID, RP ID, user handle       | Credential ID, link to your account |
|                                         | (Optional) attestation metadata |

The asymmetry is the whole point: the half that can *prove* identity stays with
you; the half the server keeps can only *verify*.

## Why passkeys beat passwords

### Nothing reusable to steal from the server

The Relying Party stores only public keys
([Security Considerations](https://www.w3.org/TR/webauthn-2/#sctn-security-considerations)).
A breach of its database leaks public keys, which are useless for signing in
because the attacker lacks the corresponding private keys.

### Phishing resistance

The browser binds each request to the page's real **origin**, and the
authenticator scopes each credential to an **RP ID** derived from that origin;
the signature then covers both. As the spec puts it, "the full origin of the
requester is included, and signed over … in all assertions produced by WebAuthn
credentials."

A look-alike phishing domain (`exarnple.com`) has a different origin and RP ID
than the real one. The authenticator will not surface the real site's passkey
there, and any assertion made would fail server verification.

### One unique key per site

Each registration creates a new key pair, so services share no credentials.
Password reuse and credential-stuffing attacks do not apply.

### Local user verification and presence

Using a passkey requires a
[test of user presence](https://www.w3.org/TR/webauthn-2/#sctn-authenticator-data)
(an authorization gesture) and usually **user verification** — a biometric or PIN
checked **locally** by the authenticator. Biometrics never leave the device, and
local verification can rate-limit guessing. Practically, signing in proves
*something you have* (the private key) and *something you are / know* (the
unlock), without sending either to the server.

### Replay and clone resistance

Every sign-in uses a fresh, single-use challenge, so a captured assertion can't
be replayed. The authenticator also keeps a per-credential
[signature counter](https://www.w3.org/TR/webauthn-2/#sctn-sign-counter) included
in each assertion; if a server ever sees it move backwards, that's a signal the
authenticator may have been **cloned**.

### Optional attestation

At registration an authenticator can present
[attestation](https://www.w3.org/TR/webauthn-2/#sctn-attestation) — a signed
statement about its make, model, and security properties — so a Relying Party
that needs to can judge where a credential came from.

## Passkeys in Keyguard

Keyguard stores passkeys as part of your vault items, **end-to-end encrypted**
like everything else. As *synced* (multi-device) passkeys, they sync across
your devices and are protected by your [backups](/docs/backups/).

- On **Android**, Keyguard registers as a system **credential provider**, so apps
  and browsers can create and use passkeys through it. See the
  [autofill & passkeys guide](/docs/autofill/) for setup.
- On **Wear OS**, you can sign in with passkeys already in your vault.
- You can **view** a passkey's details and **export** it to a file. On Android,
  passkeys can also move to or from another app through
  [credential exchange](/docs/credential-exchange/).
- [Watchtower](/docs/watchtower/) flags inactive passkeys and points out sites
  that support passkeys where you haven't created one yet.

## Further reading

- [Web Authentication: An API for accessing Public Key Credentials — Level 2](https://www.w3.org/TR/webauthn-2/)
  — the W3C Recommendation this page is based on.
- [Web Authentication — Level 3](https://www.w3.org/TR/webauthn-3/) — the
  evolving draft.
- [FIDO Alliance: Passkeys](https://fidoalliance.org/passkeys/) — background on
  the term and the industry effort behind it.
