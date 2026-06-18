---
title: Search
description: Find any item fast with Keyguard's search — terms, exact phrases, exclusion, and field qualifiers.
category: reference
order: 1
---

Keyguard's search finds the item you need in a few keystrokes. The same query
language works in the main vault search field and in **Quick search** — open it
from anywhere with `Ctrl/⌘ + Shift + Space`. Results update as you type, ranked
so the best match rises to the top.

## The basics

Type a word and Keyguard matches it against the most useful fields of every
item — title, username, email, website URL and domain, attachment names, and
more (see [What's searched](#whats-searched) below). Matching is:

- **Case-insensitive** — `alice` and `Alice` are the same.
- **Accent-insensitive** — `jose` matches `José`.
- **Partial** — `ali` already matches `alice`; you rarely type a whole word.

## Combine terms

Add more words to narrow the results. Every term must match — terms are
combined with **AND**:

```text
github work
```

finds only items that match both `github` and `work`.

## Match a phrase

Wrap words in quotes to match them together, in order:

```text
"example team"
```

Case, accents, and separators are still ignored, so `"example team"` also
matches an item titled `Example-Team`.

## Exclude matches

Start a term with `-` to drop items that match it. This works for plain words
and for qualifiers:

```text
github -personal
domain:example -tag:archive
```

## Search a specific field

Use `qualifier:value` to look inside one field instead of everywhere. Mix
qualifiers with plain words, quote their values, and negate them freely:

```text
username:alice
title:github note:"shared access"
```

### Qualifier reference

| Qualifier | Searches in |
| :-- | :-- |
| `title:`, `name:` | Item title |
| `username:` | Username |
| `email:` | Email (email-like username, or identity email) |
| `url:` | Full URL |
| `domain:`, `host:` | URL host (and passkey domain) |
| `note:` | Notes |
| `field:` | Custom field names **and** values |
| `attachment:` | Attachment file names |
| `passkey:` | Passkey domain and name |
| `ssh:` | SSH key fields |
| `password:` | Password |
| `card-number:` | Card number |
| `card-brand:` | Card brand |
| `account:` | Account |
| `folder:` | Folder |
| `tag:` | Tag |
| `organization:` | Organization |
| `collection:` | Collection |

An unrecognized qualifier is treated as plain text — `type:login` simply searches for the words "type:login".

## See also

- [Shortcuts](/features/shortcuts-placeholders/#shortcuts) — including Quick search.
