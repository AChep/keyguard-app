# Wordlists

Keyguard allows you to add your own wordlists to use them later during passphrase and username generation.

**File format**:

The supported file extensions are `.txt`, `.wordlist` and `.asc`.
The file should be a text file, with each word being on its own line.
Each line that is either empty or starts with `#`, `;`, `-`, `/` will be ignored.

Diceware lists, such as EFF's, are supported as is. If most lines start with dice numbers (`11111 abacus`),
Keyguard keeps the word after the numbers and skips lines without them, such as a PGP signature.

_Note: Keyguard will incorrectly calculate the Passphrase's strength when using custom wordlists._

### Honorable Wordlists

- [18325 words based on Ngram frequency data](https://github.com/sts10/generated-wordlists/blob/main/lists/1password-replacement/1password-replacement.txt), created by [sts10](https://github.com/sts10).
