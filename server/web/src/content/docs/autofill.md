---
title: Autofill
description: "Fill logins in other apps and browsers with Keyguard: turn it on, allow it in browsers, and adjust its options."
section: autofill
type: feature
order: 10
platforms: [android]
related: [url-matching, troubleshoot-autofill, passkey-provider, totp]
---

Keyguard fills usernames and passwords in other apps and browsers. It suggests
the logins whose URLs match the app or website you're on.

## Turn on autofill

### Android

1. In Keyguard, open **Settings > Autofill**.
2. Turn on **Autofill service**. Android opens its autofill settings.
3. Select **Keyguard**.
4. Confirm the choice. Back in Keyguard, **Autofill service** is on.

Chrome and some other browsers use their own autofill until you
allow a third-party service. Under **Autofill service**, Keyguard lists the
installed browsers that have this setting, marked **Enabled** or **Disabled**.

1. In **Settings > Autofill**, select a browser marked **Disabled**. The
   browser opens its autofill settings.
2. Choose to autofill using another service. In Chrome, select
   **Autofill using another service**.
3. Restart the browser.

## Verify

In another app or a browser, select a username field. Keyguard suggests
matching logins. On Android, they appear in the keyboard or under the field.

## Options

These options are in **Settings > Autofill**.

| Option | What it does | Default | Platforms |
| --- | --- | --- | --- |
| **Default match detection** | Sets how the URLs on your items match a website or app. See [URL matching](/docs/url-matching/). | Base domain | Android |
| **Inline suggestions** | Shows suggestions in compatible keyboards | On | Android 11 and later |
| **Manual selection** | Adds an option to search the vault next to the suggested items | On | Android |
| **Respect Autofill disabled flag** | Skips browser fields that have autofill turned off | Off | Android |
| **Auto-copy one-time passwords** | Copies the item's one-time password after you fill a login | On | Android |
| **Ask to save data** | Offers to update your vault after you fill in a form | On | Android |
| **Auto-save app or website info** | Adds the app or website to the item you fill | Off | Android |
| **Block autofill** | Turns off autofill for the URIs you list. See [Blocked URIs](/docs/blocked-uris/). | None blocked | Android |
