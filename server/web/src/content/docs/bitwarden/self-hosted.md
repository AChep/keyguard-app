---
title: Connect to a Bitwarden server
description: "Connect Keyguard to bitwarden.com, bitwarden.eu, Vaultwarden or another self-hosted Bitwarden server."
section: accounts
type: how-to
order: 10
platforms: [android, wear-os, linux, macos, windows]
related: [bitwarden/two-factor-login, multi-account, sync-and-conflicts]
---

To add a Bitwarden account, sign in to its server: the official `bitwarden.com`
or `bitwarden.eu` cloud, or a self-hosted server such as
[Vaultwarden](https://github.com/dani-garcia/vaultwarden). On a Wear OS watch,
follow [Set up Keyguard on the watch](/docs/wear-os/#set-up-keyguard-on-the-watch)
instead.

## Sign in

1. In **Settings**, select **Add account**, then **Bitwarden**.
2. Select **United States**, **Europe** or **Custom (self-hosted)**. For a
   self-hosted server, fill in the [Server fields](#server-fields).
3. Enter the **Email** and **Password** you use in the web vault.
4. Select **Sign in**. If your account uses two-factor authentication, enter the second
   factor. Keyguard adds the account and syncs its items.

For the second-factor options, see
[Two-factor authentication](/docs/bitwarden/two-factor-login/).

### Server fields

These fields appear when you select **Custom (self-hosted)**. Usually,
**Server URL** is enough.

| Field | Value |
| --- | --- |
| **Server URL** | The base URL of your server. |
| **Web Vault Server URL** | Empty means the **Server URL**. |
| **API Server URL** | Empty means the **Server URL** plus `api/`. |
| **Identity Server URL** | Empty means the **Server URL** plus `identity/`. |
| **Icons Server URL** | Empty means the **Server URL** plus `icons/`. |
| **HTTP headers** | Headers sent with every request to the server, for a reverse proxy that expects them. Select **Add more** for each header, and enter its **Key** and **Value**. |

For example, to pass Cloudflare Access, create a service token in Cloudflare
and add two headers:

| Key | Value |
| --- | --- |
| `CF-Access-Client-Id` | The token's client ID |
| `CF-Access-Client-Secret` | The token's client secret |

## Sign in with SSO

1. In **Settings**, select **Add account**, then **Bitwarden**.
2. Select your server.
3. Enter your **Email**.
   <!-- vale Keyguard.SetUp = NO -->
4. Select **Log in with SSO**. Your browser opens the web vault's SSO page.
   <!-- vale Keyguard.SetUp = YES -->
5. Sign in, then return to Keyguard.
6. Enter your master password, and select **Continue**. Accounts that use Key
   Connector skip this step.

Keyguard doesn't support trusted device encryption. An account that needs it
shows an error and points you to the web vault.

## Find the server from your email

If your organization publishes its server in DNS, Keyguard can fill it in.

1. Enter your **Email**.
2. Select **Auto-discover server**. Keyguard looks up your email domain and
   shows "Server set to" with the address it found.
3. Check the address, then sign in.

The button appears for your own domains, not for public mail providers.
Keyguard looks up the domain only when you select it. On Android, discovery
needs Android 10 or newer.

## Publish a server in DNS

For server admins: add a TXT record named `_bitwarden` or `_vaultwarden` under
your email domain. Its value is the server's `https://` URL, with an optional
port and path, and no query or fragment.

```text
_bitwarden.example.com.  IN  TXT  "https://vault.example.com"
```

## Pass a captcha

If the server asks for a captcha, Keyguard shows a **Client secret** field.

1. In the Bitwarden web vault, open **Settings > Security > Keys** and view
   your API key.
2. Copy the `client_secret` value.
3. In Keyguard, enter it in **Client secret**.
4. Select **Sign in**.
