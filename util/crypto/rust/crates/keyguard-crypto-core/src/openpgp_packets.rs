//! Bounded, lossless OpenPGP packet framing.
//!
//! Composed certificate parsers intentionally discard packets that are not
//! part of their authenticated model.  Import and mutation code must instead
//! retain the original packet framing and only rewrite explicitly selected
//! packet bodies.

use std::{
    io::{BufReader, Cursor, Read},
    ops::Range,
};

use pgp::armor::{Dearmor, DearmorOptions};
use zeroize::Zeroizing;

use crate::MAX_CONTROL_ENVELOPE_BYTES;

const MAX_PARTIAL_BODY_CHUNKS: usize = 4 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RawPacketError {
    Malformed,
    ResourceLimit,
}

#[derive(Clone, Debug)]
pub(crate) struct RawPacketSpan {
    tag: u8,
    raw: Range<usize>,
    body_chunks: Vec<Range<usize>>,
    body_len: usize,
}

impl RawPacketSpan {
    pub(crate) const fn tag(&self) -> u8 {
        self.tag
    }

    pub(crate) fn body_len(&self) -> usize {
        self.body_len
    }
}

#[derive(Debug)]
pub(crate) struct RawPacketStream {
    bytes: Zeroizing<Vec<u8>>,
    packets: Vec<RawPacketSpan>,
}

impl RawPacketStream {
    pub(crate) fn parse(input: &[u8], max_packets: usize) -> Result<Self, RawPacketError> {
        let bytes = decode_bounded(input)?;
        let packets = scan_packets(&bytes, max_packets)?;
        Ok(Self { bytes, packets })
    }

    pub(crate) fn packets(&self) -> &[RawPacketSpan] {
        &self.packets
    }

    pub(crate) fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub(crate) fn raw<'a>(&'a self, packet: &RawPacketSpan) -> &'a [u8] {
        &self.bytes[packet.raw.clone()]
    }

    pub(crate) fn body(&self, packet: &RawPacketSpan) -> Zeroizing<Vec<u8>> {
        let mut body = Zeroizing::new(Vec::with_capacity(packet.body_len));
        for chunk in &packet.body_chunks {
            body.extend_from_slice(&self.bytes[chunk.clone()]);
        }
        body
    }

    /// Returns the packet stream used for semantic parsing. RFC 9580
    /// noncritical and private packet types are retained in storage but do not
    /// participate in certificate interpretation.
    pub(crate) fn semantic_bytes(&self) -> Zeroizing<Vec<u8>> {
        let retained_len = self
            .packets
            .iter()
            .filter(|packet| packet.tag < 40)
            .map(|packet| packet.raw.len())
            .sum();
        let mut bytes = Zeroizing::new(Vec::with_capacity(retained_len));
        for packet in self.packets.iter().filter(|packet| packet.tag < 40) {
            bytes.extend_from_slice(self.raw(packet));
        }
        bytes
    }

    /// Returns the packet-index range occupied by the first transferable
    /// secret key. A following public or secret primary starts another
    /// certificate and is deliberately excluded.
    pub(crate) fn first_secret_certificate(&self) -> Option<Range<usize>> {
        let start = self.packets.iter().position(|packet| packet.tag == 5)?;
        let end = self
            .packets
            .iter()
            .enumerate()
            .skip(start + 1)
            .find_map(|(index, packet)| matches!(packet.tag, 5 | 6).then_some(index))
            .unwrap_or(self.packets.len());
        Some(start..end)
    }

    /// Compares packet tags and flattened bodies while deliberately ignoring
    /// equivalent packet-header encodings.
    pub(crate) fn inventory_matches(
        &self,
        own_range: Range<usize>,
        other: &Self,
        other_range: Range<usize>,
    ) -> bool {
        let Some(own) = self.packets.get(own_range) else {
            return false;
        };
        let Some(other_packets) = other.packets.get(other_range) else {
            return false;
        };
        own.len() == other_packets.len()
            && own.iter().zip(other_packets).all(|(left, right)| {
                left.tag == right.tag && self.body(left).as_slice() == other.body(right).as_slice()
            })
    }
}

fn decode_bounded(input: &[u8]) -> Result<Zeroizing<Vec<u8>>, RawPacketError> {
    if input.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(RawPacketError::ResourceLimit);
    }
    let first = input.first().copied().ok_or(RawPacketError::Malformed)?;
    if first & 0x80 != 0 {
        return Ok(Zeroizing::new(input.to_vec()));
    }

    let reader = BufReader::new(Cursor::new(input));
    let mut dearmor = Dearmor::with_options(
        reader,
        DearmorOptions::default().set_limit(MAX_CONTROL_ENVELOPE_BYTES),
    );
    dearmor
        .read_header()
        .map_err(|_| RawPacketError::Malformed)?;
    let mut bytes = Zeroizing::new(Vec::new());
    dearmor
        .take((MAX_CONTROL_ENVELOPE_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|_| RawPacketError::Malformed)?;
    if bytes.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(RawPacketError::ResourceLimit);
    }
    Ok(bytes)
}

