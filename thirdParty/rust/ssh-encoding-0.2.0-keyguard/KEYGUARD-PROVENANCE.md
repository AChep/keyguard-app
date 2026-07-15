# Keyguard fork provenance

- Upstream package: `ssh-encoding` 0.2.0 from crates.io
- Crates.io package SHA-256: `eb9242b9ef4108a78e8cd1a2c98e193ef372437f8c22be363075233321dd4a15`
- Upstream repository: `https://github.com/RustCrypto/SSH`
- Upstream commit: `49e514782d81d346167fcf62ec5925c75f805b0c`
- Upstream path: `ssh-encoding`
- Imported upstream `Cargo.toml.orig` SHA-256: `a7f57cb74d73a740fb7bf00f4d34495bc7f8882d93ea04219953b479b1f1058e`
- Upstream `LICENSE-APACHE` SHA-256: `a9040321c3712d8fd0b09cf52b17445de04a23a10165049ae187cd39e5c86be5`
- Upstream `LICENSE-MIT` SHA-256: `c995204cc6bad2ed67dd41f7d89bb9f1a9d48e0edd745732b30640d7912089a4`

The source was imported from the checksum-verified Cargo registry package.
`.cargo_vcs_info.json`, both upstream licenses, and the byte-exact upstream
`Cargo.toml.orig` are retained. The local `Reader` implementation adds reviewed
nested-length validation so malformed SSH structures cannot read beyond their
declared envelope. The effective manifest carries only the reviewed-vendor
marker needed by notices and SBOM generation.

Changes to the effective manifest, provenance, or local source require the
native-crypto topology/source guard and SSH malformed-input tests.
`Cargo.toml.orig` must remain byte-for-byte upstream.
