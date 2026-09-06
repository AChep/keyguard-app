//! OpenPGP certificate generation.
//!
//! Generated keys use AWS-LC entropy and route every RSA private operation
//! through the sensitive-crypto boundary before encoding transferable material.

use std::io::{Cursor, Write};

use pgp::{
    armor::BlockType,
    composed::{SignedKeyDetails, SignedSecretKey, SignedSecretSubKey},
    crypto::{
        aead::AeadAlgorithm, ecc_curve::ECCCurve, hash::HashAlgorithm,
        public_key::PublicKeyAlgorithm, sym::SymmetricKeyAlgorithm,
    },
    packet::{
        Features, KeyFlags, PacketHeader, PubKeyInner, PublicSubkey, SecretKey, SecretSubkey,
        SignatureConfig, SignatureType, Subpacket, SubpacketData,
    },
    ser::Serialize,
    types::{
        CompressionAlgorithm, Duration, KeyDetails, KeyVersion, Password, PublicParams,
        SecretParams, SigningKey, Tag, Timestamp,
    },
};
use zeroize::Zeroizing;

use keyguard_crypto_sensitive::generate_rsa_pkcs1_der;

use crate::openpgp::{
    certificate::{KeyMaterial, UserIdCertificationBuilder, UserIdCertificationError},
    crypto::{
        secret::{AwsLcRng, AwsLcRsaSecretKey, SecretPacketRef, is_rsa_private_algorithm},
        signer::SigningKeyRef,
    },
    error::{OpenPgpWriteError, pgp_internal},
    format::{FixedCapacityWriter, fingerprint_hex},
};

use super::import::{ImportPacketError, armor_key_packets, armor_key_packets_zeroizing};
use super::{KeyGenerationInput, KeyKind};

const MAX_GENERATED_USER_ID_BYTES: usize = 16 * 1024;

/// Generates a complete v4 certificate and returns transferable key material.
pub(in crate::openpgp) fn generate_key(
    request: KeyGenerationInput,
) -> Result<KeyMaterial, OpenPgpWriteError> {
    let user_id = request.user_id.trim();
    if user_id.is_empty()
        || user_id.len() > MAX_GENERATED_USER_ID_BYTES
        || request.creation_time_epoch_seconds > u64::from(u32::MAX)
        || request.expiration_seconds == Some(0)
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let created_at = Timestamp::from_secs(request.creation_time_epoch_seconds as u32);
    let certificate = match request.kind {
        KeyKind::Unspecified => return Err(OpenPgpWriteError::InvalidArgument),
        KeyKind::LegacyEd25519X25519 => {
            generate_modern_certificate(user_id, created_at, request.expiration_seconds)?
        }
        KeyKind::Rsa => generate_rsa_certificate(
            user_id,
            created_at,
            request.expiration_seconds,
            request.rsa_bits,
        )?,
    };
    encode_key_material(&certificate)
}

fn generate_modern_certificate(
    user_id: &str,
    created_at: Timestamp,
    expiration: Option<u32>,
) -> Result<SignedSecretKey, OpenPgpWriteError> {
    let rng = AwsLcRng;
    let (primary_public, primary_secret) = pgp::composed::KeyType::Ed25519Legacy
        .generate(rng)
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let (signing_public, signing_secret) = pgp::composed::KeyType::Ed25519Legacy
        .generate(rng)
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let (encryption_public, encryption_secret) =
        pgp::composed::KeyType::ECDH(ECCCurve::Curve25519Legacy)
            .generate(rng)
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;

    let primary = secret_primary_from_params(
        PublicKeyAlgorithm::EdDSALegacy,
        created_at,
        primary_public,
        primary_secret,
    )?;
    let signing = secret_subkey_from_params(
        PublicKeyAlgorithm::EdDSALegacy,
        created_at,
        signing_public,
        signing_secret,
    )?;
    let encryption = secret_subkey_from_params(
        PublicKeyAlgorithm::ECDH,
        created_at,
        encryption_public,
        encryption_secret,
    )?;
    compose_generated_certificate(
        primary, signing, encryption, user_id, created_at, expiration,
    )
}

