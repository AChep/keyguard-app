---
title: Placeholders
description: "Tokens that expand into an item's data: fields, URL parts, text transformations, dates and times."
section: reference
type: reference
order: 10
platforms: all
related: [url-overrides]
---

Placeholders are tokens that Keyguard replaces with an item's data when you
open the item. Copying or opening a URI uses the result. The syntax follows
the [KeePass placeholder specification](https://keepass.info/help/base/placeholders.html).

Placeholders work in:

- each URI of an item, except one whose
  **Match detection** is **Regular expression**;
- commands in [URL overrides](/docs/url-overrides/).

Other fields, autofill and Quick search use the text as written.

## Resolution rules

- Placeholder names and their basic parameters are case-insensitive. The
  comment prefix `{c:...}` must be lowercase, and environment variable names
  follow the system's rules.
- All placeholders in an item resolve at the same instant, so time-based
  placeholders match each other.
- Placeholders can be nested. The innermost resolves first.
- A known placeholder with no value resolves to an empty string. `{otp}`
  becomes nothing if the item has no one-time password.
- An unknown placeholder stays as it is. `{keyguard}` stays `{keyguard}`.

## Item fields

| Placeholder  | Description           |
| :----------- | :-------------------- |
| `{uuid}`     | UUID                  |
| `{title}`    | Title or name         |
| `{username}` | Username              |
| `{password}` | Password              |
| `{otp}`      | One-time password     |
| `{notes}`    | Notes                 |
| `{favorite}` | `true` or `false`     |

If the username is `joe`:

```text
> https://example.com?user={username}
https://example.com?user=joe
```

## Custom fields

Reference a custom field by name with `{s:name}`. For example, if an item has a
custom field named "Email", `{s:email}` resolves to its value. If several
fields share the name, the first one wins.

If the item's `license` field holds `12345678ABCD`:

```text
> https://example.com?license={s:license}
https://example.com?license=12345678ABCD
```

## URL parts

These work in URL override commands. They extract parts of the URL being
overridden, so your command can reassemble them. `{base}` is identical to
`{url}` and supports the same parts.

For the URL `https://user:pw@keepass.info:80/path/example.php?q=e&s=t`:

| Placeholder         | Description      | Value                                                  |
| :------------------ | :--------------- | :----------------------------------------------------- |
| `{url}`             | Full URL         | `https://user:pw@keepass.info:80/path/example.php?q=e&s=t` |
| `{url:rmvscm}`      | Without scheme   | `user:pw@keepass.info:80/path/example.php?q=e&s=t`     |
| `{url:scm}`         | Scheme           | `https`                                                |
| `{url:host}`        | Host             | `keepass.info`                                         |
| `{url:port}`        | Port, or the scheme's default port | `80`                                 |
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

The first symbol after `:` sets the separator. It can be any symbol except `{`
and `}`, and the trailing separator is required.

If the username field contains `username@example.com`:

```text
> {t-replace-rx:/{username}/.*@(.*)/$1/}
example.com
```

For the exact regex semantics, see Kotlin's
[Regex.replace](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.text/-regex/replace.html)
documentation.

### Convert to another representation

```text
{t-conv:/value/type/}
```

The separator works the same way as above.

| Type | Conversion |
| :--- | :--------- |
| `u` or `upper` | Uppercase |
| `l` or `lower` | Lowercase |
| `base64` | Base64: no padding, no wrap, URL safe |
| `hex` | Hex, lowercase |
| `uri` | URI-encode, with a space as `+` |
| `uri-dec` | URI-decode, with `+` as a space |

If the username is `joe` and the password is `Password1!`:

```text
> https://example.com?user={username}&password={t-conv:/{password}/uri/}
https://example.com?user=joe&password=Password1%21
```

## Environment variables

System environment variables are supported. Enclose the variable name in `%`
characters. For example, if `HOME` is `/home/username`:

```text
> {%HOME%}
/home/username
```

## Date and time

These resolve to the current date and time, in local time or in UTC.
Components have no leading zero: January is `1`.

### Local

| Placeholder   | Description                                                                                                  |
| :------------ | :----------------------------------------------------------------------------------------------------------- |
| `{dt_simple}` | Current local date and time as a sortable string. For `2024-01-01 17:05:34` the value is `20240101170534` |
| `{dt_year}`   | Year component                                                                                                |
| `{dt_month}`  | Month component                                                                                               |
| `{dt_day}`    | Day component                                                                                                 |
| `{dt_hour}`   | Hour component                                                                                                |
| `{dt_minute}` | Minute component                                                                                              |
| `{dt_second}` | Second component                                                                                              |

### UTC

| Placeholder       | Description                                                                                                |
| :---------------- | :---------------------------------------------------------------------------------------------------------- |
| `{dt_utc_simple}` | Current UTC date and time as a sortable string. For `2024-01-01 17:05:34` the value is `20240101170534` |
| `{dt_utc_year}`   | Year component                                                                                              |
| `{dt_utc_month}`  | Month component                                                                                             |
| `{dt_utc_day}`    | Day component                                                                                               |
| `{dt_utc_hour}`   | Hour component                                                                                              |
| `{dt_utc_minute}` | Minute component                                                                                            |
| `{dt_utc_second}` | Second component                                                                                            |

## Comments

| Placeholder | Description                          |
| :---------- | :----------------------------------- |
| `{c:value}` | Comment, removed when the value resolves |
