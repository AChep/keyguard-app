# Backups

Automatic backups are change-triggered. Keyguard marks the backup state dirty
when vault data changes. The scheduler waits a few seconds before starting a backup so a burst of changes can
be captured in one snapshot. You can also start a manual backup with **Run backup
now**.

### Repository structure

Automatic backups are stored as a small append-only repository made of
independent ZIP archives. The index is the authoritative repository catalog:
without a valid index generation the repository is treated as empty, and
existing snapshots or blobs may be discarded as garbage by future maintenance.
Important objects are written as immutable files so a failed backup can leave
extra temporary or orphaned files, but must not destroy the newest previously
valid indexed backup state.

```text
repo.zip
indexes/
  <generation>-<index-id>.zip
snapshots/
  <snapshot-id>.zip
blobs/
  <first-2>/<next-2>/<blob-id>.zip
health-check/
  <probe>.probe
```

`repo.zip` contains repository metadata, such as the repository id, format
version, creation time, crypto mode, feature list, and layout names. It is
created once for a repository and should not be replaced as part of normal
backup runs.

Each file under `indexes/` is an immutable full index generation. An index has
its own id and parent index ids so concurrent writes at the same generation can
be merged by a later backup run. On repository open, Keyguard uses the newest
generation that has at least one readable index. If multiple readable indexes
exist in that generation, Keyguard merges their snapshot and blob catalogs
before writing the next generation. Lower generations are ignored once a newer
readable generation exists, so snapshots or blobs referenced only by a raced
older generation may later be treated as garbage.

When a backup password is configured, `repo.zip` and index ZIPs are encrypted
with that repository password. Snapshot and blob ZIPs are encrypted with random
per-object keys stored in the encrypted index. When no backup password is
configured, snapshot and blob ZIPs are not encrypted and their index encryption
metadata is `none`.

Each file under `snapshots/` is a backup snapshot. A snapshot ZIP contains:

- `manifest.json`, with snapshot metadata and attachment references.
- `vault.json`, with the exported vault data for that snapshot.

Each file under `blobs/` stores one backed-up attachment as `attachment.bin`.
Blob paths are sharded by the first bytes of the blob id so large repositories
do not put every attachment in one folder. Blob ids are random object ids.
Attachment fingerprints remain deterministic and are used only as index lookup
hints for reusing a previously indexed blob.

Files under `health-check/` are temporary probes used to test that the selected
backup location can write, read, list, and delete objects.
