//! Signed data of signatures over keys and identities.
//!
//! RFC 9580 section 5.2.4 frames every hashed key by the signature's version.
//! rPGP 0.21 frames it by the key's version instead, which agrees only when
//! the signer and the signed key share a version. Third-party signatures may
//! cross versions, so Keyguard computes and verifies their digests here.

use pgp::{
    packet::{Signature, SignatureVersion, SignatureVersionSpecific},
    ser::Serialize,
    types::{KeyDetails, KeyVersion, Tag, VerifyingKey},
};

use super::verifier::OpenPgpVerifier;

/// Serializes a key with the prefix that a `signature_version` signature hashes.
pub(in crate::openpgp) fn signature_key_hash_data<K>(
    signature_version: SignatureVersion,
    key: &K,
) -> Option<Vec<u8>>
where
    K: KeyDetails + Serialize,
{
    if matches!(key.version(), KeyVersion::V5 | KeyVersion::Other(_)) {
        return None;
    }
    let key_len = key.write_len();
    let mut result = Vec::with_capacity(key_len.saturating_add(5));
    match signature_version {
        SignatureVersion::V2 | SignatureVersion::V3 | SignatureVersion::V4 => {
            result.push(0x99);
            result.extend_from_slice(&u16::try_from(key_len).ok()?.to_be_bytes());
        }
        SignatureVersion::V6 => {
            result.push(0x9b);
            result.extend_from_slice(&u32::try_from(key_len).ok()?.to_be_bytes());
        }
        SignatureVersion::V5 | SignatureVersion::Other(_) => return None,
    }
    key.to_writer(&mut result).ok()?;
    Some(result)
}

/// Computes the digest signed by a primary-key-only signature.
///
/// `pgp` deliberately limits its direct-key helpers to signature types 0x1F
/// and 0x20. RFC 9580 also defines type 0x30 over this same data when it
/// revokes a Direct Key signature, so that narrow case is handled here.
pub(in crate::openpgp) fn direct_key_signature_digest<K>(
    signature: &Signature,
    key: &K,
) -> Option<Vec<u8>>
where
    K: KeyDetails + Serialize,
{
    let config = signature.config()?;
    let mut hasher = config.hash_alg.new_hasher().ok()?;
    if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
        hasher.update(salt);
    }
    hasher.update(&signature_key_hash_data(config.version(), key)?);
    let signature_data_len = config.hash_signature_data(&mut hasher).ok()?;
    hasher.update(&config.trailer(signature_data_len).ok()?);
    Some(hasher.finalize().to_vec())
}

/// Computes the digest signed by a User ID or User Attribute certification.
///
/// RFC 9580 §5.2.3.33 identifies a target by the algorithms and hash of the
/// target signature itself. This is the certification's original signed-data
/// digest, not the signature-over-signature input used by type 0x50.
pub(in crate::openpgp) fn certification_signature_digest<K>(
    signature: &Signature,
    primary: &K,
    tag: Tag,
    identity: &impl Serialize,
) -> Option<Vec<u8>>
where
    K: KeyDetails + Serialize,
{
    let config = signature.config()?;
    let mut hasher = config.hash_alg.new_hasher().ok()?;
    if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
        hasher.update(salt);
    }
    hasher.update(&signature_key_hash_data(config.version(), primary)?);

    let identity_len = identity.write_len();
    let mut identity_body = Vec::with_capacity(identity_len);
    identity.to_writer(&mut identity_body).ok()?;
    if identity_body.len() != identity_len {
        return None;
    }
    match config.version_specific {
        SignatureVersionSpecific::V2 { .. } | SignatureVersionSpecific::V3 { .. } => {}
        SignatureVersionSpecific::V4 | SignatureVersionSpecific::V6 { .. } => {
            let prefix = match tag {
                Tag::UserId => 0xb4,
                Tag::UserAttribute => 0xd1,
                _ => return None,
            };
            hasher.update(&[prefix]);
            hasher.update(&u32::try_from(identity_len).ok()?.to_be_bytes());
        }
    }
    hasher.update(&identity_body);

    let signature_data_len = config.hash_signature_data(&mut hasher).ok()?;
    hasher.update(&config.trailer(signature_data_len).ok()?);
    Some(hasher.finalize().to_vec())
}

/// Verifies `signature` by `signer` over an already computed digest.
///
/// Callers establish the signature type and signer eligibility and charge
/// their verification budget first; this applies the remaining checks that
/// rPGP's verification helpers perform before the public-key operation.
pub(in crate::openpgp) fn verify_key_signature_digest<V>(
    signature: &Signature,
    signer: &V,
    digest: &[u8],
) -> bool
where
    V: KeyDetails + VerifyingKey,
{
    let Some(config) = signature.config() else {
        return false;
    };
    if config.pub_alg.is_pqc()
        && config
            .hash_alg
            .digest_size()
            .is_none_or(|size| size * 8 < 256)
    {
        return false;
    }
    let (Some(expected_prefix), Some(signature_bytes)) =
        (signature.signed_hash_value(), signature.signature())
    else {
        return false;
    };
    digest.get(..expected_prefix.len()) == Some(expected_prefix.as_slice())
        && OpenPgpVerifier(signer)
            .verify(config.hash_alg, digest, signature_bytes)
            .is_ok()
}

#[cfg(test)]
pub(crate) mod tests;
