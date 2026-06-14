---
title: Multiple accounts
description: Connect several Bitwarden and KeePass vaults side by side — one merged view, with per-account identity and control.
category: accounts
order: 0
---

Keyguard can hold several vaults at once: **Bitwarden** accounts — official
cloud, self-hosted, even on different servers — and **KeePass** databases,
all signed in side by side.

## One view, many accounts

The main vault merges items from **all accounts into a single list**, and
[search](/docs/search/) works across all of them at once — no switching
back and forth to find something. When you do want a narrower view, filter
by account (or folder, type, and so on), or open the account itself to see
only its items. Multi-select and batch actions work in the merged view too.

You can [hide an account](#hiding-an-account), so it is only browsable from the settings.

## Telling accounts apart

Each account has its own identity, editable from the account's screen:

- **Name** — give the account a label that means something to you;
- **Accent color** — a per-account color that helps tell its items apart.

## Hiding an account

Every account has a **Hide items** toggle on its screen. Turn it on and the
account's items disappear from the main screens of the app — the merged
vault list and the views built on it — while the account itself stays
signed in, keeps syncing, and remains visible in the account list. Open the
account directly to browse its items, or flip the toggle off to reveal them
everywhere again.

It's the right tool for vaults you need rarely but don't want to sign out
of — an old archive, or a family member's vault you help manage — keeping
your day-to-day lists focused without giving up access.

## Copying items between accounts

An item can be **copied to another account** from its actions — pick the
destination account (and, for Bitwarden, the organization or folder it
should land in). The result is an independent copy in the destination
vault; the original stays where it was.

## On the watch

The [Wear OS app](/docs/wear-os/) has its own vault, set up when you pair
the watch: you choose a provider — Bitwarden or KeePass — and complete a fresh
sign-in through your phone. The watch then carries that single account on its
own. (For KeePass, the watch receives a one-time copy of the database; it
can't sync the database afterwards.)
