//! Packet-header framing, strict scanning, and bounded keyring recovery.
//!
//! Recovery may resume only at independently framed primary-key packets; body
//! bytes from a malformed packet are never reinterpreted as new certificates.

use std::io::Cursor;

use pgp::{packet::PacketHeader, ser::Serialize, types::Tag};

use super::{
    length::{
        RawLength, read_body_chunks, read_new_length, read_u8, read_u16, read_u32,
        validate_packet_length,
    },
    types::{FixedPacketWriteError, PUBLIC_KEY_TAG, RawPacketError, RawPacketSpan, SECRET_KEY_TAG},
};

// Match Sequoia's recovery window: tolerant import remains useful for ordinary
// keyring damage without turning the full request envelope into a byte-wise
// search surface.
pub(super) const MAX_KEYRING_RECOVERY_BYTES: usize = 32 * 1024;

/// Appends one packet using the canonical fixed-length header representation.
pub(crate) fn write_fixed_packet(
    tag: u8,
    body: &[u8],
    output: &mut Vec<u8>,
) -> Result<(), FixedPacketWriteError> {
    let length = u32::try_from(body.len()).map_err(|_| FixedPacketWriteError::ResourceLimit)?;
    PacketHeader::new_fixed(Tag::from(tag), length)
        .to_writer(output)
        .map_err(|_| FixedPacketWriteError::Internal)?;
    output.extend_from_slice(body);
    Ok(())
}

/// Parses exactly one packet body under a canonical fixed-length header.
///
/// The parser must consume every body byte; trailing bytes are rejected so a
/// packet body cannot smuggle unparsed data past its semantic reading.
pub(crate) fn parse_fixed_packet_body<T>(
    tag: Tag,
    body: &[u8],
    parse: impl FnOnce(PacketHeader, &mut Cursor<&[u8]>) -> pgp::errors::Result<T>,
) -> Result<T, RawPacketError> {
    let length = u32::try_from(body.len()).map_err(|_| RawPacketError::ResourceLimit)?;
    let header = PacketHeader::new_fixed(tag, length);
    let mut reader = Cursor::new(body);
    let value = parse(header, &mut reader).map_err(|_| RawPacketError::Malformed)?;
    if usize::try_from(reader.position()).ok() != Some(body.len()) {
        return Err(RawPacketError::Malformed);
    }
    Ok(value)
}

pub(super) fn scan_packets(
    data: &[u8],
    max_packets: usize,
) -> Result<Vec<RawPacketSpan>, RawPacketError> {
    let mut packets = Vec::new();
    let mut index = 0usize;
    while index < data.len() {
        if packets.len() >= max_packets {
            return Err(RawPacketError::ResourceLimit);
        }
        packets.push(scan_packet(data, &mut index).map_err(PacketScanError::into_raw)?);
    }
    Ok(packets)
}

