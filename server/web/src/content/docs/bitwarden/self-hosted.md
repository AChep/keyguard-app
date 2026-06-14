---
title: Supported servers
description: Connect Keyguard to bitwarden.com, bitwarden.eu, or your own server — including Vaultwarden, per-endpoint URLs, and custom HTTP headers.
category: accounts
order: 1
---

Keyguard works with any Bitwarden-compatible server. When you add an account,
pick where your vault lives:

- **US** — the official `bitwarden.com` cloud;
- **EU** — the official `bitwarden.eu` cloud;
- **Custom (self-hosted)** — your own installation, including
  **[Vaultwarden](https://github.com/dani-garcia/vaultwarden)**.

You sign in with your **email and master password**, the same credentials you
use in the web vault.

## Connecting to a self-hosted server

For most installations, choosing **Custom (self-hosted)** and entering the
base URL of your server is all it takes — Keyguard derives the individual
endpoints from it.

If your setup splits services across hosts, expand the advanced options and
set the endpoints individually:

- **Web Vault Server URL**;
- **API Server URL**;
- **Identity Server URL**;
- **Icons Server URL**.

Any endpoint you leave empty falls back to the base URL.

## Custom HTTP headers

You can add custom HTTP headers that are sent with **every request** to the
server. This is what you need when your server sits behind a reverse proxy
that expects an extra header — for example a pre-shared key or an
authentication token.

Each header is a simple **name + value** pair; add as many as your setup
requires.

A common example is **Cloudflare Access** (Zero Trust), a popular way to
shield a self-hosted server such as Vaultwarden from the open internet.
Cloudflare cannot show its login page to Keyguard, so create a **service
token** in the Cloudflare dashboard instead and add its credentials as two
headers:

| Name                      | Value              |
| ------------------------- | ------------------ |
| `CF-Access-Client-Id`     | your client ID     |
| `CF-Access-Client-Secret` | your client secret |

With these in place, Cloudflare validates every request and lets it through
to your server.

## If the server asks for a captcha

Unofficial Bitwarden clients can be asked to pass a captcha verification,
which Keyguard cannot display. When that happens, Keyguard prompts you for
your account's **client secret** instead: find it in the web vault under
**Settings → Security → Keys / API Key**, paste it into the login form, and sign in
again.
