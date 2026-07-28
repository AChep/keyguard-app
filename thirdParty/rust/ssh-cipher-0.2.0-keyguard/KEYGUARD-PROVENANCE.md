# Keyguard fork provenance

- Upstream package: `ssh-cipher` 0.2.0 from crates.io
- Crates.io package SHA-256: `caac132742f0d33c3af65bfcde7f6aa8f62f0e991d80db99149eb9d44708784f`
- Upstream repository: `https://github.com/RustCrypto/SSH`
- Upstream commit: `65d6c00c0455b9dbb03471a337922076d123fe40`
- Upstream path: `ssh-cipher`
- Imported upstream `Cargo.toml.orig` SHA-256: `ff8acef700e97bf93da37733957d69d4b1eda8882f20f3ca04b2b2c75bf02039`
- Upstream `LICENSE-APACHE` SHA-256: `a9040321c3712d8fd0b09cf52b17445de04a23a10165049ae187cd39e5c86be5`
- Upstream `LICENSE-MIT` SHA-256: `df5a6e5866476bcef44f2b2e1712d123ea26e350cc0b54df253beb4333bf0aec`

The source was imported from the checksum-verified Cargo registry package.
`.cargo_vcs_info.json`, both upstream licenses, and `Cargo.toml.orig` are kept
next to the fork. The local manifest differs only to require zeroization on
the cipher and authenticator dependency edges and to add compatibility tests.
AES-GCM resolves to the checksum-locked crates.io 0.10.3 package; the manifest
enables its zeroization feature and explicitly unifies zeroization across its
AES, CTR, GHASH, and POLYVAL dependency state without carrying a local AES-GCM
fork.
The source delta wraps the transient ChaCha-derived Poly1305 key in an erasing
owner. Nonempty ChaCha and AES-GCM nonce/IV inputs are also borrowed directly
from the contiguous KDF output instead of copied into additional owned arrays.
Cipher algorithms, ciphertext, and tag construction remain unchanged.

Changes to this file, either manifest, or any `src/` file require rerunning the
Cargo Deny wrapper/feature checks, compiler unsafe lints, compatibility tests,
and all supported target checks.
