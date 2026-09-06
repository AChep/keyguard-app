//! Transferable secret-key import and password removal.
//!
//! Import preserves accepted packet framing, rewrites only protected secret
//! packets, and derives the public certificate from the same bounded stream.

use std::{
    io::{Cursor, Write},
    ops::Range,
};

use pgp::{
    armor::BlockType,
    packet::{PacketHeader, PublicKey, PublicSubkey, SecretKey, SecretSubkey},
    ser::Serialize,
    types::{
        Fingerprint, KeyDetails, KeyVersion, Password, PlainSecretParams, S2kParams, SecretParams,
        Tag,
    },
};
use zeroize::Zeroizing;

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    openpgp::{
        certificate::{
            CertificateMergeError, KeyMaterial, is_gnu_dummy_secret_stub,
            local_public_certificate_preserving_framing,
        },
        crypto::secret::SecretChunks,
        error::OpenPgpWriteError,
        format::FixedCapacityWriter,
        packet::{
            FixedPacketWriteError, RawPacketError, RawPacketSpan, RawPacketStream,
            armor::{KeyArmorError, armor_key_packets_bounded},
            parse_fixed_packet_body, write_fixed_packet,
        },
    },
};

use super::{KeyImportFailureReason, KeyImportInput, KeyImportResult};

const MAX_IMPORT_COMPONENTS: usize = 64;
const MAX_IMPORT_PACKETS: usize = crate::openpgp::packet::MAX_CERTIFICATE_PACKETS;

/// Imports the first transferable secret key, removes password protection from
/// every secret component, and returns a typed domain result.
pub(in crate::openpgp) fn import_key(
    request: KeyImportInput,
) -> Result<KeyImportResult, OpenPgpWriteError> {
    let key_data = request.key_data;
    let passphrase = request.passphrase_utf8;
    if key_data.iter().all(u8::is_ascii_whitespace) {
        return Ok(KeyImportResult::Error(KeyImportFailureReason::Empty));
    }
    if key_data.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    if passphrase
        .as_deref()
        .is_some_and(|value| std::str::from_utf8(value).is_err())
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }

    let stream = match RawPacketStream::parse(key_data.as_slice(), MAX_IMPORT_PACKETS) {
        Ok(stream) => stream,
        Err(RawPacketError::ResourceLimit) => return Err(OpenPgpWriteError::ResourceLimit),
        Err(RawPacketError::Malformed) => {
            return Ok(KeyImportResult::Error(KeyImportFailureReason::MalformedKey));
        }
    };
    let Some(certificate_range) = stream.first_secret_certificate() else {
        let reason = if stream.packets().iter().any(|packet| packet.tag() == 6) {
            KeyImportFailureReason::UnsupportedFormat
        } else {
            KeyImportFailureReason::MalformedKey
        };
        return Ok(KeyImportResult::Error(reason));
    };
    let Some(selected_with_prefix) = stream.packets().get(..certificate_range.end) else {
        return Ok(KeyImportResult::Error(KeyImportFailureReason::MalformedKey));
    };
    if stream
        .validate_marker_packets(selected_with_prefix)
        .is_err()
    {
        return Ok(KeyImportResult::Error(KeyImportFailureReason::MalformedKey));
    }
    let material = match import_packet_material(
        &stream,
        certificate_range,
        passphrase.as_deref().map(Vec::as_slice),
    ) {
        Ok(material) => material,
        Err(ImportPacketError::NeedsPassphrase) => {
            return Ok(KeyImportResult::NeedsPassphrase);
        }
        Err(ImportPacketError::InvalidPassphrase) => {
            return Ok(KeyImportResult::Error(
                KeyImportFailureReason::InvalidPassphrase,
            ));
        }
        Err(ImportPacketError::UnsupportedFormat) => {
            return Ok(KeyImportResult::Error(
                KeyImportFailureReason::UnsupportedFormat,
            ));
        }
        Err(ImportPacketError::Malformed) => {
            return Ok(KeyImportResult::Error(KeyImportFailureReason::MalformedKey));
        }
        Err(ImportPacketError::ResourceLimit) => return Err(OpenPgpWriteError::ResourceLimit),
        Err(ImportPacketError::Internal) => return Err(OpenPgpWriteError::Internal),
    };
    Ok(KeyImportResult::Success(material))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum ImportPacketError {
    Malformed,
    UnsupportedFormat,
    NeedsPassphrase,
    InvalidPassphrase,
    ResourceLimit,
    Internal,
}