fn generate_rsa_certificate(
    user_id: &str,
    created_at: Timestamp,
    expiration: Option<u32>,
    bits: u32,
) -> Result<SignedSecretKey, OpenPgpWriteError> {
    if !matches!(bits, 3_072 | 4_096) {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let primary_der = generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let signing_der = generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let encryption_der =
        generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let primary = rsa_primary_from_der(&primary_der, created_at)?;
    let signing = rsa_subkey_from_der(&signing_der, created_at)?;
    let encryption = rsa_subkey_from_der(&encryption_der, created_at)?;
    compose_generated_certificate(
        primary, signing, encryption, user_id, created_at, expiration,
    )
}

/// Generates legacy-sized RSA certificate fixtures without weakening the
/// production generator's RFC 9580 minimum.
#[cfg(test)]
pub(crate) fn generate_rsa_certificate_for_test(
    user_id: &str,
    created_at: Timestamp,
    bits: u32,
) -> Result<SignedSecretKey, OpenPgpWriteError> {
    let primary_der = generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let signing_der = generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let encryption_der =
        generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let primary = rsa_primary_from_der(&primary_der, created_at)?;
    let signing = rsa_subkey_from_der(&signing_der, created_at)?;
    let encryption = rsa_subkey_from_der(&encryption_der, created_at)?;
    compose_generated_certificate(primary, signing, encryption, user_id, created_at, None)
}

fn secret_primary_from_params(
    algorithm: PublicKeyAlgorithm,
    created_at: Timestamp,
    public: PublicParams,
    secret: SecretParams,
) -> Result<SecretKey, OpenPgpWriteError> {
    let inner = PubKeyInner::new(KeyVersion::V4, algorithm, created_at, None, public)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let public =
        pgp::packet::PublicKey::from_inner(inner).map_err(|_| OpenPgpWriteError::Internal)?;
    SecretKey::new(public, secret).map_err(|_| OpenPgpWriteError::Internal)
}

fn secret_subkey_from_params(
    algorithm: PublicKeyAlgorithm,
    created_at: Timestamp,
    public: PublicParams,
    secret: SecretParams,
) -> Result<SecretSubkey, OpenPgpWriteError> {
    let inner = PubKeyInner::new(KeyVersion::V4, algorithm, created_at, None, public)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let public = PublicSubkey::from_inner(inner).map_err(|_| OpenPgpWriteError::Internal)?;
    SecretSubkey::new(public, secret).map_err(|_| OpenPgpWriteError::Internal)
}

fn rsa_primary_from_der(der: &[u8], created_at: Timestamp) -> Result<SecretKey, OpenPgpWriteError> {
    let body = rsa_secret_packet_body(der, created_at)?;
    let header = PacketHeader::new_fixed(
        Tag::SecretKey,
        u32::try_from(body.len()).map_err(|_| OpenPgpWriteError::ResourceLimit)?,
    );
    SecretKey::try_from_reader(header, Cursor::new(body.as_slice()))
        .map_err(|_| OpenPgpWriteError::Internal)
}

fn rsa_subkey_from_der(
    der: &[u8],
    created_at: Timestamp,
) -> Result<SecretSubkey, OpenPgpWriteError> {
    let body = rsa_secret_packet_body(der, created_at)?;
    let header = PacketHeader::new_fixed(
        Tag::SecretSubkey,
        u32::try_from(body.len()).map_err(|_| OpenPgpWriteError::ResourceLimit)?,
    );
    SecretSubkey::try_from_reader(header, Cursor::new(body.as_slice()))
        .map_err(|_| OpenPgpWriteError::Internal)
}

fn rsa_secret_packet_body(
    der: &[u8],
    created_at: Timestamp,
) -> Result<Zeroizing<Vec<u8>>, OpenPgpWriteError> {
    use pkcs1::der::Decode;

    let key = pkcs1::RsaPrivateKey::from_der(der).map_err(|_| OpenPgpWriteError::Internal)?;
    if key.other_prime_infos.is_some() {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let modulus = key.modulus.as_bytes();
    let public_exponent = key.public_exponent.as_bytes();
    let private_exponent = key.private_exponent.as_bytes();
    let prime_p = key.prime2.as_bytes();
    let prime_q = key.prime1.as_bytes();
    let coefficient = key.coefficient.as_bytes();

    let body_len = [
        7,
        mpi_write_len(modulus)?,
        mpi_write_len(public_exponent)?,
        mpi_write_len(private_exponent)?,
        mpi_write_len(prime_p)?,
        mpi_write_len(prime_q)?,
        mpi_write_len(coefficient)?,
        2,
    ]
    .into_iter()
    .try_fold(0_usize, |total, length| total.checked_add(length))
    .ok_or(OpenPgpWriteError::ResourceLimit)?;
    let mut body = Zeroizing::new(Vec::new());
    body.try_reserve_exact(body_len)
        .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
    let allocation = body.as_ptr();
    let capacity = body.capacity();
    {
        let mut writer = FixedCapacityWriter(&mut body);
        writer
            .write_all(&[u8::from(KeyVersion::V4)])
            .map_err(|_| OpenPgpWriteError::Internal)?;
        writer
            .write_all(&created_at.as_secs().to_be_bytes())
            .map_err(|_| OpenPgpWriteError::Internal)?;
        writer
            .write_all(&[u8::from(PublicKeyAlgorithm::RSA)])
            .map_err(|_| OpenPgpWriteError::Internal)?;
        write_mpi(&mut writer, modulus)?;
        write_mpi(&mut writer, public_exponent)?;
        writer
            .write_all(&[0])
            .map_err(|_| OpenPgpWriteError::Internal)?;
    }
    let secret_start = body.len();
    {
        let mut writer = FixedCapacityWriter(&mut body);
        write_mpi(&mut writer, private_exponent)?;
        write_mpi(&mut writer, prime_p)?;
        write_mpi(&mut writer, prime_q)?;
        write_mpi(&mut writer, coefficient)?;
    }
    let checksum = body[secret_start..]
        .iter()
        .fold(0_u16, |sum, value| sum.wrapping_add(u16::from(*value)));
    FixedCapacityWriter(&mut body)
        .write_all(&checksum.to_be_bytes())
        .map_err(|_| OpenPgpWriteError::Internal)?;
    if body.len() != body_len || body.capacity() != capacity || body.as_ptr() != allocation {
        return Err(OpenPgpWriteError::Internal);
    }
    Ok(body)
}

fn compose_generated_certificate(
    primary: SecretKey,
    signing: SecretSubkey,
    encryption: SecretSubkey,
    user_id: &str,
    created_at: Timestamp,
    expiration: Option<u32>,
) -> Result<SignedSecretKey, OpenPgpWriteError> {
    let primary_ref = SecretPacketRef::Primary(&primary);
    let signing_ref = SecretPacketRef::Subkey(&signing);
    let primary_rsa = is_rsa_private_algorithm(primary.algorithm())
        .then(|| AwsLcRsaSecretKey::new(primary_ref))
        .transpose()?;
    let signing_rsa = is_rsa_private_algorithm(signing.algorithm())
        .then(|| AwsLcRsaSecretKey::new(signing_ref))
        .transpose()?;
    let primary_signer = SigningKeyRef(
        primary_rsa
            .as_ref()
            .map_or(&primary as &dyn SigningKey, |key| key),
    );
    let signing_signer = SigningKeyRef(
        signing_rsa
            .as_ref()
            .map_or(&signing as &dyn SigningKey, |key| key),
    );
    let mut certification = SignatureConfig::v4(
        SignatureType::CertPositive,
        primary_signer.algorithm(),
        HashAlgorithm::Sha256,
    );
    let mut primary_flags = KeyFlags::default();
    primary_flags.set_certify(true);
    certification.hashed_subpackets =
        common_key_subpackets(&primary_signer, created_at, expiration, Some(primary_flags))?;
    certification
        .hashed_subpackets
        .push(Subpacket::regular(SubpacketData::IsPrimary(true)).map_err(pgp_internal)?);
    certification.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary_signer.legacy_key_id()))
            .map_err(pgp_internal)?,
    ];
    let certification = UserIdCertificationBuilder::new(
        primary_signer,
        primary.public_key(),
        user_id,
        certification,
    )
    .build()
    .map_err(map_user_id_certification_error)?;
    let user = certification.user_id;
    let certification = certification.signature;
    let password = Password::empty();

    let mut back_signature = SignatureConfig::v4(
        SignatureType::KeyBinding,
        signing_signer.algorithm(),
        HashAlgorithm::Sha256,
    );
    back_signature.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(created_at))
            .map_err(pgp_internal)?,
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            signing_signer.fingerprint(),
        ))
        .map_err(pgp_internal)?,
    ];
    back_signature.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(signing_signer.legacy_key_id()))
            .map_err(pgp_internal)?,
    ];
    let back_signature = back_signature
        .sign_primary_key_binding(
            &signing_signer,
            signing.public_key(),
            &password,
            primary.public_key(),
        )
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;

    let signing_binding = subkey_binding_signature(
        primary_signer,
        primary.public_key(),
        signing.public_key(),
        &password,
        created_at,
        expiration,
        true,
        Some(back_signature),
    )?;
    let encryption_binding = subkey_binding_signature(
        primary_signer,
        primary.public_key(),
        encryption.public_key(),
        &password,
        created_at,
        expiration,
        false,
        None,
    )?;

    Ok(SignedSecretKey::new(
        primary,
        SignedKeyDetails::new(
            Vec::new(),
            Vec::new(),
            vec![user.into_signed(certification)],
            Vec::new(),
        ),
        Vec::new(),
        vec![
            SignedSecretSubKey::new(signing, vec![signing_binding]),
            SignedSecretSubKey::new(encryption, vec![encryption_binding]),
        ],
    ))
}