pub(super) fn scan_packets_recovering_keyring(
    data: &[u8],
    max_packets: usize,
) -> Result<(Vec<RawPacketSpan>, usize), RawPacketError> {
    let mut packets = Vec::new();
    let mut index = 0usize;
    let mut scanned_packets = 0usize;
    let mut skipped_tainted_certificates = 0usize;
    let mut recovered_after_tainted_certificate = false;
    while index < data.len() {
        if scanned_packets >= max_packets {
            return Err(RawPacketError::ResourceLimit);
        }
        match scan_packet(data, &mut index) {
            Ok(mut packet) => {
                scanned_packets += 1;
                if recovered_after_tainted_certificate {
                    debug_assert!(matches!(packet.tag, PUBLIC_KEY_TAG | SECRET_KEY_TAG));
                    packet.recovered_after_tainted_certificate = true;
                    recovered_after_tainted_certificate = false;
                }
                packets.push(packet);
            }
            Err(PacketScanError::ResourceLimit) => return Err(RawPacketError::ResourceLimit),
            Err(PacketScanError::Unrecoverable) => return Err(RawPacketError::Malformed),
            Err(PacketScanError::RecoverableFraming { resume_at }) => {
                let Some(recovery) = find_next_primary_packet(data, resume_at)? else {
                    // A parser error is not an end-of-keyring marker.  In
                    // particular, bytes after the error may contain
                    // revocation evidence belonging to the current
                    // certificate.  Without a later primary-key boundary we
                    // cannot isolate a damaged entry, so returning the
                    // already parsed prefix would silently truncate it.
                    return Err(RawPacketError::Malformed);
                };
                // RFC 9580 sections 4.3 and 10 invalidate the whole current
                // certificate sequence. Preserve earlier complete entries,
                // but discard every packet back to the most recent primary
                // before resuming at the independently framed next primary.
                if let Some(tainted_start) = packets
                    .iter()
                    .rposition(|packet| matches!(packet.tag, PUBLIC_KEY_TAG | SECRET_KEY_TAG))
                {
                    packets.truncate(tainted_start);
                    skipped_tainted_certificates = skipped_tainted_certificates.saturating_add(1);
                    recovered_after_tainted_certificate = true;
                }
                index = recovery;
            }
        }
    }
    if packets.is_empty() {
        return Err(RawPacketError::Malformed);
    }
    Ok((packets, skipped_tainted_certificates))
}

fn find_next_primary_packet(data: &[u8], start: usize) -> Result<Option<usize>, RawPacketError> {
    let search_end = start
        .saturating_add(MAX_KEYRING_RECOVERY_BYTES)
        .min(data.len());
    for candidate in start..search_end {
        if plausible_primary_packet_at(data, candidate) {
            return Ok(Some(candidate));
        }
    }
    if search_end < data.len() {
        Err(RawPacketError::ResourceLimit)
    } else {
        Ok(None)
    }
}

/// Internal packet-scan failures classified by safe keyring recovery policy.
///
/// Header parsing failures do not establish a body boundary, so recovery may
/// probe subsequent bytes. Once a complete header declares a body, however,
/// malformed body framing or EOF is terminal: probing those bytes could turn
/// attacker-controlled packet contents into an independent certificate.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PacketScanError {
    RecoverableFraming { resume_at: usize },
    Unrecoverable,
    ResourceLimit,
}

impl PacketScanError {
    const fn into_raw(self) -> RawPacketError {
        match self {
            Self::RecoverableFraming { .. } | Self::Unrecoverable => RawPacketError::Malformed,
            Self::ResourceLimit => RawPacketError::ResourceLimit,
        }
    }
}

fn header_scan_error(error: RawPacketError, packet_start: usize) -> PacketScanError {
    match error {
        RawPacketError::Malformed => PacketScanError::RecoverableFraming {
            resume_at: packet_start.saturating_add(1),
        },
        RawPacketError::ResourceLimit => PacketScanError::ResourceLimit,
    }
}

fn body_scan_error(error: RawPacketError) -> PacketScanError {
    match error {
        RawPacketError::Malformed => PacketScanError::Unrecoverable,
        RawPacketError::ResourceLimit => PacketScanError::ResourceLimit,
    }
}

