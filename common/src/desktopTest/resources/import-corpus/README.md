# Import corpus

Export files written by the real exporters of other apps, and by Keyguard
itself, frozen so that `ImportCorpusTest` checks the importers against them
without Docker, servers or accounts. Every value in them is synthetic. The
passwords in `manifest.tsv` are public test material and must never be reused.

## Files

| Resource | Written by | Notes |
| --- | --- | --- |
| `bitwarden/vault.csv` | Bitwarden CLI, `--format csv` | Logins and notes only, as the format allows. |
| `bitwarden/vault.json` | Bitwarden CLI, `--format json` | Every item type of the seed. |
| `bitwarden/vault-protected-pbkdf2.json` | Bitwarden CLI, `--format encrypted_json --password` | PBKDF2 account. |
| `bitwarden/vault-protected-argon2id.json` | the same, Argon2id account | Present only if the export follows the account KDF. |
| `bitwarden/vault-account-encrypted.json` | Bitwarden CLI, `--format encrypted_json` | Locked to the account: the import must refuse it. |
| `keepassxc/database.csv` | `keepassxc-cli export --format csv` | From `seed/keepassxc.xml`. |
| `keyguard/export.zip` | Keyguard's exporter | Password-protected, with one attachment. |

- `manifest.tsv` holds what each file must import as. It is written by hand:
  the expectations come from the seeds, not from the importer.
- `provenance.tsv` records the exporter and version of every file. The
  generators write it.
- `checksums.sha256` pins the bytes of every export.

## What the real exporters do

Behavior observed in these files that hand-written fixtures would not show:

- Bitwarden's JSON export lists no attachments at all, not even their
  names, so nothing in it is counted as a skipped attachment.
- Bitwarden keeps archived items in every export, with the date in
  `archivedDate`; the CSV has that column only since item archiving.
- Bitwarden's CSV export writes a linked custom field as `Login: undefined`,
  leaves out cards, identities and SSH keys, and ends records with CRLF while
  line breaks inside a quoted cell stay LF.
- A password-protected Bitwarden export uses the KDF of the account that
  made it, Argon2id included.
- KeePassXC writes the entries of the Recycle Bin as an ordinary
  `<root>/Recycle Bin` group, so they import as live items in a
  `Recycle Bin` folder.
- KeePassXC writes neither custom attributes nor entry history to CSV, and
  keeps an empty title empty.

## Regenerating

The seeds and generators live in
`common/src/desktopTest/fixtures/import-corpus/`, outside any source set.
Regeneration changes bytes even when nothing else changed (identifiers,
dates, random salts), so review the diff, then commit the files, the
provenance and the checksums together. The tests check what the files
import as, not their bytes.

1. Keyguard, with Gradle only:

   ```bash
   ./gradlew :common:importCorpusKeyguard
   ```

2. Bitwarden and KeePassXC, with Docker only. The compose file pins
   Vaultwarden and the generator image pins the Bitwarden CLI and
   KeePassXC; `all` also rewrites `checksums.sha256`:

   ```bash
   docker compose -f common/src/desktopTest/fixtures/import-corpus/compose.yaml run --rm --build generator all
   docker compose -f common/src/desktopTest/fixtures/import-corpus/compose.yaml down -v
   ```

3. After regenerating only the Keyguard file, refresh the checksums; this
   needs nothing but Python 3:

   ```bash
   python3 common/src/desktopTest/fixtures/import-corpus/generate.py checksums
   ```

The Bitwarden CLI cannot create accounts, so `generate.py` signs up on the
throwaway Vaultwarden the way the web vault does, and a successful
`bw login` confirms it. The CLI only talks to HTTPS servers, so a `certs`
step creates a throwaway CA for the `vaultwarden` host first.