impl From<CertificateMergeError> for ImportPacketError {
    fn from(error: CertificateMergeError) -> Self {
        match error {
            CertificateMergeError::Malformed | CertificateMergeError::ComponentCollision => {
                Self::Malformed
            }
            CertificateMergeError::UnsupportedKeyVersion => Self::UnsupportedFormat,
            CertificateMergeError::ResourceLimit => Self::ResourceLimit,
            CertificateMergeError::Internal => Self::Internal,
        }
    }
}

enum ImportSecretPacket {
    Primary(SecretKey),
    Subkey(SecretSubkey),
}

impl ImportSecretPacket {
    fn version(&self) -> KeyVersion {
        match self {
            Self::Primary(key) => key.version(),
            Self::Subkey(key) => key.version(),
        }
    }

    fn public_len(&self) -> usize {
        match self {
            Self::Primary(key) => Serialize::write_len(key.public_key()),
            Self::Subkey(key) => Serialize::write_len(key.public_key()),
        }
    }

    fn secret_len(&self) -> usize {
        match self {
            Self::Primary(key) => Serialize::write_len(key),
            Self::Subkey(key) => Serialize::write_len(key),
        }
    }

    fn is_encrypted(&self) -> bool {
        match self {
            Self::Primary(key) => key.secret_params().is_encrypted(),
            Self::Subkey(key) => key.secret_params().is_encrypted(),
        }
    }

    fn fingerprint(&self) -> Fingerprint {
        match self {
            Self::Primary(key) => key.fingerprint(),
            Self::Subkey(key) => key.fingerprint(),
        }
    }

    fn write_public_body(&self, output: &mut Vec<u8>) -> pgp::errors::Result<()> {
        match self {
            Self::Primary(key) => key.public_key().to_writer(output),
            Self::Subkey(key) => key.public_key().to_writer(output),
        }
    }

    fn write_secret_body(&self, output: &mut impl Write) -> pgp::errors::Result<()> {
        match self {
            Self::Primary(key) => key.to_writer(output),
            Self::Subkey(key) => key.to_writer(output),
        }
    }

    fn remove_password(
        &mut self,
        password: &Password,
        original_s2k_usage: u8,
    ) -> pgp::errors::Result<()> {
        match self {
            Self::Primary(key) => {
                remove_primary_password_compatible(key, password, original_s2k_usage)
            }
            Self::Subkey(key) => {
                remove_subkey_password_compatible(key, password, original_s2k_usage)
            }
        }
    }
}

struct ParsedImportPacket<'a> {
    span: &'a RawPacketSpan,
    secret: Option<ImportSecretPacket>,
    body: Option<Zeroizing<Vec<u8>>>,
    public_len: usize,
    secret_is_absent: bool,
}

