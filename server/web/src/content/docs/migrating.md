---
title: Import from another password manager
description: "Move your passwords into Keyguard from an export file, through Credential Exchange, or through a Bitwarden account."
section: backup
type: how-to
order: 10
platforms: all
related: [bitwarden/self-hosted, keepass, credential-exchange, export]
---

To move your items into Keyguard, import your old password manager's export
file into one of your accounts.

### Before you begin

- A Bitwarden account or KeePass database to import into. See
  [Connect to a Bitwarden server](/docs/bitwarden/self-hosted/) or
  [KeePass databases](/docs/keepass/).
- Saving the items follows the [Premium](/docs/premium/) rules.

## Export from your old password manager

| App | Where to export | Format in Keyguard |
| --- | --- | --- |
| Chrome, Edge, Brave, Opera, Vivaldi | **Export passwords** in the browser's password settings | Chrome |
| Firefox | **Export passwords** in the menu of the **Passwords** page | Firefox |
| Safari | **File > Export > Passwords** | Safari |
| Passwords app | **File > Export All Passwords to File** | Safari |
| 1Password | **File > Export**, format **1PUX**. The **CSV** format holds only logins and passwords. | 1Password |
| Bitwarden | **Tools > Export vault**, file format **.json**, **.json (Encrypted)** with **Password protected**, or **.csv** | Bitwarden |
| Dashlane | **Export to CSV** in the settings. The phone apps save a folder of CSV files; import them one at a time. | Dashlane |
| Enpass | **File > Export**, format **.json** | Enpass |
| KeePassXC | **Database > Export > CSV File** | KeePassXC |
| Keeper | **Export**, format **CSV** | Keeper |
| Keyguard | [Export your vault](/docs/export/) | Keyguard |
| Keyguard backup | [Automatic backups](/docs/backups/#recover-data-from-a-backup) | Keyguard backup |
| LastPass | **Export** in the advanced account options | LastPass |
| NordPass | **Export Items** in the settings | NordPass |
| Proton Pass | **Export** in the settings, as **ZIP** or **CSV**, not **PGP-encrypted** | Proton Pass |

Dashlane, Proton Pass and 1Password 1PUX archives must be unencrypted.
Keyguard exports and backups, and Bitwarden password-protected JSON exports,
support password entry during import.

Unencrypted exports hold your passwords in plain text. Delete them after the import.

A Keyguard export or backup and a 1Password 1PUX file bring their attachments
along. Other formats import without them, and the review lists what was
skipped. Archived items stay archived, and items in the trash return to the
trash.

A Keyguard export or backup also brings GPG keys and environments. An
environment's references to other items are unset; set them again after the
import.

## Import the export file

1. On the screen of the account the items go to, select
   **Import from another app**.
2. Under **Export file**, select the format of your old password manager.
3. Select the export file.
4. If the file is password-protected, enter its password.
5. Review the items. All items are selected; clear the ones you don't want.
6. Select **Import**. Keyguard saves the selected items, and the folders they
   need, to the account.

Keyguard doesn't remove duplicates. Importing the same file twice creates two
copies of every item and folder.

## Import from another Android app

If your old password manager is on the same Android device and supports
Credential Exchange, follow
[Importing from another app](/docs/credential-exchange/#importing-from-another-app).

## Import other formats through Bitwarden

The Bitwarden web vault reads the export formats of most password managers.

1. In your old password manager, export your data.
2. Sign in to your server's web vault:
   [vault.bitwarden.com](https://vault.bitwarden.com),
   [vault.bitwarden.eu](https://vault.bitwarden.eu), or your self-hosted
   server.
3. Select **Tools > Import data**.
4. Select your old password manager's format.
5. Import the exported file.
6. In Keyguard, sync the account. The items appear in your vault.

## Switch from the official Bitwarden apps

In Keyguard, sign in to the same account and server. Your vault syncs, and
Keyguard runs side by side with the official apps.

## Verify

In Keyguard, search for one of the imported items. It appears in the results.
