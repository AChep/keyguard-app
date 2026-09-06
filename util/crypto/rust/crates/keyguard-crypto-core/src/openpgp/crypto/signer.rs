//! Shared borrowed OpenPGP signer adapters.

use pgp::{
    crypto::{hash::HashAlgorithm, public_key::PublicKeyAlgorithm},
    types::{
        Fingerprint, KeyDetails, KeyId, KeyVersion, Password, PublicParams, SignatureBytes,
        SigningKey, Timestamp,
    },
};

/// A copyable borrowed signer for rPGP APIs that require a concrete key type.
#[derive(Clone, Copy)]
pub(crate) struct SigningKeyRef<'a>(pub(crate) &'a dyn SigningKey);

impl std::fmt::Debug for SigningKeyRef<'_> {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("SigningKeyRef")
            .finish_non_exhaustive()
    }
}

impl KeyDetails for SigningKeyRef<'_> {
    fn version(&self) -> KeyVersion {
        self.0.version()
    }

    fn legacy_key_id(&self) -> KeyId {
        self.0.legacy_key_id()
    }

    fn fingerprint(&self) -> Fingerprint {
        self.0.fingerprint()
    }

    fn algorithm(&self) -> PublicKeyAlgorithm {
        self.0.algorithm()
    }

    fn created_at(&self) -> Timestamp {
        self.0.created_at()
    }

    fn legacy_v3_expiration_days(&self) -> Option<u16> {
        self.0.legacy_v3_expiration_days()
    }

    fn public_params(&self) -> &PublicParams {
        self.0.public_params()
    }
}

impl SigningKey for SigningKeyRef<'_> {
    fn sign(
        &self,
        password: &Password,
        hash: HashAlgorithm,
        data: &[u8],
    ) -> pgp::errors::Result<SignatureBytes> {
        self.0.sign(password, hash, data)
    }

    fn hash_alg(&self) -> HashAlgorithm {
        self.0.hash_alg()
    }
}

/// Selects a supported replacement hash without weakening the signer's
/// algorithm-specific digest-size floor.
///
/// In particular, RFC 9580 requires Ed448 signatures to use a digest of at
/// least 512 bits. rPGP exposes that floor through the signer's preferred hash.
pub(crate) fn select_signature_hash(
    signing_algorithm: PublicKeyAlgorithm,
    signer_hash: HashAlgorithm,
    requested: HashAlgorithm,
) -> Option<HashAlgorithm> {
    if signing_hash_compatible(signing_algorithm, signer_hash, requested) {
        return Some(requested);
    }
    signing_hash_compatible(signing_algorithm, signer_hash, signer_hash).then_some(signer_hash)
}

fn signing_hash_compatible(
    signing_algorithm: PublicKeyAlgorithm,
    signer_hash: HashAlgorithm,
    candidate: HashAlgorithm,
) -> bool {
    if !matches!(
        candidate,
        HashAlgorithm::Sha224
            | HashAlgorithm::Sha256
            | HashAlgorithm::Sha384
            | HashAlgorithm::Sha512
            | HashAlgorithm::Sha3_256
            | HashAlgorithm::Sha3_512
    ) {
        return false;
    }
    if matches!(
        signing_algorithm,
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSASign
    ) {
        // Keep RSA inside the hashes implemented by AwsLcRsaSecretKey.
        return matches!(candidate, HashAlgorithm::Sha256 | HashAlgorithm::Sha512);
    }
    if !matches!(
        signing_algorithm,
        PublicKeyAlgorithm::DSA
            | PublicKeyAlgorithm::ECDSA
            | PublicKeyAlgorithm::EdDSALegacy
            | PublicKeyAlgorithm::Ed25519
            | PublicKeyAlgorithm::Ed448
    ) {
        return false;
    }
    let Some(candidate_size) = candidate.digest_size() else {
        return false;
    };
    let Some(mut minimum_size) = signer_hash.digest_size() else {
        return false;
    };
    if signing_algorithm == PublicKeyAlgorithm::Ed448 {
        minimum_size = minimum_size.max(64);
    }
    candidate_size >= minimum_size
}

#[cfg(test)]
mod tests {
    use pgp::crypto::Signer as _;
    use rand::{SeedableRng, rngs::StdRng};

    use super::*;

    #[test]
    fn ed448_requires_a_512_bit_signature_hash() {
        assert_eq!(
            select_signature_hash(
                PublicKeyAlgorithm::Ed448,
                HashAlgorithm::Sha3_512,
                HashAlgorithm::Sha256,
            ),
            Some(HashAlgorithm::Sha3_512),
        );
        assert_eq!(
            select_signature_hash(
                PublicKeyAlgorithm::Ed448,
                HashAlgorithm::Sha3_512,
                HashAlgorithm::Sha512,
            ),
            Some(HashAlgorithm::Sha512),
        );
        assert_eq!(
            select_signature_hash(
                PublicKeyAlgorithm::Ed448,
                HashAlgorithm::Sha256,
                HashAlgorithm::Sha256,
            ),
            None,
        );
    }

    #[test]
    fn rpgp_ed448_rejects_sha256_and_signs_selected_hash() {
        let key = pgp::crypto::ed448::SecretKey::generate(StdRng::seed_from_u64(7));

        assert!(key.sign(HashAlgorithm::Sha256, &[0_u8; 32]).is_err());
        assert!(key.sign(HashAlgorithm::Sha3_512, &[0_u8; 64]).is_ok());
    }

    #[test]
    fn non_ed448_sha256_defaults_remain_unchanged() {
        for algorithm in [
            PublicKeyAlgorithm::RSA,
            PublicKeyAlgorithm::DSA,
            PublicKeyAlgorithm::ECDSA,
            PublicKeyAlgorithm::EdDSALegacy,
            PublicKeyAlgorithm::Ed25519,
        ] {
            assert_eq!(
                select_signature_hash(algorithm, HashAlgorithm::Sha256, HashAlgorithm::Sha256),
                Some(HashAlgorithm::Sha256),
            );
        }
    }
}
