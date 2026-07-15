#![no_std]
#![cfg_attr(docsrs, feature(doc_auto_cfg))]
#![doc = include_str!("../README.md")]
#![doc(
    html_logo_url = "https://raw.githubusercontent.com/RustCrypto/media/6ee8e381/logo.svg",
    html_favicon_url = "https://raw.githubusercontent.com/RustCrypto/media/6ee8e381/logo.svg"
)]
#![forbid(unsafe_code)]
#![warn(
    clippy::arithmetic_side_effects,
    clippy::mod_module_files,
    clippy::panic,
    clippy::panic_in_result_fn,
    clippy::unwrap_used,
    missing_docs,
    rust_2018_idioms,
    unused_lifetimes,
    unused_qualifications
)]

//! ## Usage
//!
//! The main types provided by this library are:
//!
//! - [`Certificate`]: OpenSSH certificates
//! - [`Fingerprint`]: public key fingerprints (i.e. hashes)
//! - [`PrivateKey`]: SSH private keys (i.e. digital signature keys)
//! - [`PublicKey`]: SSH public keys (i.e. signature verification keys)
//! - [`SshSig`]: signatures with SSH keys ala `ssh-keygen -Y sign`/`ssh-keygen -Y verify`
//!
//! ### Parsing OpenSSH Public Keys
//!
//! OpenSSH-formatted public keys have the form:
//!
//! ```text
//! <algorithm id> <base64 data> <comment>
//! ```
//!
//! #### Example
//!
#![cfg_attr(feature = "std", doc = "```")]
#![cfg_attr(not(feature = "std"), doc = "```ignore")]
//! # fn main() -> Result<(), Box<dyn std::error::Error>> {
//! use ssh_key::PublicKey;
//!
//! let encoded_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti user@example.com";
//! let public_key = PublicKey::from_openssh(encoded_key)?;
//!
//! // Key attributes
//! assert_eq!(public_key.algorithm(), ssh_key::Algorithm::Ed25519);
//! assert_eq!(public_key.comment(), "user@example.com");
//!
//! // Key data: in this example an Ed25519 key
//! if let Some(ed25519_public_key) = public_key.key_data().ed25519() {
//!     assert_eq!(
//!         ed25519_public_key.as_ref(),
//!         [
//!             0xb3, 0x3e, 0xae, 0xf3, 0x7e, 0xa2, 0xdf, 0x7c, 0xaa, 0x1, 0xd, 0xef, 0xde, 0xa3,
//!             0x4e, 0x24, 0x1f, 0x65, 0xf1, 0xb5, 0x29, 0xa4, 0xf4, 0x3e, 0xd1, 0x43, 0x27, 0xf5,
//!             0xc5, 0x4a, 0xab, 0x62
//!         ].as_ref()
//!     );
//! }
//! # Ok(())
//! # }
//! ```
//!
//! ### Parsing OpenSSH Private Keys
//!
//! *NOTE: for more private key usage examples, see the [`private`] module.*
//!
//! OpenSSH-formatted private keys are PEM-encoded and begin with the following:
//!
//! ```text
//! -----BEGIN OPENSSH PRIVATE KEY-----
//! ```
//!
//! #### Example
//!
#![cfg_attr(feature = "std", doc = " ```")]
#![cfg_attr(not(feature = "std"), doc = " ```ignore")]
//! # fn main() -> Result<(), Box<dyn std::error::Error>> {
//! use ssh_key::PrivateKey;
//!
//! // WARNING: don't actually hardcode private keys in source code!!!
//! let encoded_key = r#"
//! -----BEGIN OPENSSH PRIVATE KEY-----
//! b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
//! QyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYgAAAJgAIAxdACAM
//! XQAAAAtzc2gtZWQyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYg
//! AAAEC2BsIi0QwW2uFscKTUUXNHLsYX4FxlaSDSblbAj7WR7bM+rvN+ot98qgEN796jTiQf
//! ZfG1KaT0PtFDJ/XFSqtiAAAAEHVzZXJAZXhhbXBsZS5jb20BAgMEBQ==
//! -----END OPENSSH PRIVATE KEY-----
//! "#;
//!
//! let private_key = PrivateKey::from_openssh(encoded_key)?;
//!
//! // Key attributes
//! assert_eq!(private_key.algorithm(), ssh_key::Algorithm::Ed25519);
//! assert_eq!(private_key.comment(), "user@example.com");
//!
//! // Key data: in this example an Ed25519 key
//! if let Some(ed25519_keypair) = private_key.key_data().ed25519() {
//!     assert_eq!(
//!         ed25519_keypair.public.as_ref(),
//!         [
//!             0xb3, 0x3e, 0xae, 0xf3, 0x7e, 0xa2, 0xdf, 0x7c, 0xaa, 0x1, 0xd, 0xef, 0xde, 0xa3,
//!             0x4e, 0x24, 0x1f, 0x65, 0xf1, 0xb5, 0x29, 0xa4, 0xf4, 0x3e, 0xd1, 0x43, 0x27, 0xf5,
//!             0xc5, 0x4a, 0xab, 0x62
//!         ].as_ref()
//!     );
//!
//!     assert_eq!(
//!         ed25519_keypair.private.as_ref(),
//!         [
//!             0xb6, 0x6, 0xc2, 0x22, 0xd1, 0xc, 0x16, 0xda, 0xe1, 0x6c, 0x70, 0xa4, 0xd4, 0x51,
//!             0x73, 0x47, 0x2e, 0xc6, 0x17, 0xe0, 0x5c, 0x65, 0x69, 0x20, 0xd2, 0x6e, 0x56, 0xc0,
//!             0x8f, 0xb5, 0x91, 0xed
//!         ].as_ref()
//!     )
//! }
//! # Ok(())
//! # }
//! ```
//!
//! ## `serde` support
//!
//! When the `serde` feature of this crate is enabled, the [`Certificate`],
//! [`Fingerprint`], and [`PublicKey`] types receive impls of `serde`'s
//! [`Deserialize`][`serde::Deserialize`] and [`Serialize`][`serde::Serialize`]
//! traits.
//!
//! Serializing/deserializing [`PrivateKey`] using `serde` is presently
//! unsupported.

#[cfg(feature = "alloc")]
#[macro_use]
extern crate alloc;
#[cfg(feature = "std")]
extern crate std;

pub mod authorized_keys;
pub mod private;
pub mod public;

#[cfg(feature = "alloc")]
pub mod certificate;
#[cfg(feature = "alloc")]
pub mod known_hosts;

mod algorithm;
mod error;
mod fingerprint;
mod kdf;

#[cfg(feature = "alloc")]
mod mpint;
#[cfg(feature = "alloc")]
mod signature;
#[cfg(feature = "alloc")]
mod sshsig;

pub use crate::{
    algorithm::{Algorithm, EcdsaCurve, HashAlg, KdfAlg},
    authorized_keys::AuthorizedKeys,
    error::{Error, Result},
    fingerprint::Fingerprint,
    kdf::Kdf,
    private::PrivateKey,
    public::PublicKey,
};
pub use cipher::Cipher;
pub use encoding::LineEnding;
pub use sha2;

#[cfg(feature = "alloc")]
pub use crate::{
    algorithm::AlgorithmName,
    certificate::Certificate,
    known_hosts::KnownHosts,
    mpint::Mpint,
    signature::{Signature, SigningKey},
    sshsig::SshSig,
};

#[cfg(feature = "ecdsa")]
pub use sec1;

#[cfg(feature = "rand_core")]
pub use rand_core;
