---
title: URL overrides
description: "Rewrite or extend an item's URLs with regex-matched commands and placeholders."
section: reference
type: feature
order: 20
platforms: all
related: [placeholders, url-matching]
---

A URL override adds an extra entry under every item URL that matches its
regular expression. The entry opens a rewritten URL, or runs a command on your
device.

## Add a URL override

1. In **Settings > Other > URL overrides**, select **Add**.
2. Fill in the fields listed in [Fields](#fields).
3. Select **OK**. Items with a matching URL show the override under that URL.

To run a `cmd://` override, open the entry's menu and select **Execute**.

## Fields

| Field | Value |
| --- | --- |
| **Name** | The label of the override's entry on the item |
| **Regular expression** | The override applies to URLs that this expression matches. It must match the whole URL. |
| **Command** | The new URL, usually built with [Placeholders](/docs/placeholders/). A command that starts with `cmd://` runs the rest as a shell command. |
| **Enabled** | On by default. Turn it off to keep the override without applying it. |

A `cmd://` command runs in the platform's shell:

| Platform | Shell |
| --- | --- |
| Android | `sh` |
| Linux, macOS | `bash` |
| Windows | `cmd` |

## Examples

### Open HTTP links over HTTPS

This override adds an HTTPS version of every `http://` URL. Where you can,
change the item's URL to `https://` instead.

```text
Regular expression: ^http://.*
Command:            https://{url:rmvscm}
```

### Open FTP links in FileZilla

Add this URL to the item:

```text
ftp://{username}:{password}@example.com
```

If your FTP client registers itself for `ftp://` links, the URL works as is.
Otherwise, add this override on Linux. It starts FileZilla with your
credentials filled in.

```text
Regular expression: ^ftp://.*
Command:            cmd://filezilla {url}
```

## Limitations

| Build | Limitation |
| --- | --- |
| Flatpak | `cmd://` commands run inside the Flatpak sandbox, so they can't start programs installed on the host. |
