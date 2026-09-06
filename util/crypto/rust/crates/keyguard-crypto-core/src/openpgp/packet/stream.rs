//! Byte-preserving packet streams and certificate-range projections.
//!
//! The decoded input is retained in one zeroizing allocation. Packet bodies
//! are assembled only when requested, while raw serialization returns the
//! original framing unchanged.

use std::ops::Range;

use zeroize::Zeroizing;

use super::{
    dearmor::decode_bounded,
    framing::{scan_packets, scan_packets_recovering_keyring},
    types::{
        MARKER_TAG, PUBLIC_KEY_TAG, RawPacketError, RawPacketSpan, SECRET_KEY_TAG,
        SECRET_SUBKEY_TAG,
    },
};

#[derive(Debug)]
pub(crate) struct RawPacketStream {
    bytes: Zeroizing<Vec<u8>>,
    packets: Vec<RawPacketSpan>,
    skipped_tainted_certificates: usize,
}

impl RawPacketStream {
    pub(crate) fn parse(input: &[u8], max_packets: usize) -> Result<Self, RawPacketError> {
        let bytes = decode_bounded(input)?;
        let packets = scan_packets(&bytes, max_packets)?;
        Ok(Self {
            bytes,
            packets,
            skipped_tainted_certificates: 0,
        })
    }

    /// Parses a transferable keyring while recovering at later primary-key
    /// packet boundaries after malformed bytes.
    ///
    /// This is deliberately separate from [`Self::parse`]: message and
    /// mutation inputs remain strict, while certificate import mirrors
    /// tolerant keyring behavior by retaining complete certificates before
    /// the damaged entry and independent certificates after it. Packets from
    /// the certificate sequence tainted by the error are discarded.
    pub(crate) fn parse_transferable_keyring(
        input: &[u8],
        max_packets: usize,
    ) -> Result<Self, RawPacketError> {
        let bytes = decode_bounded(input)?;
        let (packets, skipped_tainted_certificates) =
            scan_packets_recovering_keyring(&bytes, max_packets)?;
        Ok(Self {
            bytes,
            packets,
            skipped_tainted_certificates,
        })
    }

    pub(crate) fn packets(&self) -> &[RawPacketSpan] {
        &self.packets
    }

    /// Number of certificate sequences discarded at raw recovery boundaries.
    pub(crate) const fn skipped_tainted_certificates(&self) -> usize {
        self.skipped_tainted_certificates
    }

    #[cfg(test)]
    pub(crate) fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub(crate) fn raw<'a>(&'a self, packet: &RawPacketSpan) -> &'a [u8] {
        &self.bytes[packet.raw.clone()]
    }

    pub(crate) fn body(&self, packet: &RawPacketSpan) -> Zeroizing<Vec<u8>> {
        Zeroizing::new(self.body_to_vec(packet))
    }

    /// Returns the first body byte without assembling a body copy.
    ///
    /// Callers that only inspect a version octet must not pay for (or retain)
    /// an allocation of the whole packet body. `None` mirrors an empty body.
    pub(crate) fn first_body_byte(&self, packet: &RawPacketSpan) -> Option<u8> {
        packet
            .body_chunks
            .iter()
            .find(|chunk| !chunk.is_empty())
            .and_then(|chunk| self.bytes.get(chunk.start).copied())
    }

    /// Assembles one packet body into a single exact-capacity allocation.
    ///
    /// The capacity is exact, so the buffer never reallocates while bytes are
    /// being copied; [`Self::body`] relies on this to zeroize the only copy.
    /// Callers that keep the plain [`Vec`] must only use it for packet bodies
    /// that are not secret.
    pub(crate) fn body_to_vec(&self, packet: &RawPacketSpan) -> Vec<u8> {
        let mut body = Vec::with_capacity(packet.body_len);
        for chunk in &packet.body_chunks {
            body.extend_from_slice(&self.bytes[chunk.clone()]);
        }
        body
    }

    /// Validates every RFC 9580 Marker packet in a bounded packet span.
    ///
    /// A Marker is ignorable only when its complete body is exactly `PGP`.
    /// Compare directly against the retained chunks so certificate parsing
    /// does not allocate or read beyond the packet body's declared ranges.
    pub(crate) fn validate_marker_packets(
        &self,
        packets: &[RawPacketSpan],
    ) -> Result<(), RawPacketError> {
        for packet in packets.iter().filter(|packet| packet.tag == MARKER_TAG) {
            if !self.body_matches(packet, b"PGP") {
                return Err(RawPacketError::Malformed);
            }
        }
        Ok(())
    }

    /// Compares one packet body against `expected` without assembling it.
    pub(crate) fn body_matches(&self, packet: &RawPacketSpan, expected: &[u8]) -> bool {
        if packet.body_len != expected.len() {
            return false;
        }
        let mut offset = 0usize;
        for range in &packet.body_chunks {
            let Some(chunk) = self.bytes.get(range.clone()) else {
                return false;
            };
            let Some(end) = offset.checked_add(chunk.len()) else {
                return false;
            };
            if expected.get(offset..end) != Some(chunk) {
                return false;
            }
            offset = end;
        }
        offset == expected.len()
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
    ///
    /// RFC 9580 section 10.2 classifies a certificate as a transferable
    /// secret key when any primary or subkey packet carries secret material.
    /// In particular, a filtered TSK may start with a public primary and carry
    /// only selected secret subkeys.
    pub(crate) fn first_secret_certificate(&self) -> Option<Range<usize>> {
        let mut start = 0_usize;
        while start < self.packets.len() {
            start = self.packets[start..]
                .iter()
                .position(|packet| matches!(packet.tag, SECRET_KEY_TAG | PUBLIC_KEY_TAG))?
                .checked_add(start)?;
            let end = self.packets[start + 1..]
                .iter()
                .position(|packet| matches!(packet.tag, SECRET_KEY_TAG | PUBLIC_KEY_TAG))
                .map(|offset| start + 1 + offset)
                .unwrap_or(self.packets.len());
            if self.packets[start..end]
                .iter()
                .any(|packet| matches!(packet.tag, SECRET_KEY_TAG | SECRET_SUBKEY_TAG))
            {
                return Some(start..end);
            }
            start = end;
        }
        None
    }

    /// Returns the packet-index range occupied by the first transferable
    /// public key. A following public or secret primary starts another
    /// certificate and is deliberately excluded.
    pub(crate) fn first_public_certificate(&self) -> Option<Range<usize>> {
        let start = self
            .packets
            .iter()
            .position(|packet| packet.tag == PUBLIC_KEY_TAG)?;
        let end = self
            .packets
            .iter()
            .enumerate()
            .skip(start + 1)
            .find_map(|(index, packet)| {
                matches!(packet.tag, SECRET_KEY_TAG | PUBLIC_KEY_TAG).then_some(index)
            })
            .unwrap_or(self.packets.len());
        Some(start..end)
    }
}