const fn map_user_id_certification_error(error: UserIdCertificationError) -> OpenPgpWriteError {
    match error {
        UserIdCertificationError::InvalidUserId => OpenPgpWriteError::InvalidArgument,
        UserIdCertificationError::InvalidSignatureType
        | UserIdCertificationError::UnsupportedTemplate => OpenPgpWriteError::Internal,
        UserIdCertificationError::SigningFailed
        | UserIdCertificationError::UnsupportedSigningHash => OpenPgpWriteError::CryptoFailure,
    }
}

fn common_key_subpackets(
    signer: &dyn SigningKey,
    created_at: Timestamp,
    expiration: Option<u32>,
    flags: Option<KeyFlags>,
) -> Result<Vec<Subpacket>, OpenPgpWriteError> {
    let mut subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(created_at))
            .map_err(pgp_internal)?,
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .map_err(pgp_internal)?,
    ];
    if let Some(expiration) = expiration {
        subpackets.push(
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(
                expiration,
            )))
            .map_err(pgp_internal)?,
        );
    }
    if let Some(flags) = flags {
        subpackets.push(Subpacket::regular(SubpacketData::KeyFlags(flags)).map_err(pgp_internal)?);
    }
    subpackets.push(
        // Generated certificates are V4 for interoperability with GnuPG.
        // Advertise the matching LibrePGP/GnuPG AEAD profile, not RFC 9580
        // SEIPDv2: current GnuPG releases reject the v6 PKESK that SEIPDv2
        // requires even when the recipient key itself is V4.
        Subpacket::regular(SubpacketData::Features(Features::from(&[0x03][..])))
            .map_err(pgp_internal)?,
    );
    subpackets.push(
        Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
            vec![SymmetricKeyAlgorithm::AES256].into(),
        ))
        .map_err(pgp_internal)?,
    );
    subpackets.push(
        Subpacket::regular(SubpacketData::PreferredHashAlgorithms(
            vec![HashAlgorithm::Sha256].into(),
        ))
        .map_err(pgp_internal)?,
    );
    subpackets.push(
        Subpacket::regular(SubpacketData::PreferredCompressionAlgorithms(
            vec![CompressionAlgorithm::ZIP].into(),
        ))
        .map_err(pgp_internal)?,
    );
    subpackets.push(
        Subpacket::regular(SubpacketData::PreferredEncryptionModes(
            vec![AeadAlgorithm::Ocb].into(),
        ))
        .map_err(pgp_internal)?,
    );
    Ok(subpackets)
}

