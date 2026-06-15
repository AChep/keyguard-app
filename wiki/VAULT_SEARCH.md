# Vault Search

Vault search helps you find items in your vault using plain words, quoted phrases, and
field-specific qualifiers. The same query language works in the main vault search field and in
Quick search.

- Type a word to search across common vault fields such as title, username, email, URL, domain,
  custom field names, attachment names, identity names, phone numbers, cardholder names, and passkey
  details.
- Separate terms with spaces to narrow the results: each term adds another clause to the search.
- Use quotes to search for an exact phrase: `"example team"`.
- Use qualifiers to search in a specific field: `username:bob`.
- Start a clause with `-` to exclude matches: `github -work`.

### Qualifiers

Use `qualifier:value` to search in a specific field.

| Qualifier            | Searches in                   |
|:---------------------|:------------------------------|
| `title:` or `name:`  | Item title                    |
| `username:`          | Username                      |
| `email:`             | Email _(email-like username)_ |
| `password:`          | Password                      |
| `url:`               | URL                           |
| `domain:` or `host:` | URL host                      |
| `attachment:`        | Attachment names              |
| `field:`             | Custom fields                 |
| `note:`              | Notes                         |
| `ssh:`               | SSH key fields                |
| `passkey:`           | Passkey details               |
| `card-number:`       | Card number                   |
| `card-brand:`        | Card brand                    |
| `account:`           | Account                       |
| `folder:`            | Folder                        |
| `tag:`               | Tag                           |
| `organization:`      | Organization                  |
| `collection:`        | Collection                    |

### Examples

| Query                               | What it does                                                                   |
|:------------------------------------|:-------------------------------------------------------------------------------|
| `github`                            | Finds items related to `github`.                                               |
| `github work`                       | Finds items related to both clauses.                                           |
| `"example team"`                    | Searches for the full phrase.                                                  |
| `github -personal`                  | Finds items related to `github`, excluding ones that match `personal`.         |
| `title:github username:alice`       | Searches for `github` in titles and `alice` in usernames.                      |
| `title:github note:"shared access"` | Searches for `github` in titles and `shared access` in notes.                  |
| `domain:example -tag:archive`       | Searches for `example` in domains, exlcuding results that match `archive` tag. |
