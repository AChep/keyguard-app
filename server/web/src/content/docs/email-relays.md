---
title: Email relays
description: Generate masked email addresses through SimpleLogin, addy.io, Firefox Relay, and other forwarder services.
category: guides
order: 6
---

An email relay gives every signup its own **masked address** that forwards to
your real inbox — one leak or spammy newsletter, and you disable a single
alias instead of changing your email everywhere. Keyguard's
[generator](/docs/generator/) creates these aliases for you through a
forwarder service you connect.

Connect services under the generator's **Email forwarders** screen. Each
integration needs an API credential from the service:

| Service                  | What you enter                                          |
| :----------------------- | :------------------------------------------------------ |
| [SimpleLogin](https://simplelogin.io)              | API key; server URL only if self-hosted                 |
| [addy.io (AnonAddy)](https://addy.io)       | API key and your alias domain; server URL if self-hosted |
| [Firefox Relay](https://relay.firefox.com)            | API key                                                 |
| [DuckDuckGo](https://duckduckgo.com/email)               | API key                                                 |
| [Fastmail](https://www.fastmail.com)                 | API key                                                 |
| [Forward Email](https://forwardemail.net)            | API key and your domain                                 |
| [Cloudflare Email Routing](https://developers.cloudflare.com/email-routing/) | API token, zone ID, your domain, and the destination email |

You can find the API key or token in the service's account/developer
settings. Once connected, pick the forwarder in the generator's username
options and Keyguard requests a fresh alias whenever you generate one —
ready to drop into a new login item.

Notes on specific services:

- **SimpleLogin** generates aliases in the style configured in your
  SimpleLogin account (random words or UUID); Keyguard does not override it.
- **addy.io** and **SimpleLogin** both work with self-hosted installations:
  point the server URL at your instance.
- **Cloudflare Email Routing** creates routing rules on your own domain, so
  it needs the zone ID and the address the aliases should forward to.
