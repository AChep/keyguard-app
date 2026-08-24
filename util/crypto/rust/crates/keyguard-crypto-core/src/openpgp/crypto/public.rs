//! Concrete public-key capability predicates.
//!
//! These predicates describe only keys implemented by the concrete private-
//! operation adapters. Policy decides whether a supported key is acceptable
//! for a particular operation and time.

use pgp::{
    crypto::{ecc_curve::ECCCurve, public_key::PublicKeyAlgorithm},
    ser::Serialize,
    types::PublicParams,
};

/// Bit length declared by the leading MPI of the serialized public
/// parameters — the modulus for RSA, DSA, and Elgamal keys. Returns `None`
/// when the parameters fail to serialize or carry no MPI prefix.
pub(crate) fn leading_mpi_bits(params: &PublicParams) -> Option<u16> {
    let mut encoded = Vec::with_capacity(params.write_len());
    params.to_writer(&mut encoded).ok()?;
    Some(u16::from_be_bytes([*encoded.first()?, *encoded.get(1)?]))
}

/// Keys supported by the concrete private signing dispatch.
pub(crate) fn supports_signing_key(algorithm: PublicKeyAlgorithm, params: &PublicParams) -> bool {
    if algorithm == PublicKeyAlgorithm::ECDSA {
        return matches!(
            params,
            PublicParams::ECDSA(params)
                if matches!(
                    params.curve(),
                    ECCCurve::P256
                        | ECCCurve::P384
                        | ECCCurve::P521
                        | ECCCurve::Secp256k1
                )
        );
    }

    matches!(
        algorithm,
        PublicKeyAlgorithm::RSA
            | PublicKeyAlgorithm::RSASign
            | PublicKeyAlgorithm::EdDSALegacy
            | PublicKeyAlgorithm::Ed25519
    )
}

/// Keys supported by the concrete private decryption dispatch.
pub(crate) fn supports_decryption_key(
    algorithm: PublicKeyAlgorithm,
    params: &PublicParams,
) -> bool {
    if algorithm == PublicKeyAlgorithm::ECDH {
        return matches!(
            params,
            PublicParams::ECDH(params)
                if matches!(
                    params.curve(),
                    ECCCurve::Curve25519Legacy
                        | ECCCurve::P256
                        | ECCCurve::P384
                        | ECCCurve::P521
                )
        );
    }

    matches!(
        algorithm,
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
    )
}
