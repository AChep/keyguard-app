---
title: Search
description: "Search your vault with terms, phrases, exclusions and field qualifiers, and sort the results."
section: vault
type: feature
order: 20
platforms: all
related: [filters, items, attachments]
---

Search finds items as you type, with the best match first. Plain words, quoted
phrases, exclusions and field qualifiers combine in one query.

On desktop, Ctrl+Shift+Space (Cmd+Shift+Space on macOS) opens
[Quick search](/docs/quick-search/), which takes the same queries.

## How matching works

A plain word matches the main fields of every item: title, username, email,
phone, URL and domain, identity and cardholder names, custom field names and
attachment names. Notes, passwords and custom field values match only through
a qualifier, listed in the [Qualifier reference](#qualifier-reference).

Matching is:

- **Case-insensitive**: `alice` matches `Alice`.
- **Partial**: `lic` matches `alice`.
- **Accent-insensitive in text fields**, such as titles, names and notes:
  `jose` matches `José`. Usernames, emails and URLs keep their accents.

Search runs inside the current list, so any filter you set applies first. See
[Filters](/docs/filters/).

## Combine terms

Add more words to narrow the results. An item must match every term:

```text
github work
```

## Match a phrase

Wrap words in quotes to match them together, in order:

```text
"example team"
```

Case and separators are still ignored, so `"example team"` also matches an
item titled `Example-Team`.

## Exclude matches

Start a term with `-` to drop items that match it. This works for plain words
and for qualifiers:

```text
github -personal
domain:example -tag:archive
```

## Search a specific field

Use `qualifier:value` to search one field. Qualifiers mix with plain words,
take quoted values, and accept `-`:

```text
username:alice
title:github note:"shared access"
```

## Sort the list

Select **Sort by** in the search bar, then a criterion and an order. The
default is **Title**, **Alphabetically**. Only the **Title** sort lists
favorites first.

To keep your sort after Keyguard restarts, turn on **Remember sorting method**
in the vault's menu.

## Qualifier reference

| Qualifier | Searches in |
| --- | --- |
| `title:`, `name:` | Item title |
| `username:` | Username |
| `email:` | Email: an email-like username, or an identity's email |
| `url:` | Full URL |
| `domain:`, `host:` | URL host and passkey domain |
| `note:` | Notes |
| `field:` | Custom field names and values |
| `attachment:` | Attachment file names |
| `passkey:` | Passkey domain and name |
| `ssh:` | SSH key fields |
| `gpg:` | GPG key fields |
| `password:` | Password |
| `card-number:` | Card number |
| `card-brand:` | Card brand |
| `account:` | Account |
| `folder:` | Folder |
| `tag:` | Tag |
| `organization:` | Organization |
| `collection:` | Collection |

`note:`, `password:` and `card-number:` skip items with **Auth re-prompt**
turned on. An unknown qualifier is plain text: `type:login` searches for the
words "type" and "login".

## Sort options

| Criterion | Orders |
| --- | --- |
| **Title** | **Alphabetically**, **Reverse Alphabetically** |
| **Modification date** | **Newest First**, **Oldest First** |
| **Password** | **Alphabetically**, **Reverse Alphabetically** |
| **Password modification date** | **Newest First**, **Oldest First** |
| **Password strength** | **Weakest First**, **Strongest First** |
