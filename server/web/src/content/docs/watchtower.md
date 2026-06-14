---
title: Watchtower
description: The thirteen security checks Keyguard runs on your vault, how each one works, and how the checks respect your privacy.
category: guides
order: 4
---

Watchtower continuously analyzes your vault and surfaces problems before
they bite. It's free, it runs on every account type, and it can notify you
when **new alerts** appear.

## The checks at a glance

| Check | What it flags |
| :---- | :------------- |
| Pwned passwords | Passwords exposed in known data breaches — change these immediately |
| Vulnerable accounts | Sites breached **since you last changed** that password |
| Reused passwords | The same password used on multiple sites |
| Weak passwords | Passwords that fail strength requirements |
| Weak SSH keys | RSA SSH keys shorter than 2048 bits |
| Inactive two-factor authentication | Sites that offer 2FA you haven't enabled |
| Available passkeys | Sites that support passkeys you haven't set up |
| Unsecure websites | Item URLs still using `http://` |
| Duplicate items | Items that appear to be copies of each other |
| Duplicate URIs | URIs on one item that cover the same site after [match detection](/docs/url-matching/) |
| Broad URI match detection | URIs whose match rule is looser than it should be |
| Incomplete items | Missing data — logins without usernames, identities without names |
| Expiring items | Items that have expired or expire soon |

## The checks in detail

### Pwned passwords

Every password is checked against
[**Have I Been Pwned**](https://haveibeenpwned.com/), the public corpus
of real-world breach data. Keyguard hashes the password with SHA-1 and
sends only the **first five characters of the hash** to the service; the
actual comparison happens on your device (see
[privacy](#how-the-checks-respect-your-privacy)). A match means the exact
password circulates in attackers' cracking lists — no matter how strong it
looks.

> *Example:* `Summer2019!` passes a complexity rule, yet appears in known
> breaches thousands of times. Watchtower flags it; replace it with a
> generated password.

### Vulnerable accounts

Keyguard compares your logins' websites against HIBP's public **breach
catalog** and flags items whose site suffered a breach **since you last
changed that password** — the signal that your credentials may be part of
the spill.

> *Example:* a forum you use was breached this January, and your password
> there dates from two years earlier — rotate it, and anywhere it was
> reused.

### Reused passwords

Logins are grouped by their exact password; any password shared by two or
more items is flagged. One breached site shouldn't unlock the others.

> *Example:* the same password protects your email and a shopping site —
> when the shop leaks it, the email follows.

### Weak passwords & weak SSH keys

Password strength is estimated with **zxcvbn**, which models realistic
guessing attacks instead of just counting character classes; passwords
scoring *weak* are flagged. SSH keys are checked for weak key length — RSA
keys under 2048 bits are flagged (Ed25519 keys are not). The weak-password
check is the one alert that **cannot be ignored per item**.

> *Example:* `dragon99` rates as guessable in minutes despite meeting an
> "8 characters with digits" policy.

### Inactive two-factor authentication

Your login URLs are matched — by domain, honoring
[equivalent domains](/docs/url-matching/#equivalent-domains) — against a
**directory bundled with the app** of sites known to support an
authenticator-app (TOTP) second factor (powered by
[2factorauth](https://2fa.directory/)). A
match is flagged unless the
item already carries a [one-time password](/docs/totp/), or signs in with a
passkey and no password. Each finding links to the site's own 2FA
documentation.

> *Example:* your domain registrar supports authenticator apps, but your
> login has no authenticator key — enable 2FA there and scan the QR code
> into the item.

### Available passkeys

The same mechanism, using the bundled
[**passkeys directory**](https://passkeys.directory/): sites that
support passkey sign-in are flagged until the item holds a passkey.

> *Example:* your cloud storage now offers passkeys — create one on your
> next sign-in and skip the password (and the phishing risk) entirely.

### Unsecure websites

Flags item URLs that still use the `http://` protocol (`localhost` and
private-network addresses like `192.168.x.x` are ignored), which sends
everything — including your password — unencrypted. The fix is usually as
simple as editing the URL to `https://`.

### Duplicate items

Items are compared for similarity and grouped as likely duplicates, with a
**configurable tolerance** that ranges from near-exact matches to fuzzier
grouping. Clean a group up by merging it into a single item with
[multi-select](/docs/items/#organizing).

> *Example:* two `GitHub` logins with the same username, one with an older
> password — merge them and keep the history.

### Duplicate URIs

Flags an item whose URIs end up covering the **same website** once
[match detection](/docs/url-matching/) is applied — say, `example.com` and
`www.example.com/login` both matching by base domain. Removing the
redundant URI keeps autofill suggestions tidy.

### Broad URI match detection

The reverse problem: URIs whose match rule is **looser than it needs to
be**, making the item show up on sites it isn't meant for. The fix is
switching that URI to a narrower [match mode](/docs/url-matching/) — host,
starts-with, or exact.

### Incomplete items

Heuristics for entries you probably meant to finish: a blank or
placeholder name (`login`, `todo`, `entry`, …) or a login without a
username. Items that look deliberate are left alone — API-token-style
items, passkey-only logins, and items that exist to carry custom fields or
attachments.

> *Example:* an item named `email` holding a password but no username or
> website.

### Expiring items

Flags **payment cards** that have expired or expire within the next
**three months**, so the replacement is in your vault before the old card
declines. The check refreshes daily.

## Acting on alerts

Each alert links to the affected items, so fixing is usually a short loop:
open the item, change the password (the [generator](/docs/generator/) is one
tap away), enable 2FA on the site, or merge duplicates.

Not every alert applies to every item: a shared throwaway account may
legitimately reuse a password. You can **ignore specific alert types per
item**, and the alert moves to the ignored list instead of nagging you. The
weak-password check is the one exception — it cannot be silenced per item.

## How the checks respect your privacy

- **Pwned passwords** are checked against Have I Been Pwned using the
  **k-anonymity** range API: only the first five characters of the
  password's SHA-1 hash ever leave your device, and the comparison happens
  locally. Your passwords — even hashed — are never sent anywhere.
- **Vulnerable accounts** are found by comparing your items against HIBP's
  public breach catalog.
- **Two-factor** and **passkey** availability come from directories bundled
  with the app (powered by [2factorauth](https://2fa.directory/) and the
  [passkeys directory](https://passkeys.directory/)), so those checks
  involve no network requests at all.
- Everything else — strength, reuse, duplicates, URLs, completeness — is
  computed entirely on your device.
