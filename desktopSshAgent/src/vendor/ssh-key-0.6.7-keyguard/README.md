# [RustCrypto]: SSH Keys and Certificates

[![crate][crate-image]][crate-link]
[![Docs][docs-image]][docs-link]
[![Build Status][build-image]][build-link]
![Apache2/MIT licensed][license-image]
![Rust Version][rustc-image]
[![Project Chat][chat-image]][chat-link]

[Documentation][docs-link]

## About

Pure Rust implementation of SSH key file format decoders/encoders as described
in [RFC4251] and [RFC4253] as well as OpenSSH's [PROTOCOL.key] format
specification.

Additionally provides support for SSH signatures as described in
[PROTOCOL.sshsig], OpenSSH certificates as specified in [PROTOCOL.certkeys]
including certificate validation and certificate authority (CA) support,
FIDO/U2F keys as specified in [PROTOCOL.u2f] (and certificates thereof), and
also the `authorized_keys` and `known_hosts` file formats.

Supports a minimal profile which works on heapless `no_std` targets. See
"Supported algorithms" table below for which key formats work on heapless
targets and which algorithms require `alloc`.

When the `ed25519`, `p256`, and/or `rsa` features of this crate are enabled,
provides key generation and certificate signing/verification support for that
respective SSH key algorithm.

## Features

- [x] Constant-time Base64 decoder/encoder using `base64ct`/`pem-rfc7468` crates
- [x] OpenSSH-compatible decoder/encoders for the following formats:
  - [x] OpenSSH public keys
  - [x] OpenSSH private keys (i.e. `BEGIN OPENSSH PRIVATE KEY`)
  - [x] OpenSSH certificates
  - [x] OpenSSH signatures (a.k.a. "sshsig")
- [x] OpenSSH certificate support
  - [x] OpenSSH certificate validation
  - [x] OpenSSH certificate authority (CA) support i.e. cert builder/signer
- [x] Private key encryption/decryption (`bcrypt-pbkdf` + `aes256-ctr` only)
- [x] Private key generation support: DSA, Ed25519, ECDSA (P-256/P-384/P-521),
      and RSA
- [x] FIDO/U2F key support (`sk-*`) as specified in [PROTOCOL.u2f]
- [x] Fingerprint support
  - [x] "randomart" fingerprint visualizations
- [x] `no_std` support including support for "heapless" (no-`alloc`) targets
- [x] Parsing `authorized_keys` files
- [x] Parsing `known_hosts` files
- [x] `serde` support
- [x] `zeroize` support for private keys

#### TODO

- [ ] FIDO/U2F signature support
- [ ] Legacy (pre-OpenSSH) SSH key format support
  - [ ] PKCS#1 SSH private keys (i.e. RSA-only)
  - [ ] PKCS#8 SSH private keys
  - [ ] [RFC4716] SSH public keys
  - [ ] SEC1 SSH public keys

### Supported Signature Algorithms

| Name                                 | Decode | Encode | Cert | Keygen | Sign | Verify | Feature   | `no_std` |
|--------------------------------------|--------|--------|------|--------|------|--------|-----------|----------|
| `ecdsa‑sha2‑nistp256`                | ✅     | ✅     | ✅   | ✅️     | ✅️   | ✅️     | `p256`    | heapless |
| `ecdsa‑sha2‑nistp384`                | ✅     | ✅     | ✅   | ✅️     | ✅️   | ✅️     | `p384`    | heapless |
| `ecdsa‑sha2‑nistp521`                | ✅     | ✅     | ✅   | ✅️️     | ✅️ ️  | ✅️️     | `p521`    | heapless |
| `ssh‑dsa`                            | ✅     | ✅     | ✅   | ✅     | ✅️   | ✅️     | `dsa`     | `alloc` ️ |
| `ssh‑ed25519`                        | ✅     | ✅     | ✅   | ✅️     | ✅️   | ✅     | `ed25519` | heapless |
| `ssh‑rsa`                            | ✅     | ✅     | ✅   | ✅️     | ✅️   | ✅     | `rsa`     | `alloc`  |
| `sk‑ecdsa‑sha2‑nistp256@openssh.com` | ✅     | ✅     | ✅   | ⛔     | ⛔️   | ✅️     | ⛔        | `alloc`  |
| `sk‑ssh‑ed25519@openssh.com`         | ✅     | ✅     | ✅   | ⛔     | ⛔️   | ✅️️     | `ed25519` | `alloc`  |

By default *no SSH signature algorithms are enabled* and you will get an
`Error::AlgorithmUnsupported` error if you try to use them.

Enable the `crypto` feature or the "Feature" for specific algorithms in the
chart above (e.g. `p256`, `rsa`) in order to use cryptographic functionality.

The "Feature" column lists the name of `ssh-key` crate features which can
be enabled to provide full support for the "Keygen", "Sign", and "Verify"
functionality for a particular SSH key algorithm.

## Minimum Supported Rust Version

This crate requires **Rust 1.65** at a minimum.

We may change the MSRV in the future, but it will be accompanied by a minor
version bump.

## License

Licensed under either of:

 * [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
 * [MIT license](http://opensource.org/licenses/MIT)

at your option.

### Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in the work by you, as defined in the Apache-2.0 license, shall be
dual licensed as above, without any additional terms or conditions.

[//]: # (badges)

[crate-image]: https://buildstats.info/crate/ssh-key
[crate-link]: https://crates.io/crates/ssh-key
[docs-image]: https://docs.rs/ssh-key/badge.svg
[docs-link]: https://docs.rs/ssh-key/
[license-image]: https://img.shields.io/badge/license-Apache2.0/MIT-blue.svg
[rustc-image]: https://img.shields.io/badge/rustc-1.65+-blue.svg
[chat-image]: https://img.shields.io/badge/zulip-join_chat-blue.svg
[chat-link]: https://rustcrypto.zulipchat.com/#narrow/stream/346919-SSH
[build-image]: https://github.com/RustCrypto/SSH/actions/workflows/ssh-key.yml/badge.svg
[build-link]: https://github.com/RustCrypto/SSH/actions/workflows/ssh-key.yml

[//]: # (links)

[RustCrypto]: https://github.com/rustcrypto
[RFC4251]: https://datatracker.ietf.org/doc/html/rfc4251
[RFC4253]: https://datatracker.ietf.org/doc/html/rfc4253
[RFC4716]: https://datatracker.ietf.org/doc/html/rfc4716
[PROTOCOL.certkeys]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.certkeys?annotate=HEAD
[PROTOCOL.key]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.key?annotate=HEAD
[PROTOCOL.sshsig]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.sshsig?annotate=HEAD
[PROTOCOL.u2f]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.u2f?annotate=HEAD
