# KeePass Test DB Generator

Install the dependency:

```bash
python3 -m pip install pykeepass
```

Available presets:

- `10k-small-fields`: 10,000 entries, 80% login / 20% secure note, 0-3 URIs, 0-4 custom fields.
- `5k-wide-fields`: 5,000 entries, 80% login / 20% secure note, 0-3 URIs, 10-600 custom fields, with 90% of items kept below 30 fields.

Basic usage:

```bash
python3 scripts/keepass/create_test_db.py \
  --preset 10k-small-fields \
  --output /tmp/test-10k.kdbx
```

Optional flags:

- `--password`: master password for the generated database. Defaults to `test-password`.
- `--seed`: override the preset's deterministic default seed.
- `--overwrite`: replace an existing output file.

Examples:

```bash
python3 scripts/keepass/create_test_db.py \
  --preset 10k-small-fields \
  --output /tmp/test-10k.kdbx \
  --overwrite
```

```bash
python3 scripts/keepass/create_test_db.py \
  --preset 5k-wide-fields \
  --output /tmp/test-5k-wide.kdbx \
  --password secret123 \
  --seed 42 \
  --overwrite
```

Generated `.kdbx` files are local artifacts and are not meant to be committed to the repository.
