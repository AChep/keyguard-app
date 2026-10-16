---
title: URL matching
description: "How Keyguard picks the items to suggest for a website or app: match detection and equivalent domains."
section: autofill
type: reference
order: 40
platforms: all
related: [autofill, blocked-uris, url-overrides, troubleshoot-autofill, watchtower]
---

Keyguard compares the URL of a website or app with the URLs on your login
items to choose which items to suggest. This page lists the match detection
modes and the rules that go with them.

## Match detection modes

Each URL on a login item has a **Match detection** setting, next to the URL on
the item's edit screen.

| Mode | Matches when |
| --- | --- |
| **Default** | The mode set in **Default match detection** matches |
| **Base domain** | The top-level and second-level domains match. `app.example.com` matches `example.com`. |
| **Host** | The hostname, and the port if the URL has one, match exactly |
| **Starts with** | The detected URL starts with the item's URL |
| **Exact** | The URLs are identical |
| **Regular expression** | The detected URL matches the item's regular expression |
| **Never** | Never. Keyguard keeps the URL for reference only. |

## Default match detection

| Platform | Default mode |
| --- | --- |
| Android | Set in **Settings > Autofill > Default match detection**. **Base domain** unless you change it. |
| Linux, macOS, Windows | **Base domain** |

Use **Host** or **Starts with** for a domain that hosts unrelated services.
Use **Regular expression** for URLs that the other modes can't describe.

## App URLs

Android apps match by package name, with the `androidapp://` scheme.

| Item URL | Matches |
| --- | --- |
| `androidapp://com.example.app` | The Android app with the package name `com.example.app` |

## Equivalent domains

Bitwarden accounts can define equivalent domains: sets of domains that count as
one site, such as `google.com` and `youtube.com`.

- Keyguard suggests an item saved for one domain on every domain in its set.
- A URL set to **Exact** ignores equivalent domains, as in Bitwarden.
- The account's screen lists its sets under **Equivalent domains**.
