---
title: Autofill & passkeys on Android
description: Set up the Android autofill service and the Android 14+ credential provider for passkeys.
category: guides
order: 2
---

On Android, Keyguard can fill usernames and passwords into other apps and
browsers, and act as the system **passkey provider**. Both are set up from
**Settings → Autofill** inside Keyguard.

> Autofill is an Android feature — the desktop apps do not provide it.

## Enable the autofill service

Turn on **Autofill service** to let Keyguard use the Android Autofill
Framework to assist in filling login information into other apps on the
device. Keyguard sends you to the system screen where you pick it as the
provider.

With the service active you can fine-tune the behavior on the same settings
screen:

- **Inline suggestions** — on Android 11 and newer, suggestions appear
  directly in your keyboard;
- **Manual selection** — adds an option to manually search the vault for an
  entry when nothing matches;
- **Default URI match detection** — controls which items are suggested for
  a given site or app; see [URL matching](/docs/url-matching/);
- **Auto-copy one-time passwords** — copies the OTP after you fill a login,
  ready to paste into the next field;
- **Ask to save data** — offers to update your vault when you finish filling
  a form;
- **Block autofill** — disables autofill for specific URIs you list.

## Passkeys (Android 14+)

On Android 14 and newer, open **Settings → Autofill → Credential provider**
to register Keyguard in the system's credential manager. Once selected, you
can **sign in with passkeys** stored in your vault and **create new
passkeys** when a website or app offers them. Separate toggles control
whether Keyguard serves passkeys, passwords, or both.

Creating a passkey writes a new credential into your vault, so — like other
vault edits — it requires a premium license. Signing in with existing
passkeys is free.

## Browser notes

- **Chrome** supports third-party autofill services natively, but you have to
  opt in: open Chrome's *Settings → Autofill Services*, choose *Autofill
  using another service*, then restart Chrome.
- Some websites disable autofill on password fields, and some browsers ignore
  that flag — the **Respect the autofill-disabled flag** setting controls how
  Keyguard treats them.

## Device quirks

Some Xiaomi devices require you to manually allow the **"Display pop-up
windows while running in the background"** permission before autofill
dialogs can appear.