fn import_packet_material(
    stream: &RawPacketStream,
    certificate_range: Range<usize>,
    passphrase: Option<&[u8]>,
) -> Result<KeyMaterial, ImportPacketError> {
    let spans = stream
        .packets()
        .get(certificate_range)
        .ok_or(ImportPacketError::Malformed)?;
    let mut parsed = Vec::with_capacity(spans.len());
    let mut subkey_components = 0usize;
    let mut primary_version = None;
    let mut primary_fingerprint = None;
    for (position, span) in spans.iter().enumerate() {
        if !allowed_transferable_secret_tag(span.tag(), position == 0) {
            return Err(ImportPacketError::Malformed);
        }
        if matches!(span.tag(), 7 | 14) {
            subkey_components = subkey_components
                .checked_add(1)
                .filter(|count| *count <= MAX_IMPORT_COMPONENTS)
                .ok_or(ImportPacketError::ResourceLimit)?;
        }
        let secret = match span.tag() {
            5 | 7 => Some(parse_import_secret_packet(stream, span)?),
            _ => None,
        };
        let public_primary = if span.tag() == 6 {
            Some(parse_import_public_key(stream, span)?)
        } else {
            None
        };
        let public_subkey_version = if span.tag() == 14 {
            let subkey = parse_import_public_subkey(stream, span)?;
            Some(subkey.version())
        } else {
            None
        };
        let key_version = secret
            .as_ref()
            .map(ImportSecretPacket::version)
            .or_else(|| public_primary.as_ref().map(KeyDetails::version))
            .or(public_subkey_version);
        if key_version.is_some_and(|version| matches!(version, KeyVersion::V2 | KeyVersion::V3)) {
            return Err(ImportPacketError::UnsupportedFormat);
        }
        if position == 0 {
            primary_version = key_version;
            primary_fingerprint = secret
                .as_ref()
                .map(ImportSecretPacket::fingerprint)
                .or_else(|| public_primary.as_ref().map(KeyDetails::fingerprint));
        } else if matches!(
            (primary_version, key_version),
            (Some(KeyVersion::V4), Some(KeyVersion::V6))
                | (Some(KeyVersion::V6), Some(KeyVersion::V4))
        ) {
            return Err(ImportPacketError::Malformed);
        }
        let (body, public_len, secret_is_absent) = if let Some(secret) = &secret {
            let body = stream.body(span);
            let public_len = secret.public_len();
            let mut serialized_public = Vec::with_capacity(public_len);
            secret
                .write_public_body(&mut serialized_public)
                .map_err(|_| ImportPacketError::Malformed)?;
            if serialized_public.len() != public_len
                || body.get(..public_len) != Some(serialized_public.as_slice())
                || body.get(public_len).is_none()
            {
                return Err(ImportPacketError::UnsupportedFormat);
            }
            let secret_is_absent = is_gnu_dummy_secret_stub(
                secret.version(),
                body.get(public_len..).ok_or(ImportPacketError::Malformed)?,
            );
            (Some(body), public_len, secret_is_absent)
        } else {
            (None, 0, false)
        };
        parsed.push(ParsedImportPacket {
            span,
            secret,
            body,
            public_len,
            secret_is_absent,
        });
    }
    if !parsed
        .iter()
        .any(|packet| packet.secret.is_some() && !packet.secret_is_absent)
    {
        return Err(ImportPacketError::UnsupportedFormat);
    }
    if passphrase.is_none()
        && parsed.iter().any(|packet| {
            !packet.secret_is_absent
                && packet
                    .secret
                    .as_ref()
                    .is_some_and(ImportSecretPacket::is_encrypted)
        })
    {
        return Err(ImportPacketError::NeedsPassphrase);
    }

    let password = passphrase.map_or_else(Password::empty, Password::from);
    let primary_fingerprint = primary_fingerprint.ok_or(ImportPacketError::Malformed)?;
    let mut private_packet_chunks = SecretChunks::default();
    let mut public_packets = Vec::new();
    for packet in &mut parsed {
        let Some(secret) = packet.secret.as_mut() else {
            private_packet_chunks
                .push(Zeroizing::new(stream.raw(packet.span).to_vec()), usize::MAX)
                .map_err(|_| ImportPacketError::ResourceLimit)?;
            public_packets.extend_from_slice(stream.raw(packet.span));
            continue;
        };
        let body = packet.body.as_ref().ok_or(ImportPacketError::Internal)?;
        write_fixed_packet(
            public_packet_tag(packet.span.tag()),
            &body[..packet.public_len],
            &mut public_packets,
        )?;
        if packet.secret_is_absent {
            private_packet_chunks
                .push(Zeroizing::new(stream.raw(packet.span).to_vec()), usize::MAX)
                .map_err(|_| ImportPacketError::ResourceLimit)?;
            continue;
        }
        if !secret.is_encrypted() {
            private_packet_chunks
                .push(Zeroizing::new(stream.raw(packet.span).to_vec()), usize::MAX)
                .map_err(|_| ImportPacketError::ResourceLimit)?;
            continue;
        }
        let usage = body
            .get(packet.public_len)
            .copied()
            .ok_or(ImportPacketError::Malformed)?;
        secret
            .remove_password(&password, usage)
            .map_err(|_| ImportPacketError::InvalidPassphrase)?;
        let secret_len = secret.secret_len();
        let mut unlocked_body = Zeroizing::new(Vec::new());
        unlocked_body
            .try_reserve_exact(secret_len)
            .map_err(|_| ImportPacketError::ResourceLimit)?;
        let allocation = unlocked_body.as_ptr();
        let capacity = unlocked_body.capacity();
        secret
            .write_secret_body(&mut FixedCapacityWriter(&mut unlocked_body))
            .map_err(|_| ImportPacketError::Internal)?;
        if unlocked_body.len() != secret_len
            || unlocked_body.capacity() != capacity
            || unlocked_body.as_ptr() != allocation
        {
            return Err(ImportPacketError::Internal);
        }
        if unlocked_body.get(..packet.public_len) != Some(&body[..packet.public_len]) {
            return Err(ImportPacketError::UnsupportedFormat);
        }
        let suffix = unlocked_body
            .get(packet.public_len..)
            .ok_or(ImportPacketError::Internal)?;
        let preserved_len = packet
            .public_len
            .checked_add(suffix.len())
            .ok_or(ImportPacketError::ResourceLimit)?;
        let mut preserved_body = Zeroizing::new(Vec::new());
        preserved_body
            .try_reserve_exact(preserved_len)
            .map_err(|_| ImportPacketError::ResourceLimit)?;
        preserved_body.extend_from_slice(&body[..packet.public_len]);
        preserved_body.extend_from_slice(suffix);
        let private_packet = write_fixed_packet_zeroizing(packet.span.tag(), &preserved_body)?;
        private_packet_chunks
            .push(private_packet, usize::MAX)
            .map_err(|_| ImportPacketError::ResourceLimit)?;
    }

    let private_packets = private_packet_chunks
        .into_zeroizing()
        .map_err(|_| ImportPacketError::ResourceLimit)?;
    // Import is a local-state operation. Preserve unusable public components
    // for later inspection/repair while still excluding signatures whose
    // signed metadata says they must not enter a public projection.
    let public_packets = local_public_certificate_preserving_framing(&public_packets)?;
    let private_key_armored = armor_key_packets_zeroizing(&private_packets, BlockType::PrivateKey)?;
    let public_key_armored = armor_key_packets(&public_packets, BlockType::PublicKey)?;
    Ok(KeyMaterial {
        private_key_armored: private_key_armored.to_vec(),
        public_key_armored,
        fingerprint: format!("{primary_fingerprint:X}"),
    })
}

