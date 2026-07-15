# Keyguard fork provenance

- Upstream package: `ocb3` 0.1.0 from crates.io
- Crates.io package SHA-256: `c196e0276c471c843dd5777e7543a36a298a4be942a2a688d8111cd43390dedb`
- Upstream repository: `https://github.com/RustCrypto/AEADs`
- Upstream commit: `13983b9750758353f6a8853487069a85e0dbfe68`
- Upstream path: `ocb3`
- Imported upstream `Cargo.toml.orig` SHA-256: `9fcf572bdc9f92764a9331a80a64b798ef478e3a69302a8ac63963933eb23340`
- Upstream `LICENSE-APACHE` SHA-256: `a9040321c3712d8fd0b09cf52b17445de04a23a10165049ae187cd39e5c86be5`
- Upstream `LICENSE-MIT` SHA-256: `df5a6e5866476bcef44f2b2e1712d123ea26e350cc0b54df253beb4333bf0aec`

The source was imported from the checksum-verified Cargo registry package.
`.cargo_vcs_info.json`, both upstream licenses, and the byte-exact upstream
`Cargo.toml.orig` are retained. Upstream 0.1.0 declares an optional `zeroize`
dependency but does not erase OCB's precomputed, key-derived `L_*` tables. The
local `Drop` implementation erases those tables whenever the feature is
enabled. Keyguard enables that feature and uses AES with its own zeroizing key
schedule, so both the block-cipher owner and OCB-derived state are erased.
Wire behavior and the RFC 7253 calculations are otherwise unchanged.

Changes to this file, either manifest, or any `src/` file require rerunning the
Cargo Deny wrapper/feature checks, compiler unsafe lints, OCB KATs, and every
supported target check. `Cargo.toml.orig` must remain byte-for-byte upstream.
