---
title: Export your vault
description: "Export items from Keyguard as a password-protected, encrypted ZIP archive, with or without attachments."
section: backup
type: how-to
order: 20
platforms: all
related: [migrating, credential-exchange, backups]
---

To take a copy of your items out of Keyguard, export them as a
password-protected ZIP archive.

:::note
You may not need an export to leave Keyguard. Bitwarden accounts stay usable in
the official Bitwarden apps, and KeePass databases stay `.kdbx` files that other
KeePass apps open. To copy items into another app on the same Android device,
use [Credential Exchange](/docs/credential-exchange/).
:::

## Export items

1. Start the export from one of these places:
   - The account's screen: **Export individual vault** or **Export vault**.
   - An item's menu: **Export…**.
2. Confirm access with your app password or biometrics.
3. To export only some of the items, narrow them with the filters.
4. In **Archive password**, enter a password for the archive.
5. To include attached files, turn on **Export attachments**.
6. Select **Export**. Keyguard saves the archive to your Downloads folder.

**Export individual vault** exports the account's items outside
organizations. **Export vault** appears when the account belongs to an
organization, and also exports the organization's items.

## Verify

Open the archive, named `keyguard_export_<DATE>.zip`, with a ZIP tool that
supports AES-256 encryption. Enter the archive password.

| Entry | Contents |
| --- | --- |
| `vault.json` | Items, folders, collections and organizations, in an extended version of Bitwarden's JSON export format |
| `attachments/` | Attached files, if you turned on **Export attachments** |
