//! Concrete OpenPGP cryptographic adapters.
//!
//! Private RSA operations remain behind the sensitive-crypto boundary; this
//! module only adapts those implementations to the rPGP interfaces in use.

mod keygrip;
mod public;
pub(super) mod secret;
pub(crate) mod signer;
pub(crate) mod verification;

pub(crate) use keygrip::{algorithm_name, keygrip};
pub(crate) use public::{leading_mpi_bits, supports_decryption_key, supports_signing_key};
