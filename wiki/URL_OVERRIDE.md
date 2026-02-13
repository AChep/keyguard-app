# URL override

If you want to extend the default URL functionality, you can add URL overrides. An override has at least:

- **regex**: the override will be applied to URLs that match the regular expression;
- **command**: the new URL that will replace the old one and should usually contain [placeholders](PLACEHOLDERS.md).

### Example
#### HTTPS-ify
Add the following URL override to add a button that allows a user to open the same website by replacing HTTP with the HTTPS protocol.

| Field | Content                |
| :- |:-----------------------|
| Regex | `^http://.*`           |
| Command | `https://{url:rmvscm}` |

When done correctly, all URLs that use HTTP will have a button to open the same website using the HTTPS protocol.
Note that you should consider just replacing all URLs that use HTTP with their safer alternative when possible.

#### FileZilla FTP Client
Add a URL to the entry that will be overridden later:
```
ftp://{username}:{password}@example.com
``` 

This URL may already work if the FTP client correctly sets up URL protocol handlers. Otherwise, add the following URL override (Linux):

| Field | Content                 |
| :- |:------------------------|
| Regex | `^ftp://.*`             |
| Command | `cmd://filezilla {url}` |

When done correctly, all matching URLs will have a button to execute the command, launching the FileZilla client.
