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

use pgp::{
    ser::Serialize,
    types::{KeyDetails, KeyVersion},
};

/// Serializes a key with the version-specific prefix used in signature hashes.
pub(in crate::openpgp) fn signature_key_hash_data<K>(key: &K) -> Option<Vec<u8>>
where
    K: KeyDetails + Serialize,
{
    let key_len = key.write_len();
    let mut result = Vec::with_capacity(key_len.saturating_add(5));
    match key.version() {
        KeyVersion::V2 | KeyVersion::V3 | KeyVersion::V4 => {
            result.push(0x99);
            result.extend_from_slice(&u16::try_from(key_len).ok()?.to_be_bytes());
        }
        KeyVersion::V6 => {
            result.push(0x9b);
            result.extend_from_slice(&u32::try_from(key_len).ok()?.to_be_bytes());
        }
        KeyVersion::V5 | KeyVersion::Other(_) => return None,
    }
    key.to_writer(&mut result).ok()?;
    Some(result)
}
