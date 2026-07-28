# Keyguard fork provenance

- Upstream package: `polyval` 0.6.2 from crates.io
- Crates.io package SHA-256: `9d1fe60d06143b2430aa532c94cfe9e29783047f06c0d7fd359a9a51b729fa25`
- Upstream repository: `https://github.com/RustCrypto/universal-hashes`
- Upstream commit: `4e7d04f7c8e44f634ada8c673c8cb1672a64d374`
- Upstream path: `polyval`
- Imported upstream `Cargo.toml.orig` SHA-256: `b5116e6d0066afde0cff025882fc18353ab786ce755ff1fd12e3eeea0b306b52`
- Upstream `LICENSE-APACHE` SHA-256: `a9040321c3712d8fd0b09cf52b17445de04a23a10165049ae187cd39e5c86be5`
- Upstream `LICENSE-MIT` SHA-256: `a291b6910744d262a9e953c3b5868d74510e20161359f649a11e1791fde84fcd`

The source was imported from the checksum-verified Cargo registry package.
`.cargo_vcs_info.json`, both upstream licenses, and `Cargo.toml.orig` are kept
next to the fork. The local source adds borrowed final-tag helpers so the
runtime-autodetect union can finalize in place and then drop exactly its active
backend. Its new `Drop` dispatch erases the selected backend; the AArch64 PMULL
backend now erases `h` and `y`, matching the existing soft32, soft64, and x86
CLMUL backends. The field arithmetic and output transformation are unchanged.
One scoped Clippy allowance retains upstream's explicit constant-time bit
reversal formula under the pinned Rust 1.94.0 warning set.
The effective normalized manifest disables automatic discovery of upstream's
nightly-only benchmark so the fork remains compatible with the workspace's
stable all-target Clippy gate; benchmark source and `Cargo.toml.orig` remain
unchanged.

Changes to this file, either manifest, or any `src/` file require rerunning the
Cargo Deny wrapper/feature checks, compiler unsafe lints, RFC 8452 vector tests,
and the soft/x86/AArch64 backend checks.
