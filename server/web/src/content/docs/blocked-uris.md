---
title: Blocked URIs
description: "Stop Keyguard from offering autofill in the apps and on the websites you list."
section: autofill
type: feature
order: 50
platforms: [android]
related: [autofill, url-matching, security]
---

Blocked URIs stop Keyguard from offering autofill in the apps and on the
websites you list. Each entry is a rule: a URI and the mode that matches it.

## Block a website or app

1. In **Settings > Autofill**, select **Block autofill**. Keyguard opens
   **Blocked URIs**.
2. Select **Add**.
3. Fill in the fields listed in [Fields](#fields).
4. Select **OK**. The rule appears in the list.

To edit, duplicate or delete a rule, select it and choose the action.

## Verify

Open the blocked website or app and select a username field. Keyguard offers
no logins.

## Fields

| Field | Value |
| --- | --- |
| **Name** | The rule's label in the list |
| **Description** | Optional notes |
| **Enabled** | On by default. Turn it off to keep the rule without applying it. |
| **Expose data to the Autofill framework prior to unlocking the vault** | On by default. Keyguard stores the rule outside the vault, so the block works while the vault is locked. |
| **URI** | The website or app to block. For an app, see [App URLs](/docs/url-matching/#app-urls). |
| Match mode | Any of the [Match detection modes](/docs/url-matching/#match-detection-modes) except **Default** and **Never**. **Base domain** unless you change it. |

Keyguard sees a website as `https://` and its domain, without a path. A rule
that includes a path never matches a website.

## Locked and unlocked vault

| Rule | Vault locked | Vault unlocked |
| --- | --- | --- |
| Exposed | Keyguard offers nothing | Keyguard offers nothing |
| Not exposed | Keyguard offers to unlock the vault | Keyguard suggests no logins |

With the vault unlocked, a rule also covers the
[Equivalent domains](/docs/url-matching/#equivalent-domains) of a Bitwarden
account. With the vault locked, Keyguard ignores them.
