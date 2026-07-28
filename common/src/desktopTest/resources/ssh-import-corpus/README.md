# SSH private-key import compatibility corpus

This directory freezes the JDK 21 importer boundary that the native SSH
implementation must preserve after SSHJ is removed. Every private key is
synthetic, public test material. The passphrases in `manifest.tsv` are
intentionally public and must never be reused.

## Executable inventories

- `manifest.tsv` records every checked-in import fixture, its container,
  algorithm, cipher/KDF, passphrase flow, expected domain result, golden ID,
  provenance, and coverage state.
- `public-key-goldens.tsv` stores exact normalized OpenSSH public keys and
  Keyguard's existing SHA-256 fingerprint spelling. The public identities were
  checked independently with OpenSSH `ssh-keygen`; Keyguard intentionally
  retains trailing Base64 padding.
- `legacy-pem-dek-matrix.tsv` freezes all 42 BC 1.84 legacy `DEK-Info`
  spellings: AES/DES/DES-EDE/DES-EDE3/Blowfish/RC2 across every reachable
  CBC/CFB/OFB/ECB spelling, including the bare EDE aliases.
- `pkcs8-pbe-matrix.tsv` freezes the exact JDK 21 + SSHJ 0.40.0 PKCS#8
  boundary: 14 accepted PBES2 combinations (seven PBKDF2 HMAC PRFs times
  AES-128/256-CBC) and seven classic-PBE candidates that resolve to
  `MalformedKey` before key import.
- `checksums.sha256` makes all 90 private/public fixture files immutable.

## Container coverage

- `openssh/` covers RSA and Ed25519 success, valid DSA/ECDSA rejection, and
  SSHJ's complete OpenSSH-v1 bcrypt/cipher matrix: 3DES-CBC, AES CBC/CTR/GCM,
  and ChaCha20-Poly1305.
- `pem/` contains upstream PKCS#1/PKCS#8 RSA success fixtures and valid
  DSA/ECDSA rejection fixtures.
- `legacy-pem/` contains the deterministic 42-row decrypt-only legacy PEM
  matrix. Weak algorithms are compatibility inputs and must never be emitted
  by production code.
- `pkcs8-pbe/` contains the deterministic 14-row accepted PBES2 matrix.
- `ppk/` covers PPK v1-v3, RSA and Ed25519 success, AES-256-CBC, and PPK v3
  Argon2d/i/id. The PPK v1 header cases deliberately exercise SSHJ's historic,
  header-compatible reader behavior.

`FROZEN_UPSTREAM` means a fixture is copied verbatim from an independently
maintained upstream corpus. `FROZEN_DERIVED` identifies a byte-stable
compatibility derivative. `FROZEN_GENERATED` identifies deterministic bytes
from the checked-in test-only generator.

## Provenance and regeneration

OpenSSH fixtures originate in the repository's pinned, patched `ssh-key`
0.6.7 test corpus at
`thirdParty/rust/ssh-key-0.6.7-keyguard/tests/examples`. The
encrypted RSA case is a fixed `ssh-keygen` AES-256-CTR conversion of that
upstream RSA key. Its test passphrase is `hunter42`.

The representative PEM and PuTTY fixtures originate in SSHJ tag `v0.40.0`
under `src/test/resources/keyformats`, `src/test/resources/keytypes`, and
`PuTTYKeyFileTest`. SSHJ is Apache-2.0 licensed. The PPK v1 files are
explicitly marked derivatives of v2 payloads; changing the version header is
the compatibility behavior under test. The unencrypted PPK v3 derivatives
have a valid v3 HMAC-SHA256 recomputed over the canonical PPK fields.

`common/src/desktopTest/fixtures/ssh-import-corpus/GenerateSshImportCorpus.java`
is not part of a Gradle source set. With JDK 21 and BC 1.84 `bcprov`,
`bcpkix`, and `bcutil` on the classpath, it deterministically regenerates
the 42 legacy PEM files, 14 PBES2 files, two unencrypted PPK v3 files, and the
encrypted PPK v1 header-compatibility file. Run it twice into separate
temporary directories and require a byte-for-byte diff before replacing any
fixture or checksum.

BC is intentionally permitted here as a test oracle and deterministic fixture
encoder. No production source or generated runtime package may depend on it.
The corpus and compatibility test contain no SSHJ imports or helpers, so SSHJ
can be removed from both production and test dependency graphs.
