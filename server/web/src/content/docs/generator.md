---
title: Generator
description: "Generate passwords, passphrases, PIN codes, usernames, email aliases, and SSH and GPG keys."
section: vault
type: feature
order: 50
platforms: [android, wear-os, linux, macos, windows]
related: [email-relays, ssh-agent, gpg-keys]
---

The generator creates passwords, passphrases, PIN codes, usernames, email
aliases, and SSH and GPG keys. Open it from the **Generator** screen, or from
an item's field to fill that field.

## Strength

**Random symbols** and **Passphrase** values get a rating from **Weak** to
**Very strong**. It estimates how long an attacker needs to guess the value.

Entropy, in bits, measures how unpredictable a value is. Each bit doubles the
number of guesses:

| Value | Entropy |
| --- | --- |
| 16 random characters with the default options, from 68 possible characters | About 97 bits |
| 5 words from the built-in list, the default | About 65 bits |
| 6 words from the built-in list | About 78 bits |
| 1 word from a 1,000-word list | About 10 bits |

For important secrets, use 6 or more words, as the
[EFF recommends](https://www.eff.org/deeplinks/2016/07/new-wordlists-random-passphrases).
[NIST's guidelines](https://pages.nist.gov/800-63-4/sp800-63b.html) require at
least 15 characters for a password used as the only authentication factor.

## Generate a value

1. Open **Generator**.
2. Select a type, such as **Random symbols** or **Passphrase**.
3. Set the type's options. Keyguard keeps them for next time.
4. Copy the value, or select **Regenerate** for a new one.

SSH key, GPG key and email forwarder types create a value only when you select
**Generate**.

Opened from an item's field, the generator lists only the types that fit the
field, and keeps separate options for that field. Select **Use** to fill the
field with the value.

## Custom wordlists

The **Passphrase** and **Username** types can draw words from your own
wordlist instead of the built-in one.

1. On the **Generator** screen, open the menu and select **Wordlists**.
2. Add a list with **Load from a file** or **Load from a URL**.
3. In the **Passphrase** or **Username** type, set **Wordlist** to your list.

:::note
The strength rating checks passphrases against the built-in list only, so it
misjudges passphrases from a custom wordlist.
:::

### File format

A plain text file with one word per line. Keyguard skips empty lines and lines
that start with `#`, `;`, `-` or `/`. Every other line is a word, kept as is.
On desktop, the file picker shows `.txt` and `.wordlist` files.

```text
# my wordlist: this line is skipped
correct
horse
battery
staple
```

### Recommended wordlists

These lists load as is:

- [Orchard Street Wordlists](https://github.com/sts10/orchard-street-wordlists)
  by sts10: audited lists without prefix collisions or awkward words.
- [18,325 words based on Ngram frequency data](https://github.com/sts10/generated-wordlists/blob/main/lists/1password-replacement/1password-replacement.txt),
  also by sts10: a large list for more entropy per word.

The built-in list is the
[EFF long wordlist](https://www.eff.org/files/2016/07/18/eff_large_wordlist.txt)
of 7,776 words. EFF's lists, including its shorter ones, put dice numbers
before each word. Remove the numbers before you load one.

## Find a generated value

Keyguard keeps every value the generator shows for more than half a second,
even if you never use it. That includes values from an item's field. The
history stays in the encrypted vault on this device and doesn't sync.

1. On the **Generator** screen, select the history (clock) icon.
2. Select a value, then an action such as **Copy password**.

To delete values, select them and choose **Remove from the history**. To delete
them all, open the menu and select **Clear history**. **Generator history**
lists the newest 1,000 values. You can't turn the history off.

## Add a Quick Settings tile

On Android, the generator has two Quick Settings tiles. Add one when you edit
your Quick Settings panel.

| Tile | Opens |
| --- | --- |
| **Generator (tab)** | The **Generator** screen. A locked vault asks for the app password first. |
| **Generator (standalone)** | A separate generator that needs no app password. It keeps its own options and creates passwords, passphrases, PIN codes, usernames and email aliases. It has no history, email forwarders, custom wordlists or keys. |

A third tile, **Keyguard**, opens the app.

## Types

Each type keeps its own options. The tables list the defaults.

### Passwords

Random passwords use the **Random symbols** type.

| Option | Default |
| --- | --- |
| **Length** | 16, from 4 to 128 |
| **Uppercase letters**, **Lowercase letters**, **Digits**, **Symbols** | All on. Each has a minimum count, 1 by default. |
| **Exclude similar symbols** | On. Leaves out `L`, `O`, `i`, `l`, `o`, `0`, `1` and the vertical bar. |
| **Exclude ambiguous symbols** | On. Leaves out brackets, quotes, slashes and similar punctuation. |

### Passphrases

The **Passphrase** type: random words, easier to remember and type than
random characters of the same strength.

| Option | Default |
| --- | --- |
| **Length** | 5 words, from 1 to 20 |
| **Delimiter** | `-` |
| **Capitalize** | Off |
| **Number** | Off. When on, adds digits to one word. |
| **Wordlist** | The built-in EFF long wordlist, or one of your [Custom wordlists](#custom-wordlists) |

### PIN codes

The **PIN** type: digits only. Its only option is the length: 4 by default,
from 3 to 9.

### Usernames

The **Username** type: random words.

| Option | Default |
| --- | --- |
| **Length** | 2 words, from 1 to 10 |
| **Delimiter** | None |
| **Capitalize** | On |
| **Number** | On |
| **Custom word** | Empty. A word of your own that replaces one random word. |
| **Wordlist** | The built-in EFF long wordlist, or one of your [Custom wordlists](#custom-wordlists) |

### Email aliases

These types build an alias on an inbox you already own. The random part is 5
lowercase letters and digits by default, from 3 to 10.

| Type | Example | Works when |
| --- | --- | --- |
| **Plus addressing email** | `you+f3k9x@example.com` | Your provider delivers `+` addresses to your inbox |
| **Catch-all email** | `f3k9x@example.com` | You own the domain and its inbox accepts any address |
| **Subdomain addressing email** | `you@f3k9x.example.com` | Your provider routes subdomains of your domain to your inbox |

Keyguard fills in the address or domain from your account's email. **Catch-all
email** and **Subdomain addressing email** leave it empty for public providers,
such as gmail.com.

Each service you connect in [Email forwarders](/docs/email-relays/) is a
separate type, under the name you gave it.

### SSH keys

The **SSH Key** type creates a key pair for an SSH key item, ready for the
[SSH agent](/docs/ssh-agent/).

| Key type | Length |
| --- | --- |
| **Ed25519** (default) | Fixed |
| **RSA** | 1024, 2048, 3072 or 4096 bits. Default 4096. |

### GPG keys

The **GPG Key** type creates an OpenPGP key for a GPG key item.

| Option | Values |
| --- | --- |
| **Key type** | **Ed25519 + X25519** (default): an Ed25519 certification key, an Ed25519 signing subkey and an X25519 encryption subkey. **RSA**: an RSA certification key with separate RSA signing and encryption subkeys. |
| **Length** | RSA only: 3072 or 4096 bits. Default 4096. |
| **Certificate version** | **v4** (default) for GnuPG and older clients. **v6** for clients that support [RFC 9580](https://www.rfc-editor.org/rfc/rfc9580.html). |
| **Expiry** | **One year**, **Two years**, **Five years** (default), **Never** or **Custom date** |
| **Name**, **Email** | The key's user ID. **Name** is required. **Email** defaults to your account's email address. |
