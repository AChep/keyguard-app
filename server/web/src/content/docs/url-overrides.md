---
title: URL overrides
description: Rewrite or extend an item's URLs with regex-matched commands and placeholders.
category: reference
order: 3
---

URL overrides extend what an item's URLs can do. When a URL matches an
override, Keyguard shows an extra button next to it that opens the rewritten
URL — or even launches a local program. Manage them under **Settings → Other → URL
overrides**.

An override has at least:

- **Regex** — the override applies to URLs that match this regular expression;
- **Command** — the new URL that replaces the old one, which usually contains
  [placeholders](/docs/placeholders/).

## Example: HTTPS-ify

Add a button to every insecure link that opens the same website over HTTPS:

| Field   | Content                |
| :------ | :--------------------- |
| Regex   | `^http://.*`           |
| Command | `https://{url:rmvscm}` |

When done correctly, all URLs that use HTTP get a button to open the same
website using the HTTPS protocol. That said, consider simply replacing HTTP
URLs with their safer alternative when possible.

## Example: FileZilla FTP client

Add a URL to the item that will be overridden later:

```text
ftp://{username}:{password}@example.com
```

This URL may already work if the FTP client correctly sets up URL protocol
handlers. Otherwise, add the following URL override (Linux):

| Field   | Content                 |
| :------ | :---------------------- |
| Regex   | `^ftp://.*`             |
| Command | `cmd://filezilla {url}` |

When done correctly, all matching URLs get a button that executes the command,
launching the FileZilla client with your credentials filled in.
