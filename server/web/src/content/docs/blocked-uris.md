---
title: Blocked URIs
description: "Stop Keyguard from offering autofill in the apps and on the websites you list."
section: autofill
type: feature
order: 50
platforms: [android, linux, macos, windows]
related: [autofill, url-matching, security, browser-extension]
---

Blocked URIs stop Keyguard from offering autofill in the apps and on the
websites you list. Each entry is a rule: a URI and the mode that matches it.
The rules also apply to the [browser extension](/docs/browser-extension/).

## Block a website or app

1. In **Settings > Autofill**, select **Block autofill**. Keyguard opens
   **Blocked URIs**.
2. Select **Add**.
3. Fill in the fields listed in [Fields](#fields).
4. Select **OK**. The rule appears in the list.

To edit, duplicate or delete a rule, select it and choose the action.

In the browser extension, **Never for this site** adds a **Saving only** rule
for the site.

## Verify

Open the blocked website or app and select a username field. Keyguard offers
no logins. The browser extension's popup reads "This site matches a blocked
URI. Keyguard doesn’t fill here."

For a **Saving only** rule, Keyguard still offers logins. After you sign in,
it doesn't offer to save the login.

## Fields

| Field | Value |
| --- | --- |
| **Name** | The rule's label in the list |
| **Description** | Optional notes |
| **Enabled** | On by default. Turn it off to keep the rule without applying it. |
| **Expose data to the Autofill framework prior to unlocking the vault** | On by default. Keyguard stores the rule outside the vault, so the block works while the vault is locked. |
| **URI** | The website or app to block. For an app, see [App URLs](/docs/url-matching/#app-urls). |
| Match mode | Any of the [Match detection modes](/docs/url-matching/#match-detection-modes) except **Default** and **Never**. **Base domain** unless you change it. |
| **Block** | **Autofill and saving** unless you change it. **Saving only** keeps autofill and stops offers to save logins. |

Keyguard sees a website as its scheme and host, without a path. A rule that
includes a path never matches a website. **Exact** and **Starts with** rules
compare the scheme: a rule for `https://example.com` doesn't block
`http://example.com`. On Android, Keyguard assumes `https://` when the browser
doesn't report the scheme.

## Locked and unlocked vault

These tables apply to autofill on Android. For an **Autofill and saving** rule:

| Rule | Vault locked | Vault unlocked |
| --- | --- | --- |
| Exposed | Keyguard offers nothing | Keyguard offers nothing |
| Not exposed | Keyguard offers to unlock the vault | Keyguard suggests no logins |

A **Saving only** rule changes only the offer to save a login:

| Rule | Vault locked | Vault unlocked |
| --- | --- | --- |
| Exposed | Keyguard doesn't offer to save | Keyguard doesn't offer to save |
| Not exposed | Keyguard offers to save | Keyguard doesn't offer to save |

:::note
Keyguard reads a rule that is not exposed only while the vault is unlocked. If
the vault is still locked when you save, Keyguard may save the login.
:::

The browser extension reads no rules while the vault is locked: it offers to
unlock and to save a login on any site. If you select **Save**, Keyguard asks
you to unlock, then refuses the save where a rule blocks saving. Once the vault
is unlocked, the extension suggests no logins on a blocked site and doesn't
offer to save where saving is blocked.

With the vault unlocked, a rule also covers the
[Equivalent domains](/docs/url-matching/#equivalent-domains) of a Bitwarden
account. With the vault locked, Keyguard ignores them.