#[allow(clippy::too_many_arguments)]
pub(in crate::openpgp) fn subkey_binding_signature<K>(
    primary_signer: SigningKeyRef<'_>,
    primary_public: &pgp::packet::PublicKey,
    subkey_public: &K,
    password: &Password,
    created_at: Timestamp,
    expiration: Option<u32>,
    signing: bool,
    embedded: Option<pgp::packet::Signature>,
) -> Result<pgp::packet::Signature, OpenPgpWriteError>
where
    K: KeyDetails + Serialize,
{
    let mut flags = KeyFlags::default();
    flags.set_sign(signing);
    flags.set_encrypt_comms(!signing);
    flags.set_encrypt_storage(!signing);
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyBinding,
        primary_signer.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets =
        common_key_subpackets(&primary_signer, created_at, expiration, Some(flags))?;
    if let Some(embedded) = embedded {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::EmbeddedSignature(Box::new(embedded)))
                .map_err(pgp_internal)?,
        );
    }
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary_signer.legacy_key_id()))
            .map_err(pgp_internal)?,
    ];
    config
        .sign_subkey_binding(&primary_signer, primary_public, password, subkey_public)
        .map_err(|_| OpenPgpWriteError::CryptoFailure)
}

pub(in crate::openpgp) fn encode_key_material(
    certificate: &SignedSecretKey,
) -> Result<KeyMaterial, OpenPgpWriteError> {
    let public = certificate.to_public_key();
    let mut private_packets = Zeroizing::new(Vec::with_capacity(certificate.write_len()));
    certificate
        .to_writer(&mut *private_packets)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let mut public_packets = Vec::with_capacity(public.write_len());
    public
        .to_writer(&mut public_packets)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let private_key_armored =
        armor_key_packets_zeroizing(private_packets.as_slice(), BlockType::PrivateKey)
            .map_err(map_generated_key_armor_error)?;
    let public_key_armored = armor_key_packets(&public_packets, BlockType::PublicKey)
        .map_err(map_generated_key_armor_error)?;
    Ok(KeyMaterial {
        private_key_armored: private_key_armored.to_vec(),
        public_key_armored,
        fingerprint: fingerprint_hex(&public.primary_key),
    })
}

