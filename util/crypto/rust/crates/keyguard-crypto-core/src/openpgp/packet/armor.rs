//! Version-driven ASCII-armor checksum policy and bounded output helpers.

use std::io::{self, Write};

use crate::openpgp::format::FixedCapacityWriter;

use pgp::{
    armor::{self, BlockType, Headers},
    composed::ArmorOptions,
    packet::SignatureVersion,
    ser::Serialize,
    types::KeyVersion,
};
use zeroize::Zeroizing;

use super::{
    stream::RawPacketStream,
    types::{
        PUBLIC_KEY_TAG, PUBLIC_SUBKEY_TAG, RawPacketError, SECRET_KEY_TAG, SECRET_SUBKEY_TAG,
        SIGNATURE_TAG,
    },
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BufferedArmorError {
    ResourceLimit,
    Internal,
}

/// Already-framed packet bytes exposed through the serializer interface.
pub(crate) struct RawPackets<'a>(pub(crate) &'a [u8]);

impl Serialize for RawPackets<'_> {
    fn to_writer<W: Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        writer.write_all(self.0)?;
        Ok(())
    }

    fn write_len(&self) -> usize {
        self.0.len()
    }
}

#[derive(Default)]
struct CountingWriter {
    length: usize,
    overflowed: bool,
}

