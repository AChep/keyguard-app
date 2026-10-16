---
title: Sends
description: "Share text or a file through a Bitwarden link that deletes itself, with an optional password or access limit."
section: accounts
type: feature
order: 60
platforms: [android, wear-os, linux, macos, windows]
related: [bitwarden/self-hosted, attachments, premium]
---

A send shares text or a file through a public link. The link holds the key
that decrypts the send. Bitwarden deletes the send on its deletion date.

### Before you begin

- A Bitwarden account. See
  [Connect to a Bitwarden server](/docs/bitwarden/self-hosted/). KeePass
  databases don't have sends.
- File sends and the **Specific people** option need Bitwarden premium on that
  account.
- Creating and changing sends follows the [Premium](/docs/premium/) rules.

## Create a send

1. Open **Send** and select **New item**.
2. Select **Text** or **File**.
3. Enter a name, then the text, or select a file with **Select a file**.
4. Set the [Options](#options), then select **Save**.

A file can be up to 500 MB. You can also drop a file on the **Sends** list to
start a file send.

Visible fields of a vault item, such as a username or a note, have
**Create a send** in their menu. It starts a text send with the field's value.

## Share a send

Open the send and select **Copy send**. Keyguard copies its name and public
link. The menu of **Public URL** also offers **Copy URL**, **Launch browser**,
a QR code and, on Android, **Share**.

A send created offline gets its public link after Keyguard syncs it.

## Change a send

To change the options, open the send and select the edit (pencil) icon. You
can't change the account, the type or the file of an existing send.

The send's menu has these actions. To run one on several sends, select them in
the **Sends** list.

| Action | Result |
| --- | --- |
| **Change name** | Renames the send |
| **Set password**, **Change password** | Recipients must enter the password to open the link |
| **Remove password** | Recipients open the link without a password |
| **Show email**, **Hide email** | Shows or hides your email address from recipients |
| **Activate**, **Deactivate** | A deactivated send can't be opened. It keeps its link. |
| **Delete forever** | Deletes the send from the server |

## Options

| Option | What it does |
| --- | --- |
| **Save to** | The Bitwarden account that holds the send |
| **When accessing the item, hide the text by default** | Text sends only. Recipients reveal the text themselves. |
| **Max access count** | The link stops working after this many views |
| **Who can view** | **Anyone with the link** (default), **Anyone with the link + password**, or **Specific people**. Specific people verify their email with a one-time code after opening the link. |
| **Email(s)** | For **Specific people**: the allowed addresses, separated by commas |
| **Deletion date** | When Bitwarden deletes the send: from 1 hour to 30 days, or a **Custom** date up to 31 days away. Default 7 days. |
| **Expiration date** | When the link stops working. The send stays in your list until its deletion date. Default **Never**. |
| **Hide email address** | Hides your email address from recipients |
| **Deactivate** | Turns the link off without deleting the send |

Notes are for you. Recipients don't see them.

## The send list

The **Sends** list marks sends with **Deactivated** and **Expired**. Sends
that delete soonest come first.

| Tool | Options |
| --- | --- |
| Search | Matches names and notes, not the text of a send |
| Filters | **Account** and **Type** |
| Sort | **Title**, **Access count**, **Modification date**, **Expiration date** or **Deletion date** |

The **Access count** of an open send shows how many times its link was opened,
out of **Max access count** if set.

## Limitations

| Platform | Limitation |
| --- | --- |
| Wear OS | View only. You can't create or change sends on the watch. |
