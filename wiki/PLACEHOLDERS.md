# Placeholders

Keyguard replaces placeholders when performing an action with the field (copying, opening in a browser and more). The feature is largely based on the [Keepass's specification](https://keepass.info/help/base/placeholders.html). 

**At this moment placeholders are supported in**:

- URL field;
- URL override commands.

**Basics**:

- placeholders and their basics parameters are _case-insensitive_;
- placeholders are resolved using the shared constant time, all time-sensitive placeholders will be synced;
- if no value is found, the placeholder will be replaced with an empty string: `{otp}` will be replaced with an empty string if an entry doesn't have one-time password configured;
- if no placeholder is found, the placeholder will be kept in it's original form: `{keyguard}` will be replaced with `{keyguard}`.

### Types
#### Entry Core

| Placeholder | Description |
| :- | :---- |
| `uuid` | UUID |
| `title` | Title/Name  |
| `username` | Username |
| `password` | Password |
| `otp` | One-time password |
| `notes` | Notes |
| `favorite` | Favorite |

Example:
```
> https://example.com?user={username}
https://example.com?user=joe
```

#### Entry Custom Field
Custom strings can be referenced using `{s:name}`. For example, if you have a custom string named "Email", you can use the placeholder `{s:email}`. 

| Placeholder | Description |
| :- | :---- |
| `s:value` | First of the custom fields named 'value' |

_Example_:
```
> https://example.com?license={s:license}
https://example.com?license=12345678ABCD
```

#### Entry URL
\***URL override specific**\*

This is useful in URL override command field. You can extract data from the URL for your new *command*.

Note: `{base}` supports exactly the same parts as `{url}` and is identical to it.  

| Placeholder | Description |
| :- | :---- |
| `url` | URL: `https://user:pw@keepass.info:80/path/example.php?q=e&s=t` |
| `url:rmvscm` | URL without scheme name: `user:pw@keepass.info:80/path/example.php?q=e&s=t` |
| `url:scm` | Scheme name: `https` |
| `url:host` | Host: `keepass.info` |
| `url:port` | Port: `80` |
| `url:path` | Path: `/path/example.php` |
| `url:query` | Query: `?q=e&s=t` |
| `url:userinfo` | User information: `user:pw` |
| `url:username` | Username: `user` |
| `url:password` | Password: `pw` |

#### Text transformation

##### Replace text using regular expression

```
t-replace-rx:/text/search/replace/
```
the first symbol after `:` defines the separator. It may be any symbol except `{` and `}`. Trailing separator symbol is required.

_Example_:

Let the username field contain the email address 'username@example.com', then:
```
> {t-replace-rx:/{username}/.*@(.*)/$1/}
example.com
```

for more info how it works, see the [underlying implementation's documentation](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.text/-regex/replace.html)

##### Convert text to the other representation

```
t-conv:/value/type/
```
the first symbol after `:` defines the separator. It may be any symbol except `{` and `}`. Trailing separator symbol is required.

- `u` or `upper` - transforms 'value' component into the uppercase basing on the English locale;
- `l` or `lower` - transforms 'value' component into the lowercase basing on the English locale;
- `base64` - encodes 'value' component into the Base64 (no padding, no wrap, URL safe) representation of the text;
- `hex` - encodes 'value' component into the HEX (lowercase) representation of the text;
- `uri` - encodes 'value' component into the URI representation of the text;
- `uri-dec` - decodes 'value' component from the URI representation of the text to the text;

_Example_:
```
> https://example.com?user={username}&password={t-conv:/{password}/uri/}
https://example.com?user=joe&password=Password1%21
```

#### Environmental variables

System environment variables are supported. 
The name of the variable must be enclosed in `%` characters.

_Example_:
```
> {%HOME%}
/home/username
```

#### Date-time
##### Local

| Placeholder | Description |
| :- | :---- |
| `dt_simple` | Current local date/time as a simple, sortable string. For example, for '2024-01-01 17:05:34' the value is `20240101170534`. |
| `dt_year` | Year component of the current local date/time |
| `dt_month` | Month component of the current local date/time |
| `dt_day` | Day component of the current local date/time |
| `dt_hour` | Hour component of the current local date/time |
| `dt_minute` | Minute component of the current local date/time |
| `dt_second` | Second component of the current local date/time |

##### UTC

| Placeholder | Description |
| :- | :---- |
| `dt_utc_simple` | Current UTC date/time as a simple, sortable string. For example, for '2024-01-01 17:05:34' the value is `20240101170534`. |
| `dt_utc_year` | Year component of the current UTC date/time |
| `dt_utc_month` | Month component of the current UTC date/time |
| `dt_utc_day` | Day component of the current UTC date/time |
| `dt_utc_hour` | Hour component of the current UTC date/time |
| `dt_utc_minute` | Minute component of the current UTC date/time |
| `dt_utc_second` | Second component of the current UTC date/time |

##### Utility

| Placeholder | Description |
| :-- | :---- |
| `c:value` | Comment, removed upon transformation |
