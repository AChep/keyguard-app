//! Public key components retained by a certificate.
//!
//! A component identifies packet material only. Whether it is authenticated,
//! revoked, expired, or usable belongs to the policy layer.

use pgp::{
    crypto::hash::HashAlgorithm,
    crypto::public_key::PublicKeyAlgorithm,
    packet::{PublicKey, PublicSubkey},
    types::{
        EncryptionKey, EskType, Fingerprint, KeyDetails, KeyId, KeyVersion, PkeskBytes,
        PublicParams, SignatureBytes, Timestamp, VerifyingKey,
    },
};
use rand::CryptoRng;

use crate::openpgp::format::fingerprint_hex;

#[derive(Clone)]
pub(crate) enum PublicComponent {
    Primary(PublicKey),
    Subkey(PublicSubkey),
}

impl PublicComponent {
    pub(crate) fn fingerprint_hex(&self) -> String {
        fingerprint_hex(self)
    }
}

impl std::fmt::Debug for PublicComponent {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PublicComponent")
            .field("fingerprint", &self.fingerprint_hex())
            .finish_non_exhaustive()
    }
}

impl KeyDetails for PublicComponent {
    fn version(&self) -> KeyVersion {
        match self {
            Self::Primary(key) => key.version(),
            Self::Subkey(key) => key.version(),
        }
    }

    fn legacy_key_id(&self) -> KeyId {
        match self {
            Self::Primary(key) => key.legacy_key_id(),
            Self::Subkey(key) => key.legacy_key_id(),
        }
    }

    fn fingerprint(&self) -> Fingerprint {
        match self {
            Self::Primary(key) => key.fingerprint(),
            Self::Subkey(key) => key.fingerprint(),
        }
    }

    fn algorithm(&self) -> PublicKeyAlgorithm {
        match self {
            Self::Primary(key) => key.algorithm(),
            Self::Subkey(key) => key.algorithm(),
        }
    }

    fn created_at(&self) -> Timestamp {
        match self {
            Self::Primary(key) => key.created_at(),
            Self::Subkey(key) => key.created_at(),
        }
    }

    fn legacy_v3_expiration_days(&self) -> Option<u16> {
        match self {
            Self::Primary(key) => key.legacy_v3_expiration_days(),
            Self::Subkey(key) => key.legacy_v3_expiration_days(),
        }
    }

    fn public_params(&self) -> &PublicParams {
        match self {
            Self::Primary(key) => key.public_params(),
            Self::Subkey(key) => key.public_params(),
        }
    }
}

// These rPGP adapters expose the retained primary-key or subkey packet. They
// add no authentication, revocation, expiration, or algorithm policy.
impl EncryptionKey for PublicComponent {
    fn encrypt<R: CryptoRng + rand::Rng>(
        &self,
        rng: R,
        plain: &[u8],
        typ: EskType,
    ) -> pgp::errors::Result<PkeskBytes> {
        match self {
            Self::Primary(key) => key.encrypt(rng, plain, typ),
            Self::Subkey(key) => key.encrypt(rng, plain, typ),
        }
    }
}

impl VerifyingKey for PublicComponent {
    fn verify(
        &self,
        hash: HashAlgorithm,
        data: &[u8],
        signature: &SignatureBytes,
    ) -> pgp::errors::Result<()> {
        match self {
            Self::Primary(key) => key.verify(hash, data, signature),
            Self::Subkey(key) => key.verify(hash, data, signature),
        }
    }
}
