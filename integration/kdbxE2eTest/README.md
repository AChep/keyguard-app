# KDBX / PyKeePass E2E Tests

This module checks KDBX interoperability in both directions:

1. `pykeepass` generates and reopens a deterministic database.
2. `:util:kdbx` decodes it and compares every supported semantic field with a JSON manifest.
3. `:util:kdbx` re-encodes the database and decodes it again.
4. `pykeepass` opens the Kotlin output and compares it with the same manifest.

Generated databases and manifests stay under `build/kdbxE2eTest/artifacts/`. They are local test
artifacts and must not be committed.

## Python setup

Use Python 3.10 or newer and install the pinned dependencies into a virtual environment:

```bash
python3 -m venv integration/kdbxE2eTest/.venv
integration/kdbxE2eTest/.venv/bin/python -m pip install \
  -r integration/kdbxE2eTest/requirements.txt
```

Run the suite with that interpreter:

```bash
./gradlew :integration:kdbxE2eTest:kdbxE2eTest \
  -PkdbxE2ePython="$PWD/integration/kdbxE2eTest/.venv/bin/python"
```

When `-PkdbxE2ePython` is omitted, the task uses `python3` from `PATH`. On Windows, point the
property to the virtual environment's `Scripts/python.exe`.

The Gradle task runs the Python driver's `doctor` command before starting JUnit and reports the
exact install command when the required `pykeepass` version is missing.

## Covered formats

- KDBX 3.1: AES cipher with AES-KDF.
- KDBX 4.0: AES cipher with AES-KDF.
- KDBX 4.0: AES cipher with Argon2d.
- KDBX 4.0: Twofish cipher with Argon2d.
- KDBX 4.0 AES/Argon2d with password-plus-keyfile credentials.
- KDBX 4.0 AES/Argon2d with key-file-only credentials (no password).

The Python driver copies the existing seed databases from `:util:kdbx` into the build directory;
it never modifies the checked-in seeds. ChaCha20, Argon2id, KDBX 4.1-only fields, malformed input,
and large-vault performance remain covered by the focused `:util:kdbx` tests and benchmarks.

## Python driver

The driver at `python/kdbx_e2e.py` exposes three commands used by Gradle:

- `doctor` validates the pinned runtime.
- `generate` creates a database and its canonical JSON manifest.
- `verify` opens a database and checks it against a manifest.

Run `python/kdbx_e2e.py <command> --help` for the command-line arguments.