impl Write for CountingWriter {
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        let Some(length) = self.length.checked_add(buffer.len()) else {
            self.overflowed = true;
            return Err(io::Error::other("armored output length overflow"));
        };
        self.length = length;
        Ok(buffer.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Encodes armor twice: once into a counter and once into a fixed-capacity,
/// zeroizing allocation. The second pass cannot reallocate after secret bytes
/// have been written, even if the serializer emits an unexpected length.
pub(crate) fn write_zeroizing_armor(
    source: &impl Serialize,
    block_type: BlockType,
    headers: Option<&Headers>,
    include_checksum: bool,
    limit: usize,
) -> Result<Zeroizing<Vec<u8>>, BufferedArmorError> {
    let mut counter = CountingWriter::default();
    let counted = armor::write(source, block_type, &mut counter, headers, include_checksum);
    if counter.overflowed || counter.length > limit {
        return Err(BufferedArmorError::ResourceLimit);
    }
    counted.map_err(|_| BufferedArmorError::Internal)?;

    let mut output = Zeroizing::new(Vec::new());
    output
        .try_reserve_exact(counter.length)
        .map_err(|_| BufferedArmorError::ResourceLimit)?;
    let allocation = output.as_ptr();
    let capacity = output.capacity();
    armor::write(
        source,
        block_type,
        &mut FixedCapacityWriter(&mut output),
        headers,
        include_checksum,
    )
    .map_err(|_| BufferedArmorError::Internal)?;
    if output.len() != counter.length
        || output.capacity() != capacity
        || output.as_ptr() != allocation
    {
        return Err(BufferedArmorError::Internal);
    }
    Ok(output)
}

/// A key or signature version that cannot be emitted under the supported
/// OpenPGP armor policy.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct UnsupportedArmorVersion;

/// Resolves the checksum bit for a transferable key block.
///
/// Every supplied version is inspected before returning, so an unknown packet
/// version is never hidden by an earlier v6 packet. A v6 key or signature
/// anywhere in the block disables the legacy CRC24 checksum.
pub(crate) fn key_block_include_checksum(
    key_versions: impl IntoIterator<Item = KeyVersion>,
    signature_versions: impl IntoIterator<Item = SignatureVersion>,
    legacy_default: bool,
) -> Result<bool, UnsupportedArmorVersion> {
    let mut contains_v6 = false;
    for version in key_versions {
        match version {
            KeyVersion::V2 | KeyVersion::V3 | KeyVersion::V4 | KeyVersion::V5 => {}
            KeyVersion::V6 => contains_v6 = true,
            KeyVersion::Other(_) => return Err(UnsupportedArmorVersion),
        }
    }
    for version in signature_versions {
        match version {
            SignatureVersion::V2
            | SignatureVersion::V3
            | SignatureVersion::V4
            | SignatureVersion::V5
            | SignatureVersion::Other(_) => {}
            SignatureVersion::V6 => contains_v6 = true,
        }
    }
    Ok(legacy_default && !contains_v6)
}

/// Resolves the checksum bit for one or more armored signature packets.
///
/// Mixed sequences are handled conservatively: one v6 signature disables the
/// checksum for the whole armor block.
pub(crate) fn signature_include_checksum(
    versions: impl IntoIterator<Item = SignatureVersion>,
    legacy_default: bool,
) -> Result<bool, UnsupportedArmorVersion> {
    key_block_include_checksum(std::iter::empty(), versions, legacy_default)
}

/// Failure while armoring a bounded transferable-key packet block.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum KeyArmorError {
    Malformed,
    ResourceLimit,
    UnsupportedVersion,
    Internal,
}

/// Armors an already-framed transferable-key packet block into one bounded,
/// zeroizing allocation.
///
/// The packet stream is rescanned so the checksum convention can be derived
/// from every key and signature version in the block; the emitted bytes keep
/// the caller's original framing unchanged.
pub(crate) fn armor_key_packets_bounded(
    packets: &[u8],
    block_type: BlockType,
    max_packets: usize,
    max_output_bytes: usize,
) -> Result<Zeroizing<Vec<u8>>, KeyArmorError> {
    let options = ArmorOptions::default();
    let stream = RawPacketStream::parse(packets, max_packets).map_err(|error| match error {
        RawPacketError::Malformed => KeyArmorError::Malformed,
        RawPacketError::ResourceLimit => KeyArmorError::ResourceLimit,
    })?;
    let mut key_versions = Vec::new();
    let mut signature_versions = Vec::new();
    for packet in stream.packets() {
        let version = match packet.tag() {
            SECRET_KEY_TAG | SECRET_SUBKEY_TAG | PUBLIC_KEY_TAG | PUBLIC_SUBKEY_TAG
            | SIGNATURE_TAG => stream
                .first_body_byte(packet)
                .ok_or(KeyArmorError::Malformed)?,
            _ => continue,
        };
        if packet.tag() == SIGNATURE_TAG {
            signature_versions.push(SignatureVersion::from(version));
        } else {
            key_versions.push(KeyVersion::from(version));
        }
    }
    if key_versions.is_empty() {
        return Err(KeyArmorError::Malformed);
    }
    // RFC 9580 forbids the legacy CRC24 checksum when any v6 key or
    // signature packet occurs in the armored key block.
    let include_checksum =
        key_block_include_checksum(key_versions, signature_versions, options.include_checksum)
            .map_err(|_| KeyArmorError::UnsupportedVersion)?;
    write_zeroizing_armor(
        &RawPackets(packets),
        block_type,
        options.headers,
        include_checksum,
        max_output_bytes,
    )
    .map_err(|error| match error {
        BufferedArmorError::ResourceLimit => KeyArmorError::ResourceLimit,
        BufferedArmorError::Internal => KeyArmorError::Internal,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zeroizing_armor_counts_before_allocating_and_enforces_the_limit() {
        let source = RawPackets(b"private packet bytes");
        let output = write_zeroizing_armor(&source, BlockType::PrivateKey, None, true, usize::MAX)
            .expect("armor within the limit");
        assert_eq!(
            write_zeroizing_armor(&source, BlockType::PrivateKey, None, true, output.len() - 1,),
            Err(BufferedArmorError::ResourceLimit),
        );
    }

    #[test]
    fn mixed_signature_versions_omit_checksum_when_any_signature_is_v6() {
        assert_eq!(
            signature_include_checksum([SignatureVersion::V4, SignatureVersion::V6], true,),
            Ok(false),
        );
        assert_eq!(
            signature_include_checksum([SignatureVersion::V4], true),
            Ok(true),
        );
    }

    #[test]
    fn unknown_signature_versions_are_preserved_and_do_not_hide_v6() {
        assert_eq!(
            key_block_include_checksum(
                [KeyVersion::V6, KeyVersion::Other(99)],
                [SignatureVersion::V6],
                true,
            ),
            Err(UnsupportedArmorVersion),
        );
        assert_eq!(
            signature_include_checksum([SignatureVersion::V6, SignatureVersion::Other(99)], true,),
            Ok(false),
        );
        assert_eq!(
            signature_include_checksum([SignatureVersion::Other(99)], true),
            Ok(true),
        );
    }
}
