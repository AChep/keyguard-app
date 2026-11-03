---
title: Lock and unlock
description: "The app password, biometric, YubiKey and security key unlock, auto-lock, and what to do if you forget a password."
section: security
type: feature
order: 10
platforms: all
related: [security-settings, security, backups]
---

The app password locks everything Keyguard keeps on this device. You unlock
with it, or with a faster method such as biometrics, a YubiKey or a security
key, and the vault locks itself again after a delay.

## App password and account passwords

Keyguard uses two kinds of password:

- Your account password, the Bitwarden master password or the KeePass database
  password, protects the vault itself. That vault lives on the Bitwarden server
  or in the `.kdbx` file, and the same password works in other clients.
- The app password, created when you first set up Keyguard, protects the
  local copy of your vault on this device. See [Security model](/docs/security/).

The app password never leaves the device, so each device can have its own.
Changing it doesn't touch your accounts.

Locking keeps your data on the device, encrypted, and unlocking brings it back.
Signing out of an account removes its data from Keyguard, including changes that
haven't synced yet.

## Turn on an unlock method

1. Open **Settings > Security**.
2. Turn on a method from [Unlock methods](#unlock-methods). For
   **YubiKey unlock**, select **Provision slot** for slot 1 or 2.
3. Follow the prompts.

The unlock screen then offers the methods you turned on. Each method is set up
per device. Your app password still works if a key is lost or reset.

Provisioning a YubiKey slot overwrites its current configuration. Keyguard
asks first.

### Android

Keyguard accepts only strong (Class 3) biometrics, such as most fingerprint
sensors. Weak biometrics can't protect encryption keys. If your phone has only
weak biometrics, Keyguard hides **Biometric unlock**.

### Linux

**System authentication unlock** shows the desktop's polkit dialog. Whether it
accepts a fingerprint or your login password depends on the system's PAM setup.
Keyguard offers the option only when polkit and protected memory
(memfd_secret, or the kernel's process keyring) are available.

polkit needs a rule that declares the Keyguard action. The first time you turn
on the option, Keyguard installs the rule and asks for administrator approval.
The Flatpak build can't write to the host, so run this command once instead:

```sh
flatpak run --command=cat com.artemchep.keyguard /app/share/polkit-1/actions/com.artemchep.keyguard.policy | sudo install -D -m 0644 /dev/stdin /usr/share/polkit-1/actions/com.artemchep.keyguard.policy
```

The key lives in memory only. After Keyguard restarts, the first unlock needs the
app password, unless the vault key is persisted. While the key is held in
memfd_secret, the kernel blocks hibernation: turn off the option or quit
Keyguard before you hibernate.

## Lock the vault

Select **Lock vault** in the vault list's menu or in **Settings > Security**.
On desktop, you can also use the lock shortcut from
[Global shortcuts](/docs/shortcuts/#global-shortcuts).

## Change the app password

1. In **Settings > Security**, select **Change app password**.
2. In **Current password**, enter your app password.
3. In **New password**, enter the new password.
4. To keep biometric unlock, leave **Biometric authentication** selected.
5. Select **Change password**.

The change removes **YubiKey unlock** and **Security key (FIDO2)** enrollment.
Turn them on again afterward.

## Unlock methods

| Method | Platforms | Details |
| --- | --- | --- |
| **Biometric unlock** | Android, macOS | Strong biometrics on Android. Touch ID on macOS. |
| **Windows Hello unlock** | Windows | The Windows Hello prompt. |
| **System authentication unlock** | Linux | The polkit dialog. See [Linux](#linux). |
| **YubiKey unlock** | Android, Linux, macOS, Windows | HMAC-SHA1 challenge-response in slot 1 or 2. USB, or NFC on Android. On desktop, connect one YubiKey at a time. On Linux, Keyguard needs access to the key's HID device. |
| **Security key (FIDO2)** | Android, Linux, macOS, Windows | A USB key with PRF (hmac-secret) and user verification: a FIDO2 PIN, set in the key maker's app, or built-in biometrics. Windows needs WebAuthn PRF support (Windows 11 22H2 or newer). On Linux, Keyguard needs access to the key's HID device. |

With biometric unlock on, **Require app password** sets how often Keyguard
asks for the app password instead, from **Immediately** to **Never**. The
default is 14 days. Keyguard also asks after the device's biometric settings
change.

To protect single items further, see
[Per-item protection](/docs/items/#per-item-protection).

## Auto-lock

These settings are in **Settings > Security**.

| Setting | Platforms | What it does |
| --- | --- | --- |
| **Lock after a delay** | All | Locks the vault this long after Keyguard leaves the foreground, from **Immediately** to **Never**. The default is 5 minutes. |
| **Lock when screen turns off** | Android | Locks a few seconds after the screen turns off. |
| **Lock when screen turns off or device sleeps** | macOS | Locks when the display turns off or the Mac sleeps. |
| **Lock on screen off, sleep, or sign-out** | Windows | Locks when the display turns off or the PC sleeps, and when you lock Windows, sign out, switch users, or disconnect a Remote Desktop session. |
| **Persist vault key on a disk** | All | Keeps the vault key on disk, so the vault stays unlocked after the app is unloaded from memory. Turns off the screen-off lock. |

The screen-off settings are on by default.

:::caution
With **Persist vault key on a disk** on, anyone who gets access to the
device's storage can open your local vault data. Leave it off unless fewer
unlocks matter more to you.
:::

## Troubleshooting

### You forgot the app password

There's no recovery. On the unlock screen's menu, select **Erase app data**.
It erases at once, without asking to confirm. Then set Keyguard up again and add
your accounts.

Your Bitwarden server and KeePass files aren't affected. Only local changes that
never synced are lost.

### You forgot the Bitwarden master password

Recovery options, such as a password hint, emergency access or organization
recovery, depend on your server. Check the Bitwarden web vault.

### You forgot the KeePass password

There's no recovery by design. Keep the password somewhere safe.
