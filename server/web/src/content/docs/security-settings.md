---
title: Security settings
description: "Every setting on the Security screen: locking, the clipboard, screen privacy, unlock methods and the app password."
section: reference
type: reference
order: 60
platforms: all
related: [lock-and-unlock, appearance, security]
---

These settings are in **Settings > Security**, in the order the screen lists
them. Where another page covers a setting in detail, its section links there.

## Locking

### Persist vault key on a disk

Keeps the vault unlocked after the app is unloaded from memory. See
[Auto-lock](/docs/lock-and-unlock/#auto-lock).

### Lock after a delay

Locks the vault after Keyguard leaves the foreground. See
[Auto-lock](/docs/lock-and-unlock/#auto-lock).

### Screen-off lock

Locks when the screen turns off. Its label and triggers differ by platform.
Android, macOS and Windows only. See
[Auto-lock](/docs/lock-and-unlock/#auto-lock).

### Lock vault

Locks the vault now. See [Lock the vault](/docs/lock-and-unlock/#lock-the-vault).

## Clipboard

### Automatically clear clipboard

Clears a copied value after 10, 20 or 30 seconds, or 1, 2 or 5 minutes. The
default is **Never**.

### Auto-refresh one-time password notification duration

After you autofill a login that has a one-time password, shows a notification
with the current code for at least this long: **Never**, 30 seconds, or 1 or 2
minutes. The default is 30 seconds. Android only.

### One-time password notification settings

Opens Android's settings for that notification. Android only.

## Privacy

### Conceal fields

Hides passwords, card numbers and other sensitive values until you reveal them.
On by default.

### Allow screenshots

Sets whether Keyguard allows screenshots and screen recording. Android and
Windows only.

- **Disabled**, the default, blocks them. On Android, it also hides the app's
  content in recent apps.
- **Allowed, with a visual cue** allows them and shows a banner while they're
  allowed. Select the banner to turn screenshots off.
- **Allowed** allows them without the banner.

### Load Website icons and Load Gravatar icons

Also in the appearance settings. See [Icons](/docs/appearance/#icons).

## Biometric unlock

### Biometric unlock

Unlocks the vault with the platform's authentication. The label depends on the
platform: **Biometric unlock**, **Windows Hello unlock** or **System
authentication unlock**. See [Unlock methods](/docs/lock-and-unlock/#unlock-methods).

### Require confirmation

After biometric authentication, asks you to confirm with a button. Off by
default. Android only, on devices with biometrics.

### Require app password

Sets how often Keyguard asks for the app password instead of biometrics. Shown
while biometric unlock is on. See
[Unlock methods](/docs/lock-and-unlock/#unlock-methods).

## Security keys

### YubiKey unlock

Unlocks the vault with a YubiKey. See
[Unlock methods](/docs/lock-and-unlock/#unlock-methods).

### Security key (FIDO2)

Unlocks the vault with a FIDO2 security key. See
[Unlock methods](/docs/lock-and-unlock/#unlock-methods).

## App password

### Change app password

See [Change the app password](/docs/lock-and-unlock/#change-the-app-password).