fn parse_import_secret_packet(
    stream: &RawPacketStream,
    span: &RawPacketSpan,
) -> Result<ImportSecretPacket, ImportPacketError> {
    let body = stream.body(span);
    match span.tag() {
        5 => parse_fixed_packet_body(Tag::SecretKey, body.as_slice(), |header, reader| {
            SecretKey::try_from_reader(header, reader)
        })
        .map(ImportSecretPacket::Primary),
        7 => parse_fixed_packet_body(Tag::SecretSubkey, body.as_slice(), |header, reader| {
            SecretSubkey::try_from_reader(header, reader)
        })
        .map(ImportSecretPacket::Subkey),
        _ => return Err(ImportPacketError::Malformed),
    }
    .map_err(ImportPacketError::from)
}

#[cfg(test)]
pub(in crate::openpgp) fn import_secret_packet_public_len(
    stream: &RawPacketStream,
    span: &RawPacketSpan,
) -> Result<usize, ImportPacketError> {
    parse_import_secret_packet(stream, span).map(|packet| packet.public_len())
}

fn parse_import_public_subkey(
    stream: &RawPacketStream,
    span: &RawPacketSpan,
) -> Result<PublicSubkey, ImportPacketError> {
    let body = stream.body(span);
    parse_fixed_packet_body(Tag::PublicSubkey, body.as_slice(), |header, reader| {
        PublicSubkey::try_from_reader(header, reader)
    })
    .map_err(ImportPacketError::from)
}

fn parse_import_public_key(
    stream: &RawPacketStream,
    span: &RawPacketSpan,
) -> Result<PublicKey, ImportPacketError> {
    let body = stream.body(span);
    parse_fixed_packet_body(Tag::PublicKey, body.as_slice(), |header, reader| {
        PublicKey::try_from_reader(header, reader)
    })
    .map_err(ImportPacketError::from)
}