/// Cheaply probes a recovery candidate without scanning its body framing.
///
/// Primary-key packets cannot use partial or indeterminate lengths. Checking
/// that restriction before walking the body prevents attacker-controlled junk
/// from charging the partial-chunk limit once per candidate offset. The body
/// version and minimum fixed fields also keep an arbitrary high-bit byte from
/// swallowing a later real certificate as a false recovery boundary.
fn plausible_primary_packet_at(data: &[u8], candidate: usize) -> bool {
    let Some(header) = data.get(candidate).copied() else {
        return false;
    };
    if header & 0x80 == 0 {
        return false;
    }
    let tag = if header & 0x40 != 0 {
        header & 0x3f
    } else {
        (header >> 2) & 0x0f
    };
    if !matches!(tag, PUBLIC_KEY_TAG | SECRET_KEY_TAG) {
        return false;
    }

    let mut body_start = candidate.saturating_add(1);
    let length = if header & 0x40 != 0 {
        match read_new_length(data, &mut body_start) {
            Ok(RawLength::Fixed(length)) => length,
            Ok(RawLength::Partial(_) | RawLength::Indeterminate) | Err(_) => return false,
        }
    } else {
        match header & 0x03 {
            0 => match read_u8(data, &mut body_start) {
                Ok(length) => usize::from(length),
                Err(_) => return false,
            },
            1 => match read_u16(data, &mut body_start) {
                Ok(length) => usize::from(length),
                Err(_) => return false,
            },
            2 => match read_u32(data, &mut body_start) {
                Ok(length) => match usize::try_from(length) {
                    Ok(length) => length,
                    Err(_) => return false,
                },
                Err(_) => return false,
            },
            3 => return false,
            _ => unreachable!("two-bit old packet length"),
        }
    };
    let Some(body_end) = body_start.checked_add(length) else {
        return false;
    };
    let Some(body) = data.get(body_start..body_end) else {
        return false;
    };
    match body.first().copied() {
        // V2/V3 include creation time, validity, and algorithm fields.
        Some(2 | 3) => body.len() >= 8,
        // V4 includes creation time and the public-key algorithm.
        Some(4) => body.len() >= 6,
        // V5/V6 additionally carry a four-octet key-material length.
        Some(5 | 6) => body.len() >= 10,
        Some(_) | None => false,
    }
}

fn scan_packet(data: &[u8], index: &mut usize) -> Result<RawPacketSpan, PacketScanError> {
    let raw_start = *index;
    let header = read_u8(data, index).map_err(|error| header_scan_error(error, raw_start))?;
    if header & 0x80 == 0 {
        return Err(PacketScanError::RecoverableFraming {
            resume_at: raw_start.saturating_add(1),
        });
    }
    let (tag, length) = if header & 0x40 != 0 {
        (
            header & 0x3f,
            read_new_length(data, index).map_err(|error| header_scan_error(error, raw_start))?,
        )
    } else {
        let tag = (header >> 2) & 0x0f;
        let length = match header & 0x03 {
            0 => RawLength::Fixed(usize::from(
                read_u8(data, index).map_err(|error| header_scan_error(error, raw_start))?,
            )),
            1 => RawLength::Fixed(usize::from(
                read_u16(data, index).map_err(|error| header_scan_error(error, raw_start))?,
            )),
            2 => RawLength::Fixed(
                usize::try_from(
                    read_u32(data, index).map_err(|error| header_scan_error(error, raw_start))?,
                )
                .map_err(|_| PacketScanError::ResourceLimit)?,
            ),
            3 => RawLength::Indeterminate,
            _ => unreachable!("two-bit old packet length"),
        };
        (tag, length)
    };

    let mut body_chunks = Vec::new();
    let mut body_len = 0usize;
    let invalid_header = if matches!(tag, 0 | 15 | 16 | 22..=39) {
        true
    } else {
        match validate_packet_length(tag, length) {
            Ok(()) => false,
            Err(RawPacketError::Malformed) => true,
            Err(RawPacketError::ResourceLimit) => return Err(PacketScanError::ResourceLimit),
        }
    };
    read_body_chunks(data, index, length, &mut body_chunks, &mut body_len)
        .map_err(body_scan_error)?;
    if invalid_header {
        // The header still declared this complete body. Resume after it so
        // unknown critical data can taint the current certificate without
        // allowing packet-looking body bytes to become a recovery boundary.
        return Err(PacketScanError::RecoverableFraming { resume_at: *index });
    }
    Ok(RawPacketSpan {
        tag,
        raw: raw_start..*index,
        body_chunks,
        body_len,
        recovered_after_tainted_certificate: false,
    })
}
