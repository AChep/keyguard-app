---
title: Export your vault
description: "Export items from Keyguard as an encrypted ZIP archive or a KeePass database, with or without attachments."
section: backup
type: how-to
order: 20
platforms: all
related: [migrating, credential-exchange, backups]
---

To take a copy of your items out of Keyguard, export them as a
password-protected ZIP archive or KeePass database.

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
3. Select **ZIP archive** or **KeePass database (KDBX)**.
4. Select **Continue**.
5. To export only some of the items, narrow them with the filters.
6. To include attached files, turn on **Export attachments**.
7. Select **Continue**.
8. Enter a password for the file and confirm it.
9. Select **Continue**.
10. Review the export, then select **Export**. Keyguard saves the file to your
    Downloads folder.

**Export individual vault** exports the account's items outside
organizations. **Export vault** appears when the account belongs to an
organization, and also exports the organization's items.

## Verify

### ZIP archive

Open `keyguard_export_<DATE>.zip` with a ZIP tool that
supports AES-256 encryption. Enter the archive password.

| Entry | Contents |
| --- | --- |
| `vault.json` | Items, folders, collections and organizations, in an extended version of Bitwarden's JSON export format |
| `attachments/` | Attached files, if you turned on **Export attachments** |

### KeePass database

Open `keyguard_export_<DATE>.kdbx` in a KeePass-compatible app.
Enter the database password. Folders become groups, and attached files are
stored in the database. Organizations and collections aren't exported.