fn allowed_transferable_secret_tag(tag: u8, is_first: bool) -> bool {
    match tag {
        5 | 6 => is_first,
        2 | 7 | 10 | 12 | 13 | 14 | 17 | 21 | 40..=63 => !is_first,
        _ => false,
    }
}

fn public_packet_tag(secret_tag: u8) -> u8 {
    match secret_tag {
        5 => 6,
        7 => 14,
        _ => secret_tag,
    }
}

fn write_fixed_packet_zeroizing(
    tag: u8,
    body: &[u8],
) -> Result<Zeroizing<Vec<u8>>, ImportPacketError> {
    let length = u32::try_from(body.len()).map_err(|_| ImportPacketError::ResourceLimit)?;
    let header = PacketHeader::new_fixed(Tag::from(tag), length);
    let output_len = header
        .write_len()
        .checked_add(body.len())
        .ok_or(ImportPacketError::ResourceLimit)?;
    let mut output = Zeroizing::new(Vec::new());
    output
        .try_reserve_exact(output_len)
        .map_err(|_| ImportPacketError::ResourceLimit)?;
    let allocation = output.as_ptr();
    let capacity = output.capacity();
    {
        let mut writer = FixedCapacityWriter(&mut output);
        header
            .to_writer(&mut writer)
            .map_err(|_| ImportPacketError::Internal)?;
        writer
            .write_all(body)
            .map_err(|_| ImportPacketError::Internal)?;
    }
    if output.len() != output_len || output.capacity() != capacity || output.as_ptr() != allocation
    {
        return Err(ImportPacketError::Internal);
    }
    Ok(output)
}

impl From<FixedPacketWriteError> for ImportPacketError {
    fn from(error: FixedPacketWriteError) -> Self {
        match error {
            FixedPacketWriteError::ResourceLimit => Self::ResourceLimit,
            FixedPacketWriteError::Internal => Self::Internal,
        }
    }
}

impl From<RawPacketError> for ImportPacketError {
    fn from(error: RawPacketError) -> Self {
        match error {
            RawPacketError::Malformed => Self::Malformed,
            RawPacketError::ResourceLimit => Self::ResourceLimit,
        }
    }
}

pub(in crate::openpgp) fn armor_key_packets(
    packets: &[u8],
    block_type: BlockType,
) -> Result<Vec<u8>, ImportPacketError> {
    armor_key_packets_zeroizing(packets, block_type).map(|output| output.to_vec())
}

pub(super) fn armor_key_packets_zeroizing(
    packets: &[u8],
    block_type: BlockType,
) -> Result<Zeroizing<Vec<u8>>, ImportPacketError> {
    armor_key_packets_bounded(packets, block_type, MAX_IMPORT_PACKETS, usize::MAX).map_err(
        |error| match error {
            KeyArmorError::Malformed => ImportPacketError::Malformed,
            KeyArmorError::ResourceLimit => ImportPacketError::ResourceLimit,
            KeyArmorError::UnsupportedVersion => ImportPacketError::UnsupportedFormat,
            KeyArmorError::Internal => ImportPacketError::Internal,
        },
    )
}

fn remove_primary_password_compatible(
    key: &mut SecretKey,
    password: &Password,
    original_s2k_usage: u8,
) -> pgp::errors::Result<()> {
    if original_s2k_usage != 255 {
        return key.remove_password(password);
    }
    let plain = unlock_malleable_cfb(key.secret_params(), key.public_key(), password)?;
    *key = SecretKey::new(key.public_key().clone(), SecretParams::Plain(plain))?;
    Ok(())
}

fn remove_subkey_password_compatible(
    key: &mut SecretSubkey,
    password: &Password,
    original_s2k_usage: u8,
) -> pgp::errors::Result<()> {
    if original_s2k_usage != 255 {
        return key.remove_password(password);
    }
    let plain = unlock_malleable_cfb(key.secret_params(), key.public_key(), password)?;
    *key = SecretSubkey::new(key.public_key().clone(), SecretParams::Plain(plain))?;
    Ok(())
}

