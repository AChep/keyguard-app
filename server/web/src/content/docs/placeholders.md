---
title: Placeholders
description: Dynamic tokens that expand into an item's data — fields, URL parts, transformations, and timestamps.
category: reference
order: 2
---

Placeholders are tokens that Keyguard replaces with real data at the moment you
use a field — copying it, opening it in a browser, and so on. The syntax is
largely based on the
[KeePass specification](https://keepass.info/help/base/placeholders.html).

Placeholders currently work in:

- an item's **URL** field;
- [**URL override**](/docs/url-overrides/) commands.

## How resolution works

- Placeholder names and their basic parameters are **case-insensitive** — the
  comment prefix `{c:...}` is the one exception and must be lowercase.
- All placeholders in a field resolve at the same shared instant, so
  time-sensitive placeholders stay in sync with each other.
- A known placeholder with no value resolves to an **empty string** — `{otp}`
  becomes nothing if the item has no one-time password configured.
- An unknown placeholder is **kept as-is** — `{keyguard}` stays `{keyguard}`.

## Item fields

| Placeholder  | Description           |
| :----------- | :-------------------- |
| `{uuid}`     | UUID                  |
| `{title}`    | Title / name          |
| `{username}` | Username              |
| `{password}` | Password              |
| `{otp}`      | One-time password     |
| `{notes}`    | Notes                 |
| `{favorite}` | Favorite              |

```text
> https://example.com?user={username}
https://example.com?user=joe
```

## Custom fields

Reference a custom field by name with `{s:name}`. For example, if an item has a
custom field named "Email", `{s:email}` resolves to its value. If several
fields share the name, the first one wins.

```text
> https://example.com?license={s:license}
https://example.com?license=12345678ABCD
```

## URL parts

*Available in URL override commands.* These extract pieces of the URL being
overridden, so your command can reassemble them. `{base}` is identical to
`{url}` and supports the same parts.

For the URL `https://user:pw@keepass.info:80/path/example.php?q=e&s=t`:

| Placeholder         | Description      | Value                                                  |
| :------------------ | :--------------- | :----------------------------------------------------- |
| `{url}`             | Full URL         | `https://user:pw@keepass.info:80/path/example.php?q=e&s=t` |
| `{url:rmvscm}`      | Without scheme   | `user:pw@keepass.info:80/path/example.php?q=e&s=t`     |
| `{url:scm}`         | Scheme           | `https`                                                |
| `{url:host}`        | Host             | `keepass.info`                                         |
| `{url:port}`        | Port             | `80`                                                   |
| `{url:path}`        | Path             | `/path/example.php`                                    |
| `{url:query}`       | Query            | `q=e&s=t`                                             |
| `{url:userinfo}`    | User information | `user:pw`                                              |
| `{url:username}`    | Username         | `user`                                                 |
| `{url:password}`    | Password         | `pw`                                                   |
| `{url:parameter:q}` | Query parameter named `q` | `e`                                           |

## Text transformations

### Replace with a regular expression

```text
{t-replace-rx:/text/search/replace/}
```

The first symbol after `:` defines the separator — any symbol except `{` and
`}` — and the trailing separator is required.

If the username field contains `username@example.com`:

```text
> {t-replace-rx:/{username}/.*@(.*)/$1/}
example.com
```

See the
[underlying implementation's documentation](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.text/-regex/replace.html)
for the exact regex semantics.

### Convert to another representation

```text
{t-conv:/value/type/}
```

The separator works the same way as above. Supported types:

- `u` or `upper` — uppercase;
- `l` or `lower` — lowercase;
- `base64` — Base64 (no padding, no wrap, URL safe);
- `hex` — HEX (lowercase);
- `uri` — URI-encode;
- `uri-dec` — URI-decode.

```text
> https://example.com?user={username}&password={t-conv:/{password}/uri/}
https://example.com?user=joe&password=Password1%21
```

## Environment variables

System environment variables are supported; enclose the variable name in `%`
characters.

```text
> {%HOME%}
/home/username
```

## Date and time

### Local

| Placeholder   | Description                                                                                                  |
| :------------ | :----------------------------------------------------------------------------------------------------------- |
| `{dt_simple}` | Current local date/time as a simple, sortable string — for `2024-01-01 17:05:34` the value is `20240101170534` |
| `{dt_year}`   | Year component                                                                                                |
| `{dt_month}`  | Month component                                                                                               |
| `{dt_day}`    | Day component                                                                                                 |
| `{dt_hour}`   | Hour component                                                                                                |
| `{dt_minute}` | Minute component                                                                                              |
| `{dt_second}` | Second component                                                                                              |

### UTC

| Placeholder       | Description                                                                                                |
| :---------------- | :---------------------------------------------------------------------------------------------------------- |
| `{dt_utc_simple}` | Current UTC date/time as a simple, sortable string — for `2024-01-01 17:05:34` the value is `20240101170534` |
| `{dt_utc_year}`   | Year component                                                                                              |
| `{dt_utc_month}`  | Month component                                                                                             |
| `{dt_utc_day}`    | Day component                                                                                               |
| `{dt_utc_hour}`   | Hour component                                                                                              |
| `{dt_utc_minute}` | Minute component                                                                                            |
| `{dt_utc_second}` | Second component                                                                                            |

## Comments

| Placeholder | Description                          |
| :---------- | :----------------------------------- |
| `{c:value}` | Comment — removed upon transformation |
