---
title: Two-factor login
description: The second-factor methods Keyguard supports when signing in to a Bitwarden account, and how each one works.
category: accounts
order: 2
---

If your Bitwarden account has two-step login enabled, Keyguard asks for the
second factor right after your email and master password. When your account
has several methods configured, you can switch between them on the
verification screen.

| Method                  | Android | Desktop | Notes                              |
| :---------------------- | :-----: | :-----: | :--------------------------------- |
| Authenticator app (TOTP) | ✓      | ✓       | Enter the 6-digit code             |
| Email                   | ✓       | ✓       | Code sent to your email, resendable |
| New-device verification | ✓       | ✓       | Email code on first login from a new device |
| YubiKey                 | ✓       | ✓       | Android: USB/NFC tap; desktop: enter the OTP manually |
| FIDO2 WebAuthn          | ✓       | —       | Browser-based flow                 |
| Duo / organization Duo  | ✓       | —       | In-app web view                    |
| FIDO U2F (legacy)       | —       | —       | _Superseded by FIDO2 WebAuthn_       |
| Log in with SSO         | —       | —       | Not supported                      |
| Log in with a device    | —       | —       | Not supported                      |
| Log in with a passkey   | —       | —       | Not supported                      |

## Remember me

Every method that recurs offers a **remember** option — new-device
verification is the exception. Tick it and Keyguard skips the
second factor on future logins from the same device.

## Method notes

### Authenticator & email

Enter the verification code from your authenticator app, or the code that
arrives in your inbox. The email screen has a resend button in case the code
does not arrive.

### YubiKey

On **Android**, plug the key in over **USB** or hold it to the back of your
phone for **NFC**, then touch the key. On **desktop**, Keyguard doesn't talk
to the key directly — type the one-time code it produces into the field. You
can also type the code manually at any time.

### FIDO2 WebAuthn

Keyguard hands the challenge to your **web browser**, where you use your
security key or platform authenticator on a page served by your web vault.
Once verified, the browser sends you straight back to the app. Mobile only.

### Duo

Duo verification — personal or organization-managed — runs inside an in-app
web view that loads your web vault's Duo connector page, and returns to the
app when approved. Mobile only.
