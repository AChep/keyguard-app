//! **POLYVAL** is a GHASH-like universal hash over GF(2^128) useful for
//! implementing [AES-GCM-SIV] or [AES-GCM/GMAC].
//!
//! From [RFC 8452 Section 3] which defines POLYVAL for use in AES-GCM-SIV:
//!
//! > "POLYVAL, like GHASH (the authenticator in AES-GCM; ...), operates in a
//! > binary field of size 2^128.  The field is defined by the irreducible
//! > polynomial x^128 + x^127 + x^126 + x^121 + 1."
//!
//! By multiplying (in the finite field sense) a sequence of 128-bit blocks of
//! input data data by a field element `H`, POLYVAL can be used to authenticate
//! the message sequence as powers (in the finite field sense) of `H`.
//!
//! # Minimum Supported Rust Version
//! Rust **1.56** or higher.
//!
//! In the future the minimum supported Rust version may be changed, but it
//! be will be accompanied with a minor version bump.
//!
//! # Supported backends
//! This crate provides multiple backends including a portable pure Rust
//! backend as well as ones based on CPU intrinsics.
//!
//! ## "soft" portable backend
//! As a baseline implementation, this crate provides a constant-time pure Rust
//! implementation based on [BearSSL], which is a straightforward and
//! compact implementation which uses a clever but simple technique to avoid
//! carry-spilling.
//!
//! ## ARMv8 intrinsics (`PMULL`, MSRV 1.61+)
//! On `aarch64` targets including `aarch64-apple-darwin` (Apple M1) and Linux
//! targets such as `aarch64-unknown-linux-gnu` and `aarch64-unknown-linux-musl`,
//! support for using the `PMULL` instructions in ARMv8's Cryptography Extensions
//! with the following `RUSTFLAGS`:
//!
//! ```text
//! --cfg polyval_armv8
//! ```
//!
//! On Linux and macOS when the ARMv8 features are enabled, support for `PMULL`  
//! intrinsics is autodetected at runtime. On other platforms the `crypto`
//! target feature must be enabled via RUSTFLAGS.
//!
//! ## `x86`/`x86_64` intrinsics (`CMLMUL`)
//! By default this crate uses runtime detection on `i686`/`x86_64` targets
//! in order to determine if `CLMUL` is available, and if it is not, it will
//! fallback to using a constant-time software implementation.
//!
//! For optimal performance, set `target-cpu` in `RUSTFLAGS` to `sandybridge`
//! or newer:
//!
//! Example:
//!
//! ```text
//! $ RUSTFLAGS="-Ctarget-cpu=sandybridge" cargo bench
//! ```
//!
//! # Relationship to GHASH
//! POLYVAL can be thought of as the little endian equivalent of GHASH, which
//! affords it a small performance advantage over GHASH when used on little
//! endian architectures.
//!
//! It has also been designed so it can also be used to compute GHASH and with
//! it GMAC, the Message Authentication Code (MAC) used by AES-GCM.
//!
//! From [RFC 8452 Appendix A]:
//!
//! > "GHASH and POLYVAL both operate in GF(2^128), although with different
//! > irreducible polynomials: POLYVAL works modulo x^128 + x^127 + x^126 +
//! > x^121 + 1 and GHASH works modulo x^128 + x^7 + x^2 + x + 1.  Note
//! > that these irreducible polynomials are the 'reverse' of each other."
//!
//! [AES-GCM-SIV]: https://en.wikipedia.org/wiki/AES-GCM-SIV
//! [AES-GCM/GMAC]: https://en.wikipedia.org/wiki/Galois/Counter_Mode
//! [BearSSL]: https://www.bearssl.org/constanttime.html#ghash-for-gcm
//! [RFC 8452 Section 3]: https://tools.ietf.org/html/rfc8452#section-3
//! [RFC 8452 Appendix A]: https://tools.ietf.org/html/rfc8452#appendix-A

#![no_std]
#![cfg_attr(docsrs, feature(doc_cfg))]
#![doc(
    html_logo_url = "https://raw.githubusercontent.com/RustCrypto/media/8f1a9894/logo.svg",
    html_favicon_url = "https://raw.githubusercontent.com/RustCrypto/media/8f1a9894/logo.svg"
)]
#![warn(missing_docs, rust_2018_idioms)]

mod backend;
mod mulx;

pub use crate::{backend::Polyval, mulx::mulx};
pub use universal_hash;

opaque_debug::implement!(Polyval);

/// Size of a POLYVAL block in bytes
pub const BLOCK_SIZE: usize = 16;

/// Size of a POLYVAL key in bytes
pub const KEY_SIZE: usize = 16;

/// POLYVAL keys (16-bytes)
pub type Key = universal_hash::Key<Polyval>;

/// POLYVAL blocks (16-bytes)
pub type Block = universal_hash::Block<Polyval>;

/// POLYVAL tags (16-bytes)
pub type Tag = universal_hash::Block<Polyval>;
