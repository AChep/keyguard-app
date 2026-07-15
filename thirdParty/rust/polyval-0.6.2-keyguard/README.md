# RustCrypto: POLYVAL

[![crate][crate-image]][crate-link]
[![Docs][docs-image]][docs-link]
[![Build Status][build-image]][build-link]
![Apache2/MIT licensed][license-image]
![Rust Version][rustc-image]

[POLYVAL][1] ([RFC 8452][2]) is a [universal hash function][3] which operates
over GF(2^128) and can be used for constructing a
[Message Authentication Code (MAC)][4].

Its primary intended use is for implementing [AES-GCM-SIV][5], however it is
closely related to [GHASH][6] and therefore can also be used to implement
[AES-GCM][7] at no cost to performance on little endian architectures.

[Documentation][docs-link]

## Security Notes

This crate has received one [security audit by NCC Group][8], with no significant
findings. We would like to thank [MobileCoin][9] for funding the audit.

All implementations contained in the crate are designed to execute in constant
time, either by relying on hardware intrinsics (i.e. AVX2 on x86/x86_64), or
using a portable implementation which is only constant time on processors which
implement constant-time multiplication.

It is not suitable for use on processors with a variable-time multiplication
operation (e.g. short circuit on multiply-by-zero / multiply-by-one, such as
certain 32-bit PowerPC CPUs and some non-ARM microcontrollers).

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

[crate-image]: https://buildstats.info/crate/polyval
[crate-link]: https://crates.io/crates/polyval
[docs-image]: https://docs.rs/polyval/badge.svg
[docs-link]: https://docs.rs/polyval/
[build-image]: https://github.com/RustCrypto/universal-hashes/workflows/polyval/badge.svg?branch=master&event=push
[build-link]: https://github.com/RustCrypto/universal-hashes/actions?query=workflow%3Apolyval
[license-image]: https://img.shields.io/badge/license-Apache2.0/MIT-blue.svg
[rustc-image]: https://img.shields.io/badge/rustc-1.56+-blue.svg

[//]: # (footnotes)

[1]: https://en.wikipedia.org/wiki/AES-GCM-SIV#Operation
[2]: https://tools.ietf.org/html/rfc8452#section-3
[3]: https://en.wikipedia.org/wiki/Universal_hashing
[4]: https://en.wikipedia.org/wiki/Message_authentication_code
[5]: https://en.wikipedia.org/wiki/AES-GCM-SIV
[6]: https://en.wikipedia.org/wiki/Galois/Counter_Mode#Mathematical_basis
[7]: https://en.wikipedia.org/wiki/Galois/Counter_Mode
[8]: https://research.nccgroup.com/2020/02/26/public-report-rustcrypto-aes-gcm-and-chacha20poly1305-implementation-review/
[9]: https://www.mobilecoin.com/
