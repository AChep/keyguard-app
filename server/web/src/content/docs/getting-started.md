---
title: Get started with Keyguard
description: "Install Keyguard, create its local vault, and add your first Bitwarden or KeePass account."
section: get-started
type: tutorial
order: 10
platforms: all
related: [autofill, lock-and-unlock, backups, migrating, watchtower]
---

In this tutorial, you install Keyguard, create its local vault, and add your
first account. At the end, your items are in Keyguard and you can search them.

### Before you begin

- A Bitwarden or Vaultwarden account, or a KeePass (`.kdbx`) database.
  Keyguard can also create a new KeePass database.

## Install the app

1. Download Keyguard for your platform from the [Download](/download/) page.
2. Install Keyguard.
3. Open Keyguard. It asks you to create a vault.

## Create the local vault

The app password protects Keyguard's data on this device. It's separate from
your Bitwarden or KeePass password.

1. In **App password**, enter a new password.
2. Optional: to unlock with biometrics, select **Biometric authentication**.
   It appears only on devices that support it.
3. Select **Create a vault**. Keyguard opens your empty vault.

## Add an account

1. On the empty vault screen, select **Bitwarden** or **KeePass**.
2. Sign in to your Bitwarden account, or open your `.kdbx` file. Your items
   appear in the vault.

For a self-hosted server, SSO, or a database on WebDAV or S3, see
[Connect to a Bitwarden server](/docs/bitwarden/self-hosted/) or
[KeePass databases](/docs/keepass/).

## Search your vault

In **Search vault**, enter part of an item's name. Matching items appear as you
type.
