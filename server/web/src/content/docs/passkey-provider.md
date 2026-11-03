---
title: Use Keyguard as a passkey provider
description: "Register Keyguard as the system's passkey provider to create and sign in with passkeys stored in your vault."
section: autofill
type: how-to
order: 30
platforms: [android, windows]
related: [passkeys, autofill, troubleshoot-autofill]
---

To create and use passkeys from your vault in apps and browsers, register
Keyguard as the system's passkey provider.

### Before you begin

| Platform | Requirement |
| --- | --- |
| Android | Android 14 or later, on a device with credential provider support |
| Windows | Windows 11 with support for third-party passkey providers, and Keyguard from the Microsoft Store or an MSIX package |

## Register Keyguard

### Android

1. In Keyguard, open **Settings > Autofill**.
2. Select **Credential provider**. Android opens its passwords and passkeys
   settings.
3. Turn on **Keyguard**.

**Passkeys support** and **Passwords support**, in **Settings > Autofill**,
set which requests Keyguard answers. Both are on by default.

### Windows

1. In Keyguard, open **Settings > Autofill**.
2. Turn on **Passkeys**. Keyguard asks to let Windows store the websites and
   account names of your passkeys. Private keys stay in your vault.
3. Select **OK**. The switch reads "Registered. Enable Keyguard in your
   system's passkey settings."
4. Select **Open passkey settings**. Windows opens its passkey settings.
5. Turn on **Keyguard**. In Keyguard, the switch reads "Keyguard can save and
   use passkeys."

## Create a passkey

1. On a website or in an app, start creating a passkey.
2. In the system prompt, select **Keyguard**.
3. If Keyguard is locked, unlock it.
4. Select the login to store the passkey in.
5. Select **Save to**. Keyguard adds the passkey to the login.

## Sign in with a passkey

1. On the website or in the app, choose to sign in with a passkey.
2. In the system prompt, select a passkey from Keyguard.
3. If Keyguard is locked, unlock it.

## Verify

Open the login in Keyguard. The item shows the new passkey.
