---
title: Troubleshoot autofill and passkeys
description: "Fix missing or wrong autofill suggestions, and problems with Keyguard as a passkey provider."
section: autofill
type: troubleshooting
order: 70
platforms: [android, windows]
related: [autofill, passkey-provider, url-matching]
---

These fixes apply to autofill and to Keyguard as a passkey provider. A fix for
one platform names it.

## Chrome doesn't show Keyguard suggestions

On Android, Chrome uses its own autofill until you opt in to a third-party
service. Turn it on as in
[Turn on autofill](/docs/autofill/#turn-on-autofill).

## Autofill dialogs don't appear on a Xiaomi device

Some Xiaomi devices block the pop-up windows that autofill uses.

In Android's app settings for Keyguard, turn on **Display pop-up windows while
running in the background**. On these devices, Keyguard shows a note under
**Autofill service** with a button that opens the setting.

## A password field gets no suggestions

The website marks the field as autofill-disabled, and you turned on
**Respect Autofill disabled flag**, which is off by default.

In **Settings > Autofill**, turn off **Respect Autofill disabled flag**.

## The wrong logins are suggested

The item's URL uses a match detection mode that is too broad or too strict for
the site.

Edit the item and change **Match detection** next to the URL. The modes are in
[URL matching](/docs/url-matching/).

## Keyguard isn't offered when you create a passkey

Keyguard answers a create request only when it's registered, it can edit your
vault, and **Passkeys support** is on.

1. Check that Keyguard is registered, as in
   [Register Keyguard](/docs/passkey-provider/#register-keyguard).
2. If your build needs [Premium](/docs/premium/) to edit the vault, check that
   you have it.
3. On Android, in **Settings > Autofill**, turn on **Passkeys support**.

## A browser shows "Authorize this app" instead of your passkeys

On Android, a browser requests passkeys on behalf of websites. This browser
isn't on Keyguard's list of privileged apps.

1. Select **Authorize this app**.
2. In Keyguard, select **Grant the privilege and continue**.

Keyguard adds the browser to **Privileged apps** in **Settings > Autofill**. A
privileged app can request credentials for any service, so grant this only to
browsers you trust.

## The device doesn't support credential providers

Under **Credential provider**, Keyguard notes that the device doesn't support
credential providers. The device's Android build lacks the system support that
passkey providers need.

Only the manufacturer can add it. Keyguard can still fill passwords with the
autofill service. See [Autofill](/docs/autofill/).

## "Install Keyguard from the Microsoft Store or an MSIX package to use passkeys"

Only MSIX packages, including the Microsoft Store build, can register as a
passkey provider.

Install Keyguard from the Microsoft Store or an MSIX package, from the
[Download](/download/#windows) page.

## "Update your operating system to use Keyguard as a passkey provider"

This Windows version lacks the system support for third-party passkey
providers.

Update to a Windows 11 version that supports third-party passkey providers.
