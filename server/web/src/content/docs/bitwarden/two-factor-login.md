---
title: Two-factor authentication
description: "The two-factor methods Keyguard supports when you sign in to a Bitwarden account."
section: accounts
type: reference
order: 20
platforms: all
related: [bitwarden/self-hosted, multi-account]
---

If your Bitwarden account has two-factor authentication turned on, Keyguard asks for the
second factor on the **Verification** screen, after your password or SSO
sign-in. This page lists the methods and where they work.

## Methods

With several methods set up, switch between them on the **Verification**
screen. **Remember me** skips the second factor on future sign-ins from this
device.

| Method | Platforms | Remember me | Details |
| --- | --- | --- | --- |
| **Authenticator** | All | Yes | Enter the code from your authenticator app. |
| **Email** | All | Yes | Keyguard requests the code when the method opens. **Re-send verification code** becomes available after 30 seconds. |
| **Email**, new-device verification | All | No | A code sent the first time you sign in from a new device. No resend. |
| **YubiKey** | All | Yes | On Android, connect the key over USB or hold it to the back of the device for NFC. On desktop, select the field and touch the key to type the code. |
| **FIDO2 Web Authentication** | Android, Linux, macOS, Windows | Yes | See [Security keys](#security-keys). |
| **Duo**, **Duo (Organization)** | Android | No | Runs in an in-app web view that loads your web vault's Duo page. |
| **FIDO U2F** | None | | Use FIDO2 Web Authentication instead. |
| Sign-in with a device | None | | Not supported. |
| Sign-in with a passkey | None | | Not supported. |

A method that doesn't work on your platform shows **Launch Web vault**. Switch
to another method, or use another device.

## Security keys

- **Use security key** signs in with the key directly in Keyguard. The key
  doesn't need PRF support or enrollment for vault unlock.
- On Linux and macOS, Keyguard asks for the key's PIN in its own dialog. On
  Windows, Windows shows its own security key prompt.
- On Linux, Keyguard needs permission to access the key's HID device.
- On Android, **Use browser** opens your web vault's connector page and
  returns the result to Keyguard. Use it for passkeys managed by a browser and
  for legacy U2F credentials. If Android asks which app should open the return
  link, choose Keyguard.

## Limitations

| Build | Limitation |
| --- | --- |
| Flatpak | **FIDO2 Web Authentication** isn't available. Use another method. |