fn map_generated_key_armor_error(error: ImportPacketError) -> OpenPgpWriteError {
    match error {
        ImportPacketError::ResourceLimit => OpenPgpWriteError::ResourceLimit,
        ImportPacketError::Malformed
        | ImportPacketError::UnsupportedFormat
        | ImportPacketError::NeedsPassphrase
        | ImportPacketError::InvalidPassphrase
        | ImportPacketError::Internal => OpenPgpWriteError::Internal,
    }
}

fn mpi_parts(value: &[u8]) -> Result<(u16, &[u8]), OpenPgpWriteError> {
    let first_nonzero = value
        .iter()
        .position(|byte| *byte != 0)
        .ok_or(OpenPgpWriteError::InvalidArgument)?;
    let value = &value[first_nonzero..];
    let leading = value[0].leading_zeros() as usize;
    let bit_length = value
        .len()
        .checked_mul(8)
        .and_then(|bits| bits.checked_sub(leading))
        .and_then(|bits| u16::try_from(bits).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    Ok((bit_length, value))
}

fn mpi_write_len(value: &[u8]) -> Result<usize, OpenPgpWriteError> {
    let (_, value) = mpi_parts(value)?;
    value
        .len()
        .checked_add(2)
        .ok_or(OpenPgpWriteError::ResourceLimit)
}

fn write_mpi(output: &mut impl Write, value: &[u8]) -> Result<(), OpenPgpWriteError> {
    let (bit_length, value) = mpi_parts(value)?;
    output
        .write_all(&bit_length.to_be_bytes())
        .and_then(|()| output.write_all(value))
        .map_err(|_| OpenPgpWriteError::Internal)
}
