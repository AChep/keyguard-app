---
title: URL matching
description: How Keyguard decides which items to suggest for a website or app — match detection modes and equivalent domains.
category: reference
order: 4
---

When you open a website or an Android app, Keyguard compares its URL against
the URLs of your login items to decide which ones to suggest for
[autofill](/docs/autofill/). If the wrong item shows up — or the right one
doesn't — the **match detection** mode is almost always the reason.

## Match detection modes

Every URL on a login item has its own match detection setting, found next to
the URL on the item's edit screen:

| Mode                   | Matches when…                                                       |
| :--------------------- | :------------------------------------------------------------------ |
| **Default**            | Uses the global default from *Settings → Autofill → Default URI match detection* |
| **Base domain**        | The top-level and second-level domain match — `app.example.com` matches `example.com` |
| **Host**               | The hostname (and port, if specified) match exactly                  |
| **Starts with**        | The detected URL starts with the item's URL                          |
| **Exact**              | The URLs are identical                                               |
| **Regular expression** | The detected URL matches the item's regex                            |
| **Never**              | Never matched — the URL is kept for reference only                   |

Out of the box the global default is **Base domain**, which is right for
most websites. Tighten it per-URL when a domain hosts many unrelated
services (`Host` or `Starts with`), or loosen it with a regex for unusual
setups.

## Matching apps

Android apps are matched by package name using the `androidapp://` scheme —
for example:

```text
androidapp://com.example.app
```

## Equivalent domains

Bitwarden accounts can define **equivalent domains** — sets of domains that
should be treated as the same site (think `google.com` and `youtube.com`).
Keyguard honors them: an item saved for one domain in a set is suggested on
the others. You can review them in the app under **Equivalent domains**.

One exception, matching Bitwarden's behavior: a URL set to **Exact** match
ignores equivalent domains entirely.
