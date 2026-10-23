# Keyguard fork provenance

- Upstream package: `ssh-key` 0.6.7 from crates.io
- Crates.io package SHA-256: `3b86f5297f0f04d08cabaa0f6bff7cb6aec4d9c3b49d87990d63da9d9156a8c3`
- Upstream repository: `https://github.com/RustCrypto/SSH`
- Upstream commit: `6ae2c0850c0febb485fa53678cd622aa0e5f3db5`
- Upstream path: `ssh-key`
- Imported upstream `Cargo.toml.orig` SHA-256: `ed2cd81f61d5b84abc8bfe0455968157c4fc32066303001641a5ca8106df4c2e`
- Upstream `LICENSE-APACHE` SHA-256: `a9040321c3712d8fd0b09cf52b17445de04a23a10165049ae187cd39e5c86be5`
- Upstream `LICENSE-MIT` SHA-256: `33f702959c0ea91c08b21b65cf1f08b6c122ec9e6db0b5db784a7b367d942330`

The source was imported from the checksum-verified Cargo registry package.
`.cargo_vcs_info.json`, both upstream licenses, and the byte-exact upstream
`Cargo.toml.orig` are retained. The consumer and reviewed-fork workspaces
select Keyguard's bounded `ssh-encoding` fork. The effective manifest adds the
`keyguard-decryption` feature, whose KDF implementation delegates to
`keyguard-crypto-sensitive` without enabling upstream RSA/signing/generation
features. The local source also preserves the reviewed SSHJ-compatible
OpenSSH import extensions for opaque algorithm labels, MPINT values,
security-key application strings, and private-key decoding.

Slice 6 additionally keeps the bcrypt KDF output in one contiguous
`Zeroizing<Vec<u8>>`; the cipher key and IV are borrowed from that owner for the
complete decrypt operation. Changes to the effective manifest, provenance, or
local source require the `util/crypto/tools/native-crypto-policy` check and
`SshImportCompatibilityCorpusTest`. `Cargo.toml.orig` must remain byte-for-byte
upstream.

The signature container also accepts legacy `ssh-rsa` (SHA-1) algorithm labels
for agent response encoding and decoding, matching Keyguard's existing native
signer. Signature verification and algorithm selection are unchanged. The
desktop SSH agent tests cover legacy and SHA-2 response wire round trips.

Certificate timestamps retain their full unsigned 64-bit wire values, including
OpenSSH's `u64::MAX` no-expiry sentinel. Parsing, encoding, and validity checks
are independent of platform `SystemTime` limits. The `valid_after_time` and
`valid_before_time` accessors now return `Result<SystemTime>`; unrepresentable
values and the no-expiry sentinel return `Error::Time`. No Keyguard production
callers use these accessors. Certificate and desktop-agent tests cover real
OpenSSH no-expiry fixtures, unchanged signed bytes, and validity boundaries.

The `tests/examples/*-forever-cert.pub` fixtures were generated with
OpenSSH_for_Windows_9.5p2 using `ssh-keygen -s <test-ca> -I <key-id> -n test-user
<public-key>` (and `-h` for the host certificate), with no `-V` option. They
certify the existing `id_ed25519.pub` and `id_rsa_3072.pub` test keys using a
throwaway Ed25519 CA. Tests consume the public fixtures without invoking
OpenSSH or requiring the CA private key.

RSA private-key conversion to RustCrypto preserves both prime factors, `p` and
`q`; upstream 0.6.7 passed `p` twice, causing valid signing keys to be rejected.
Regression tests check both borrowed and owned conversions, exact component
round trips, and RSA certificate signing and verification. CI exercises these
paths with `crypto,std`; the application's `keyguard-decryption,std` feature
selection remains unchanged and does not enable this conversion.
