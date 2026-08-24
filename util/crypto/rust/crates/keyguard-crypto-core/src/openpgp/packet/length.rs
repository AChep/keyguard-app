//! RFC 4880 and RFC 9580 packet-length parsing.
//!
//! Partial and indeterminate lengths retain their existing tag restrictions,
//! chunk quota, and checked body-size accounting.

use std::ops::Range;

use crate::MAX_CONTROL_ENVELOPE_BYTES;

use super::types::RawPacketError;

pub(crate) const MAX_PARTIAL_BODY_CHUNKS: usize = 4 * 1024;

#[derive(Clone, Copy)]
pub(super) enum RawLength {
    Fixed(usize),
    Partial(usize),
    Indeterminate,
}

pub(super) fn validate_packet_length(tag: u8, length: RawLength) -> Result<(), RawPacketError> {
    match length {
        // RFC 9580 section 4.2.1.4 permits partial lengths only for
        // streaming data packets and requires the first chunk to contain at
        // least 512 octets. Later partial chunks may be smaller.
        RawLength::Partial(length) if length < 512 || !matches!(tag, 8 | 9 | 11 | 18 | 20) => {
            Err(RawPacketError::Malformed)
        }
        // Legacy indeterminate lengths consume the rest of the input. Match
        // GnuPG's historic-data compatibility boundary rather than allowing
        // certificate packets to absorb and then canonicalize their tail.
        RawLength::Indeterminate if !matches!(tag, 8 | 9 | 11) => Err(RawPacketError::Malformed),
        RawLength::Fixed(_) | RawLength::Partial(_) | RawLength::Indeterminate => Ok(()),
    }
}

/// Assembles the RFC 9580 section 4.2.1.2 two-octet new-format length.
///
/// The caller has already matched `first` against `192..=223`.
pub(crate) const fn two_octet_new_length(first: u8, second: u8) -> usize {
    ((first as usize - 192) << 8) + second as usize + 192
}

/// Decodes the RFC 9580 section 4.2.1.4 partial-body chunk length.
///
/// The caller has already matched `first` against `224..=254`.
pub(crate) const fn partial_body_length(first: u8) -> Option<usize> {
    1usize.checked_shl((first & 0x1f) as u32)
}

pub(super) fn read_new_length(data: &[u8], index: &mut usize) -> Result<RawLength, RawPacketError> {
    let first = read_u8(data, index)?;
    match first {
        0..=191 => Ok(RawLength::Fixed(usize::from(first))),
        192..=223 => {
            let second = read_u8(data, index)?;
            Ok(RawLength::Fixed(two_octet_new_length(first, second)))
        }
        224..=254 => Ok(RawLength::Partial(
            partial_body_length(first).ok_or(RawPacketError::ResourceLimit)?,
        )),
        255 => Ok(RawLength::Fixed(
            usize::try_from(read_u32(data, index)?).map_err(|_| RawPacketError::ResourceLimit)?,
        )),
    }
}

pub(super) fn read_body_chunks(
    data: &[u8],
    index: &mut usize,
    mut length: RawLength,
    chunks: &mut Vec<Range<usize>>,
    body_len: &mut usize,
) -> Result<(), RawPacketError> {
    loop {
        if chunks.len() >= MAX_PARTIAL_BODY_CHUNKS {
            return Err(RawPacketError::ResourceLimit);
        }
        let chunk_len = match length {
            RawLength::Fixed(length) | RawLength::Partial(length) => length,
            RawLength::Indeterminate => data.len().saturating_sub(*index),
        };
        *body_len = body_len
            .checked_add(chunk_len)
            .filter(|length| *length <= MAX_CONTROL_ENVELOPE_BYTES)
            .ok_or(RawPacketError::ResourceLimit)?;
        let end = index
            .checked_add(chunk_len)
            .filter(|end| *end <= data.len())
            .ok_or(RawPacketError::Malformed)?;
        chunks.push(*index..end);
        *index = end;
        match length {
            RawLength::Partial(_) => length = read_new_length(data, index)?,
            RawLength::Fixed(_) | RawLength::Indeterminate => return Ok(()),
        }
    }
}

pub(super) fn read_u8(data: &[u8], index: &mut usize) -> Result<u8, RawPacketError> {
    let value = *data.get(*index).ok_or(RawPacketError::Malformed)?;
    *index += 1;
    Ok(value)
}

pub(super) fn read_u16(data: &[u8], index: &mut usize) -> Result<u16, RawPacketError> {
    let end = index.checked_add(2).ok_or(RawPacketError::Malformed)?;
    let bytes = data.get(*index..end).ok_or(RawPacketError::Malformed)?;
    *index = end;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

pub(super) fn read_u32(data: &[u8], index: &mut usize) -> Result<u32, RawPacketError> {
    let end = index.checked_add(4).ok_or(RawPacketError::Malformed)?;
    let bytes = data.get(*index..end).ok_or(RawPacketError::Malformed)?;
    *index = end;
    Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}
