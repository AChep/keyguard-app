# Wordlists

Keyguard allows you to add your own Wordlists to later use them during the Passphrase and Username generation.

**File format**:

The supported file extensions are `.txt` and `.wordlist`.
The file should be a text file, with each word being on its own line.
Each line that is either empty or starts from `#`, `;`, `-`, `/` will be ignored.

_Note: Keyguard will incorrectly calculate the Passphrase's strength when using custom wordlists._

### Honorable Wordlists

- [18325 words based on Ngram frequency data](https://github.com/sts10/generated-wordlists/blob/main/lists/1password-replacement/1password-replacement.txt), created by [sts10](https://github.com/sts10).
