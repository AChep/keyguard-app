//! Neutral helpers shared by OpenPGP message write workflows.
//!
//! Parsing, legacy-version detection, and error classification remain free of
//! operation-specific buffering or session state.

use super::*;

pub(super) fn parse_private_certificate(
    input: &[u8],
) -> Result<ParsedSecretCertificate, OpenPgpWriteError> {
    match parse_secret_certificate(input) {
        Ok(secret) => {
            if secret.public().public_subkeys.len() > MAX_OPENPGP_COMPONENTS {
                return Err(OpenPgpWriteError::ResourceLimit);
            }
            Ok(secret)
        }
        Err(MutationMaterialError::UnsupportedKeyVersion) => {
            // Preserve the established version-specific ABI for legacy full
            // secret keys.  Mixed certificates are rejected by the shared
            // packet parser before they can be represented by rPGP.
            let version = parse_secret_key(input).err().and_then(|error| match error {
                OpenPgpWriteError::UnsupportedKeyVersion(version) => Some(version),
                _ => None,
            });
            Err(version.map_or(
                OpenPgpWriteError::InvalidArgument,
                OpenPgpWriteError::UnsupportedKeyVersion,
            ))
        }
        Err(error) => Err(OpenPgpWriteError::from(error)),
    }
}

pub(super) fn parse_secret_key(input: &[u8]) -> Result<SignedSecretKey, OpenPgpWriteError> {
    let packets = RawPacketStream::parse(input, MAX_OPENPGP_PACKETS)?;
    let semantic = packets.semantic_bytes();
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(semantic.as_slice()))
        .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
    if let Some(version) = legacy_secret_version(&secret) {
        return Err(OpenPgpWriteError::UnsupportedKeyVersion(version));
    }
    if secret.public_subkeys.len() + secret.secret_subkeys.len() > MAX_OPENPGP_COMPONENTS {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    Ok(secret)
}

pub(super) fn legacy_secret_version(secret: &SignedSecretKey) -> Option<u8> {
    std::iter::once(secret.primary_key.version())
        .chain(
            secret
                .public_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .chain(
            secret
                .secret_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .find_map(legacy_version_number)
}

pub(super) fn legacy_public_version(certificate: &SignedPublicKey) -> Option<u8> {
    std::iter::once(certificate.primary_key.version())
        .chain(
            certificate
                .public_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .find_map(legacy_version_number)
}

pub(super) fn legacy_version_number(version: KeyVersion) -> Option<u8> {
    match version {
        KeyVersion::V2 => Some(2),
        KeyVersion::V3 => Some(3),
        _ => None,
    }
}

pub(super) fn map_read_error(
    error: crate::openpgp::message::OpenPgpReadError,
) -> OpenPgpWriteError {
    match error {
        crate::openpgp::message::OpenPgpReadError::InvalidArgument => {
            OpenPgpWriteError::InvalidArgument
        }
        crate::openpgp::message::OpenPgpReadError::ResourceLimit => {
            OpenPgpWriteError::ResourceLimit
        }
        crate::openpgp::message::OpenPgpReadError::Internal => OpenPgpWriteError::Internal,
    }
}

pub(super) fn parse_policy_candidates(
    documents: &[Vec<u8>],
) -> Result<Vec<SignedPublicKey>, OpenPgpWriteError> {
    parse_mutation_candidates(documents).map_err(OpenPgpWriteError::from)
}

impl From<MutationMaterialError> for OpenPgpWriteError {
    fn from(error: MutationMaterialError) -> Self {
        match error.severity() {
            MaterialErrorSeverity::ResourceLimit => Self::ResourceLimit,
            MaterialErrorSeverity::InvalidArgument => Self::InvalidArgument,
            MaterialErrorSeverity::Internal => Self::Internal,
        }
    }
}

pub(super) fn map_policy_error(error: OpenPgpPolicyError) -> OpenPgpWriteError {
    match error {
        OpenPgpPolicyError::ResourceLimit | OpenPgpPolicyError::RequestResourceLimit => {
            OpenPgpWriteError::ResourceLimit
        }
        OpenPgpPolicyError::Internal => OpenPgpWriteError::Internal,
    }
}
