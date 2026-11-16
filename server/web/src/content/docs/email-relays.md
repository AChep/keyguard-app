---
title: Email forwarders
description: "Generate masked email addresses through SimpleLogin, addy.io, Firefox Relay and other forwarders."
section: vault
type: reference
order: 60
platforms: all
related: [generator]
---

The email forwarding services Keyguard supports, and the fields each one
needs. A forwarder creates a masked address and forwards its mail to your
inbox.

## Services

In the [Generator](/docs/generator/) menu, open **Email forwarders**. Select
**Add integration**, then a service. It appears as a generator type and
requests a new alias each time you generate one.

| Service | Fields | Records the website |
| --- | --- | --- |
| [addy.io](https://addy.io) | **API key**, **Domain**, **Recipient ID (optional)**, **Server URL** | In the alias description |
| [Cloudflare Email Routing](https://developers.cloudflare.com/email-routing/) | **API token**, **Zone ID**, **Domain**, **Destination email** | In the routing rule's name |
| [DuckDuckGo](https://duckduckgo.com/email/) | **API key** | No |
| [Fastmail](https://www.fastmail.com) | **API key** | As the site the masked email is for |
| [Firefox Relay](https://relay.firefox.com) | **API key** | No |
| [Forward Email](https://forwardemail.net) | **API key**, **Domain** | In the alias description |
| [ImprovMX](https://www.improvmx.com) | **API key**, **Domain**, **Destination email** | No |
| [mailcow](https://mailcow.email) | **Server URL**, **API key**, **Domain**, **Destination email** | No |
| [Migadu](https://www.migadu.com) | **Email**, **API key**, **Domain**, **Destination email** | No |
| [SimpleLogin](https://simplelogin.io) | **API key**, **Server URL** | As the site the alias is used on |

Keyguard knows the website only when you generate an alias from autofill on
Android.

## Fields

Every service also has a **Name**. It labels the service's type in the
generator and defaults to the service's name.

| Field | Value |
| --- | --- |
| **API key**, **API token** | A credential from the service's account or developer settings |
| **Email** | Migadu: the email address you use to sign in to the Migadu account, which can differ from the destination email |
| **Server URL** | Base URL of your server. Required for mailcow. Optional for self-hosted addy.io or SimpleLogin; empty uses the service's own server. |
| **Domain** | addy.io: the alias domain, such as `anonaddy.me`. Forward Email, Cloudflare, ImprovMX, mailcow and Migadu: your domain. |
| **Recipient ID (optional)** | addy.io: the ID of a verified recipient. Empty means your default recipient. |
| **Zone ID** | Cloudflare: the ID of your domain's zone |
| **Destination email** | Cloudflare, ImprovMX, mailcow and Migadu: the address that aliases forward to |

## Alias formats

| Service | Alias |
| --- | --- |
| Cloudflare Email Routing | 8 random lowercase letters and digits at your domain. Keyguard creates a forwarding rule for each alias. |
| ImprovMX | 8 random lowercase letters and digits at your domain. Keyguard creates each alias in ImprovMX. |
| mailcow | 8 random lowercase letters and digits at your domain. Keyguard creates a permanent, active alias in mailcow. |
| Migadu | 8 random lowercase letters and digits at your domain. Keyguard creates each alias in Migadu. |
| SimpleLogin | The format set in your SimpleLogin account, such as random words or a UUID |
| Other services | The service's own format |
