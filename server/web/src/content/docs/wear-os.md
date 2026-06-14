---
title: Keyguard on Wear OS
description: Put your vault on your wrist — setup, what works on the watch, and current limitations.
category: guides
order: 8
---

Keyguard ships a Wear OS app, so your vault — and your one-time passwords —
are available on your wrist without taking the phone out of your pocket.

## Setup

Install Keyguard on both the **phone** and the **watch** (it comes with the
same Google Play listing). The watch does not sign in to your server on its
own; instead it asks the paired phone for access. Start the flow on the
watch, then **authorize it from your phone**, choosing which account to
share. From then on the watch keeps its own synced copy of the vault.

> For **KeePass** accounts the watch receives a one-time copy of the
> database; picking a local database from the watch itself is not supported.

## What you can do on the watch

- browse the **vault** and your **favorites**;
- open an item and read its fields, including the current **one-time
  password**;
- view **sends**;
- use the **generator**;
- serve credentials to apps running on the watch through the credential
  provider.

## Good to know

The watch app is a viewer-style companion — editing items still happens on
the phone or desktop. Two-factor methods that need a browser or hardware key
(WebAuthn, Duo, YubiKey) are likewise unavailable on the watch, which is one
more reason authorization runs through the phone.
