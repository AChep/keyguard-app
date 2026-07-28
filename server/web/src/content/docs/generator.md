---
title: Generator
description: Generate passwords, passphrases, PIN codes, usernames, email aliases, SSH and GPG keys — with your own wordlists.
category: guides
order: 8
---

The generator creates fresh credentials whenever you need them. Every mode
lives on the same **Generator** screen — pick the mode, tune its options, and
copy the result — and the same generator is one tap away when you edit an
item's field.

## Passwords

Random character passwords. You control the **length** and which character
classes are used — uppercase, lowercase, digits, and symbols, each with a
**minimum count** — and you can exclude similar-looking characters and
ambiguous symbols for values you may have to read or type by hand.

Strength is measured in **bits of entropy**: each bit doubles the number of
guesses required. With all four character classes enabled, each character adds
about **6.5 bits** (about **6 bits** without symbols), so the default
16-character password has roughly **104 bits**. For comparison,
[NIST's guidelines](https://pages.nist.gov/800-63-4/sp800-63b.html) require at
least **15 characters** for a password used as the only authentication factor.

## Passphrases

A sequence of random words — `correct-horse-battery-staple` — much easier to
remember and type than a character soup of the same strength. You choose the
**number of words**, the **delimiter**, **capitalization**, and whether to
include a **number**. Words are drawn from the built-in dictionary or from
your own [wordlists](#custom-wordlists).

Passphrase strength counts in words, not characters: each word drawn from
the built-in dictionary — the
[EFF long wordlist](https://www.eff.org/deeplinks/2016/07/new-wordlists-random-passphrases),
7,776 words — adds about **12.9 bits of entropy**, roughly what two random
characters give you. That's the trade-off behind the memorability: matching
a 16-character random password takes eight words. Five words (the default)
come to ≈64 bits, fine for everyday accounts; use **six or more (≈77
bits)** — the EFF's own recommendation — for anything that guards important
secrets.

A [custom wordlist](#custom-wordlists) changes the math: a word is worth
more bits the longer the list is. From a 1,000-word list each word adds
only ~10 bits — six words fall from 77 to 60 bits — so add a word or two
when your list is small. This is also why the in-app strength meter
misjudges custom-wordlist passphrases: it doesn't know your list's size.

## PIN codes

Digit-only codes for the places that accept nothing else — the only option is
the **length**.

## Usernames

Word-based usernames: random words with optional **capitalization**, an
included **number**, and a **custom word** of your own mixed in. Like
passphrases, usernames can draw from your own
[wordlists](#custom-wordlists).

## Email aliases

For signing up without handing out your real address, the generator can build
aliases on top of an inbox you already own — each scheme lets you set the
length of the random part:

- **Plus addressing** — `you+f3k9x@example.com`; delivered to
  `you@example.com` by every provider that supports the `+` convention;
- **Catch-all** — `f3k9x@example.com`; for when you control the domain and
  run a catch-all inbox on it;
- **Subdomain addressing** — `you@f3k9x.example.com`; for providers that
  route wildcard subdomains of your own domain.

## Email relays

An **email forwarder service** creates a separate address that forwards mail to
your real inbox, keeping your real address out of the signup. Connect a service
once, and the generator can request a fresh alias on demand. See
[Email relays](/docs/email-relays/) for the supported services and how to link
one.

## SSH keys

Generates an SSH key pair — **Ed25519** (the default) or **RSA** (4096 bits
by default) — stored as an SSH key item, ready to be served by the
[SSH agent](/docs/ssh-agent/).

## GPG keys

Generates an OpenPGP key, stored as a [GPG key](/docs/gpg-keys/) item, in one
of two profiles:

- **Modern** — an **Ed25519** primary key with a separate **Ed25519** signing
  subkey and an **X25519** (Curve25519) encryption subkey. These are built with
  the widely-compatible v4 EdDSA/ECDH encodings so that GnuPG and other clients
  can import them.
- **RSA** — a single RSA key, **3072** or **4096** bits (4096 by default).

Generation does not currently set an expiration date or a passphrase, and does
not offer DSA, ElGamal, NIST, or custom-curve keys.

----

## Custom wordlists

Passphrases and usernames can draw on your own wordlists instead of the
built-in dictionary. Manage them from the **Generator** screen under
**Wordlists** — a wordlist can be loaded from a file or from a URL.

### File format

The supported file extensions are `.txt` and `.wordlist`. The file should be a
plain text file with each word on its own line; lines that are empty or start
with `#`, `;`, `-`, or `/` are ignored.

```text
# my wordlist — this line is ignored
correct
horse
battery
staple
```

> Note: Keyguard will incorrectly calculate the passphrase's strength when
> using custom wordlists.

### Honorable wordlists

All of these are plain text files with one word per line, ready to load
into Keyguard:

- [EFF's Long Wordlist](https://www.eff.org/deeplinks/2016/07/new-wordlists-random-passphrases)
  — 7,776 memorable, distinct words curated by the Electronic Frontier
  Foundation; the modern de-facto standard for random passphrases
  ([direct download](https://www.eff.org/files/2016/07/18/eff_large_wordlist.txt)).
- **EFF's Short Wordlists** — from the same article: shorter, even more
  memorable words, including a list whose words all have unique
  three-letter prefixes.
- [Orchard Street Wordlists](https://github.com/sts10/orchard-street-wordlists)
  — modern, rigorously audited lists by [sts10](https://github.com/sts10),
  free of prefix collisions and awkward words.
- [18,325 words based on Ngram frequency data](https://github.com/sts10/generated-wordlists/blob/main/lists/1password-replacement/1password-replacement.txt),
  also by sts10 — a large list for maximum entropy per word.
