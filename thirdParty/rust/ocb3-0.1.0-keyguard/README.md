# RustCrypto: OCB3

[![crate][crate-image]][crate-link]
[![Docs][docs-image]][docs-link]
![Apache2/MIT licensed][license-image]
![Rust Version][rustc-image]
[![Project Chat][chat-image]][chat-link]
[![Build Status][build-image]][build-link]

Pure Rust implementation of the Offset Codebook Mode v3 (OCB3)
[Authenticated Encryption with Associated Data (AEAD)][aead] cipher as described in [RFC7253].

[Documentation][docs-link]

## Example

```rust
use aes::Aes128;
use ocb3::{
    aead::{Aead, AeadCore, KeyInit, OsRng, generic_array::GenericArray},
    consts::U12,
    Ocb3,
};

type Aes128Ocb3 = Ocb3<Aes128, U12>;

let key = Aes128::generate_key(&mut OsRng);
let cipher = Aes128Ocb3::new(&key);
let nonce = Aes128Ocb3::generate_nonce(&mut OsRng);
let ciphertext = cipher.encrypt(&nonce, b"plaintext message".as_ref()).unwrap();
let plaintext = cipher.decrypt(&nonce, ciphertext.as_ref()).unwrap();

assert_eq!(&plaintext, b"plaintext message");
```

## Security Notes

No security audits of this crate have ever been performed, and it has not been thoroughly assessed to ensure its operation is constant-time on common CPU architectures.

USE AT YOUR OWN RISK!

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

[crate-image]: https://buildstats.info/crate/ocb3
[crate-link]: https://crates.io/crates/ocb3
[docs-image]: https://docs.rs/ocb3/badge.svg
[docs-link]: https://docs.rs/ocb3/
[license-image]: https://img.shields.io/badge/license-Apache2.0/MIT-blue.svg
[rustc-image]: https://img.shields.io/badge/rustc-1.60+-blue.svg
[chat-image]: https://img.shields.io/badge/zulip-join_chat-blue.svg
[chat-link]: https://rustcrypto.zulipchat.com/#narrow/stream/260038-AEADs
[build-image]: https://github.com/RustCrypto/AEADs/workflows/ocb3/badge.svg?branch=master&event=push
[build-link]: https://github.com/RustCrypto/AEADs/actions

[//]: # (general links)

[rfc7253]: https://datatracker.ietf.org/doc/rfc7253/
[aead]: https://en.wikipedia.org/wiki/Authenticated_encryption
