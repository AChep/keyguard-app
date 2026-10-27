---
title: Attachments
description: "Attach files to vault items, download them, and preview images, text and ZIP archives without saving them to disk."
section: vault
type: feature
order: 90
platforms: [android, wear-os, linux, macos, windows]
related: [items]
---

Attach files to any item, such as a recovery code image or a key file. They
sync with the item's Bitwarden or KeePass account. Keyguard previews common
file types in memory, without saving them to disk.

## Add an attachment

1. Open the item and select the edit (pencil) icon.
2. In the **Attachments** section, select **Add more**.
3. Choose a file.
4. Select **Save**. The attachment shows **Pending upload** until the next
   sync saves it to the account.

You can also drop a file onto the edit screen to add it.

## Download an attachment

1. Open the item and select the attachment.
2. Select **Download**.

The **Downloads** screen, in the vault's menu, lists your downloads. A
downloaded file stays until you select **Delete local file**.

| Platform | Saves to | Download |
| --- | --- | --- |
| Android | Keyguard's app storage | Continues in the background, and retries if it fails |
| Linux, macOS | `keyguard` in your Downloads folder | Runs while Keyguard is open |
| Windows | `ArtemChepurnyi\keyguard` in your Downloads folder | Runs while Keyguard is open |

## Preview an attachment

1. Open the item and select the attachment.
2. Select **Preview**. Keyguard downloads the file into memory and shows it.

**Preview** appears once the attachment is uploaded, for files up to 8 MB of
the types below.

A Markdown file opens with **Source** and **Preview** tabs. Text and Markdown
previews have **Copy all** and **Wrap lines** buttons.

:::details[Preview file types]
| Kind | Files |
| --- | --- |
| Images | PNG, JPEG, GIF, BMP, WebP, HEIC and HEIF |
| Markdown | `.md` and `.markdown` |
| Text | Plain text, code, configuration and data files, such as `.txt`, `.json`, `.yaml`, `.env` and `.pem`, and files named `Dockerfile`, `Makefile`, `README` or `LICENSE` |
| ZIP archives | `.zip` |
:::

### Browse a ZIP archive

1. On a `.zip` attachment, select **Preview**. Keyguard lists the archive's
   files and folders.
2. Select a folder to open it, or a file to preview it.
3. To save a file, select **Save file** next to it, and choose where to save
   it.

A file marked **Encrypted** asks for the **Archive password** first. Browsing
and previews stay in memory. Saving passes the file through temporary storage,
which Keyguard cleans up afterward.