fn unlock_malleable_cfb<K>(
    secret_params: &SecretParams,
    public: &K,
    password: &Password,
) -> pgp::errors::Result<PlainSecretParams>
where
    K: KeyDetails + Serialize,
{
    let SecretParams::Encrypted(encrypted) = secret_params else {
        return Err("inconsistent protected OpenPGP key".to_owned().into());
    };
    let S2kParams::Cfb { sym_alg, s2k, iv } = encrypted.string_to_key_params() else {
        return Err("inconsistent legacy OpenPGP protection".to_owned().into());
    };
    let derived = s2k.derive_key(&password.read(), sym_alg.key_size())?;
    let mut plaintext = Zeroizing::new(encrypted.data().to_vec());
    sym_alg.decrypt_with_iv_regular(derived.as_ref(), iv, &mut plaintext)?;
    PlainSecretParams::try_from_reader(
        Cursor::new(plaintext.as_slice()),
        public.version(),
        public.algorithm(),
        public.public_params(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::openpgp::packet::MARKER_TAG;

    const SECRET_KEY: &[u8] = include_bytes!("../../../tests/fixtures/openpgp/cv25519-secret.asc");

    fn marker_packet(body: &[u8]) -> Vec<u8> {
        let mut marker = Vec::new();
        write_fixed_packet(MARKER_TAG, body, &mut marker).expect("write Marker packet");
        marker
    }

    fn old_format_marker_packet(body: &[u8]) -> Vec<u8> {
        let length = u8::try_from(body.len()).expect("test Marker body fits old-format length");
        let mut marker = Vec::with_capacity(2 + body.len());
        marker.extend_from_slice(&[0xa8, length]);
        marker.extend_from_slice(body);
        marker
    }

    fn secret_document_with_marker(marker: &[u8], leading: bool) -> Vec<u8> {
        let fixture = RawPacketStream::parse(SECRET_KEY, MAX_IMPORT_PACKETS)
            .expect("parse secret fixture packet stream");
        let mut document = Vec::with_capacity(fixture.bytes().len() + marker.len());
        if leading {
            document.extend_from_slice(marker);
        }
        for (index, packet) in fixture.packets().iter().enumerate() {
            document.extend_from_slice(fixture.raw(packet));
            if !leading && index == 0 {
                document.extend_from_slice(marker);
            }
        }
        document
    }

    fn import_test_key(key_data: Vec<u8>) -> KeyImportResult {
        import_key(KeyImportInput {
            key_data: Zeroizing::new(key_data),
            passphrase_utf8: None,
        })
        .expect("import operation completes")
    }

    fn assert_import_succeeds(key_data: Vec<u8>, case: &str) {
        assert!(
            matches!(import_test_key(key_data), KeyImportResult::Success(_)),
            "{case}",
        );
    }

    fn assert_import_is_malformed(key_data: Vec<u8>, case: &str) {
        assert!(
            matches!(
                import_test_key(key_data),
                KeyImportResult::Error(KeyImportFailureReason::MalformedKey)
            ),
            "{case}",
        );
    }

    #[test]
    fn secret_import_accepts_exact_leading_and_interior_markers() {
        let old_leading = secret_document_with_marker(&old_format_marker_packet(b"PGP"), true);
        assert_import_succeeds(old_leading.clone(), "raw old-format leading Marker");
        assert_import_succeeds(
            armor_key_packets(&old_leading, BlockType::PrivateKey)
                .expect("armor valid leading Marker"),
            "armored old-format leading Marker",
        );

        let new_interior = secret_document_with_marker(&marker_packet(b"PGP"), false);
        assert_import_succeeds(new_interior, "raw new-format interior Marker");
    }

    #[test]
    fn secret_import_rejects_malformed_markers_in_raw_and_armored_keys() {
        assert_import_is_malformed(
            secret_document_with_marker(&old_format_marker_packet(b""), true),
            "empty leading Marker",
        );
        assert_import_is_malformed(
            secret_document_with_marker(&marker_packet(b"PGX"), false),
            "wrong interior Marker",
        );

        let long = secret_document_with_marker(&marker_packet(b"PGPX"), false);
        let armored = armor_key_packets(&long, BlockType::PrivateKey)
            .expect("armor malformed Marker for import validation");
        assert_import_is_malformed(armored, "long armored interior Marker");
    }
}
