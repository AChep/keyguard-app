# [RustCrypto]: SSH Symmetric Ciphers

[![crate][crate-image]][crate-link]
[![Docs][docs-image]][docs-link]
[![Build Status][build-image]][build-link]
![Apache2/MIT licensed][license-image]
![Rust Version][rustc-image]
[![Project Chat][chat-image]][chat-link]

[Documentation][docs-link]

## About

Pure Rust implementation of SSH symmetric encryption including support for the
modern `aes128-gcm@openssh.com`/`aes256-gcm@openssh.com` and
`chacha20-poly1305@openssh.com` algorithms as well as legacy support for older
ciphers.

Built on the pure Rust cryptography implementations maintained by the
[RustCrypto] organization.

## Minimum Supported Rust Version

This crate requires **Rust 1.60** at a minimum.

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

[crate-image]: https://buildstats.info/crate/ssh-cipher
[crate-link]: https://crates.io/crates/ssh-cipher
[docs-image]: https://docs.rs/ssh-cipher/badge.svg
[docs-link]: https://docs.rs/ssh-cipher/
[license-image]: https://img.shields.io/badge/license-Apache2.0/MIT-blue.svg
[rustc-image]: https://img.shields.io/badge/rustc-1.60+-blue.svg
[chat-image]: https://img.shields.io/badge/zulip-join_chat-blue.svg
[chat-link]: https://rustcrypto.zulipchat.com/#narrow/stream/346919-SSH
[build-image]: https://github.com/RustCrypto/SSH/actions/workflows/ssh-cipher.yml/badge.svg
[build-link]: https://github.com/RustCrypto/SSH/actions/workflows/ssh-cipher.yml

[//]: # (links)

[RustCrypto]: https://github.com/rustcrypto
[RFC4251]: https://datatracker.ietf.org/doc/html/rfc4251
