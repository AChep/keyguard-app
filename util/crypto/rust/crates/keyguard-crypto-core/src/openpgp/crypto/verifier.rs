//! Borrowed OpenPGP verifier with secp256k1 signature interoperability.

use std::{borrow::Cow, io};

use pgp::{
    crypto::{hash::HashAlgorithm, public_key::PublicKeyAlgorithm},
    ser::Serialize,
    types::{
        EcdsaPublicParams, Fingerprint, KeyDetails, KeyId, KeyVersion, Mpi, PublicParams,
        SignatureBytes, Timestamp, VerifyingKey,
    },
};

/// Adapts rPGP verification without changing the retained signature packets.
///
/// GnuPG accepts both ECDSA S representatives. rPGP 0.20 forwards secp256k1
/// signatures to k256, which accepts only low-S. Normalize a temporary copy
/// at the public-key operation boundary, leaving hashing, issuer checks,
/// certificate policy, and wire preservation to their existing owners.
#[derive(Debug)]
pub(crate) struct OpenPgpVerifier<'a, K: ?Sized>(pub(crate) &'a K);

impl<K: KeyDetails + ?Sized> KeyDetails for OpenPgpVerifier<'_, K> {
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

impl<K: Serialize + ?Sized> Serialize for OpenPgpVerifier<'_, K> {
    fn to_writer<W: io::Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        self.0.to_writer(writer)
    }

    fn write_len(&self) -> usize {
        self.0.write_len()
    }
}

impl<K: VerifyingKey + ?Sized> VerifyingKey for OpenPgpVerifier<'_, K> {
    fn verify(
        &self,
        hash: HashAlgorithm,
        digest: &[u8],
        signature: &SignatureBytes,
    ) -> pgp::errors::Result<()> {
        let signature = if matches!(
            (self.algorithm(), self.public_params()),
            (
                PublicKeyAlgorithm::ECDSA,
                PublicParams::ECDSA(EcdsaPublicParams::Secp256k1 { .. })
            )
        ) {
            normalize_secp256k1(signature)?
        } else {
            Cow::Borrowed(signature)
        };
        self.0.verify(hash, digest, signature.as_ref())
    }
}

fn normalize_secp256k1(signature: &SignatureBytes) -> pgp::errors::Result<Cow<'_, SignatureBytes>> {
    let SignatureBytes::Mpis(mpis) = signature else {
        return Err("invalid secp256k1 signature encoding".to_owned().into());
    };
    let [r, s] = mpis.as_slice() else {
        return Err("invalid secp256k1 signature component count"
            .to_owned()
            .into());
    };
    let mut bytes = [0_u8; 64];
    for (field, mpi) in bytes.as_chunks_mut::<32>().0.iter_mut().zip([r, s]) {
        let value: &[u8] = mpi.as_ref();
        if value.len() > field.len() {
            return Err("invalid secp256k1 signature component length"
                .to_owned()
                .into());
        }
        let offset = field.len() - value.len();
        field[offset..].copy_from_slice(value);
    }
    // Parsing rejects zero and out-of-range scalars before normalization.
    let parsed = k256::ecdsa::Signature::from_slice(&bytes)?;
    let Some(normalized) = parsed.normalize_s() else {
        return Ok(Cow::Borrowed(signature));
    };
    let (r, s) = normalized.split_bytes();
    Ok(Cow::Owned(SignatureBytes::Mpis(vec![
        Mpi::from_slice(&r),
        Mpi::from_slice(&s),
    ])))
}

#[cfg(test)]
pub(crate) mod tests;
