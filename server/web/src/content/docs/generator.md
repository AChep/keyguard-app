---
title: Generator & wordlists
description: Generate passwords, passphrases, usernames, email aliases, and SSH keys — with your own wordlists.
category: guides
order: 7
---

The generator creates fresh credentials whenever you need them: random
passwords, memorable passphrases, usernames, email aliases, and SSH keys. You
control the length, character sets, and structure, so each result fits the
service you are signing up for.

## Email aliases

Keyguard generates masked email addresses through an email forwarder service
you link with an API token: **SimpleLogin**, **addy.io (AnonAddy)**,
**DuckDuckGo**, **Firefox Relay**, **Forward Email**, **Cloudflare**, or
**Fastmail**. Hand out a unique address per signup and keep your real inbox
private.

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
