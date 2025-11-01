# URL override

If you want to extend the default URL functionality, you can add URL overrides. An override has at least:

- **regex**: the override will be applied to URLs that match the regular expression;
- **command**: the new URL that will replace the old one, usually should contain [placeholders](PLACEHOLDERS.md).

### Example
#### HTTPS-ify
Add the following URL override to add a button that allows a user to open the same website replacing HTTP with HTTPS protocol.

| Field | Content                |
| :- |:-----------------------|
| Regex | `^http://.*`           |
| Command | `https://{url:rmvscm}` |

when done correctly, all URLs that use HTTP will have a button to open the same website using the HTTPS protocol.
Note that you should consider just replacing all URLs that use HTTP with their safer alternative when possible.

#### FileZilla FTP Client
Add a URL to the entry that we be overridden later: 
```
ftp://{username}:{password}@example.com
``` 

this URL may already work if the FTP client correctly sets up URL protocol handlers. Otherwise, add the following URL override (Linux):

| Field | Content                 |
| :- |:------------------------|
| Regex | `^ftp://.*`             |
| Command | `cmd://filezilla {url}` |

when done correctly, all matching URLs will have a button to execute the command, launching the FireZilla client.