fn scan_packets(data: &[u8], max_packets: usize) -> Result<Vec<RawPacketSpan>, RawPacketError> {
    let mut packets = Vec::new();
    let mut index = 0usize;
    while index < data.len() {
        if packets.len() >= max_packets {
            return Err(RawPacketError::ResourceLimit);
        }
        let raw_start = index;
        let header = read_u8(data, &mut index)?;
        if header & 0x80 == 0 {
            return Err(RawPacketError::Malformed);
        }
        let (tag, length) = if header & 0x40 != 0 {
            (header & 0x3f, read_new_length(data, &mut index)?)
        } else {
            let tag = (header >> 2) & 0x0f;
            let length = match header & 0x03 {
                0 => RawLength::Fixed(usize::from(read_u8(data, &mut index)?)),
                1 => RawLength::Fixed(usize::from(read_u16(data, &mut index)?)),
                2 => RawLength::Fixed(
                    usize::try_from(read_u32(data, &mut index)?)
                        .map_err(|_| RawPacketError::ResourceLimit)?,
                ),
                3 => RawLength::Indeterminate,
                _ => unreachable!("two-bit old packet length"),
            };
            (tag, length)
        };
        if matches!(tag, 0 | 15 | 16 | 22..=39) {
            return Err(RawPacketError::Malformed);
        }

        let mut body_chunks = Vec::new();
        let mut body_len = 0usize;
        read_body_chunks(data, &mut index, length, &mut body_chunks, &mut body_len)?;
        packets.push(RawPacketSpan {
            tag,
            raw: raw_start..index,
            body_chunks,
            body_len,
        });
    }
    Ok(packets)
}

#[derive(Clone, Copy)]
enum RawLength {
    Fixed(usize),
    Partial(usize),
    Indeterminate,
}

fn read_new_length(data: &[u8], index: &mut usize) -> Result<RawLength, RawPacketError> {
    let first = read_u8(data, index)?;
    match first {
        0..=191 => Ok(RawLength::Fixed(usize::from(first))),
        192..=223 => {
            let second = read_u8(data, index)?;
            Ok(RawLength::Fixed(
                ((usize::from(first) - 192) << 8) + usize::from(second) + 192,
            ))
        }
        224..=254 => Ok(RawLength::Partial(
            1usize
                .checked_shl(u32::from(first & 0x1f))
                .ok_or(RawPacketError::ResourceLimit)?,
        )),
        255 => Ok(RawLength::Fixed(
            usize::try_from(read_u32(data, index)?).map_err(|_| RawPacketError::ResourceLimit)?,
        )),
    }
}

fn read_body_chunks(
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

fn read_u8(data: &[u8], index: &mut usize) -> Result<u8, RawPacketError> {
    let value = *data.get(*index).ok_or(RawPacketError::Malformed)?;
    *index += 1;
    Ok(value)
}

fn read_u16(data: &[u8], index: &mut usize) -> Result<u16, RawPacketError> {
    let end = index.checked_add(2).ok_or(RawPacketError::Malformed)?;
    let bytes = data.get(*index..end).ok_or(RawPacketError::Malformed)?;
    *index = end;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn read_u32(data: &[u8], index: &mut usize) -> Result<u32, RawPacketError> {
    let end = index.checked_add(4).ok_or(RawPacketError::Malformed)?;
    let bytes = data.get(*index..end).ok_or(RawPacketError::Malformed)?;
    *index = end;
    Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scanner_preserves_partial_packet_framing() {
        let data = [0xcd, 0xe0, b'a', 0xe1, b'b', b'c', 0x01, b'd'];
        let stream = RawPacketStream::parse(&data, 1).expect("parse partial packet");
        let packet = &stream.packets()[0];
        assert_eq!(packet.tag(), 13);
        assert_eq!(stream.raw(packet), data);
        assert_eq!(stream.body(packet).as_slice(), b"abcd");
    }

    #[test]
    fn scanner_rejects_unknown_critical_packet() {
        let data = [0xd6, 0x00];
        assert_eq!(
            RawPacketStream::parse(&data, 1).expect_err("unknown critical packet"),
            RawPacketError::Malformed,
        );
    }
}
