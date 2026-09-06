//! Shared packet-layer limits, tags, errors, and byte ranges.
//!
//! Packet spans point into the stream's single retained byte buffer. Their
//! chunk ranges preserve partial-body framing without copying or normalizing
//! packet contents.

use std::ops::Range;

/// Maximum packets accepted from one transferable certificate document.
///
/// Import, merge and mutation share this bound: a certificate small enough to
/// import must remain small enough to mutate, so a stricter local limit would
/// only produce certificates that can never be renewed or revoked again.
pub(crate) const MAX_CERTIFICATE_PACKETS: usize = 8 * 1024;

// RFC 4880 / RFC 9580 packet-tag registry shared by the raw framing seams.
pub(crate) const SIGNATURE_TAG: u8 = 2;
pub(crate) const SECRET_KEY_TAG: u8 = 5;
pub(crate) const PUBLIC_KEY_TAG: u8 = 6;
pub(crate) const SECRET_SUBKEY_TAG: u8 = 7;
pub(crate) const MARKER_TAG: u8 = 10;
pub(crate) const TRUST_TAG: u8 = 12;
pub(crate) const USER_ID_TAG: u8 = 13;
pub(crate) const PUBLIC_SUBKEY_TAG: u8 = 14;
pub(crate) const USER_ATTRIBUTE_TAG: u8 = 17;
pub(crate) const PADDING_TAG: u8 = 21;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RawPacketError {
    Malformed,
    ResourceLimit,
}

/// Failure while serializing a bounded fixed-length packet envelope.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FixedPacketWriteError {
    ResourceLimit,
    Internal,
}

#[derive(Clone, Debug)]
pub(crate) struct RawPacketSpan {
    pub(super) tag: u8,
    pub(super) raw: Range<usize>,
    pub(super) body_chunks: Vec<Range<usize>>,
    pub(super) body_len: usize,
    pub(super) recovered_after_tainted_certificate: bool,
}

impl RawPacketSpan {
    pub(crate) const fn tag(&self) -> u8 {
        self.tag
    }

    pub(crate) fn body_len(&self) -> usize {
        self.body_len
    }

    /// Returns whether this primary starts after raw keyring recovery dropped
    /// the preceding damaged certificate sequence.
    pub(crate) const fn recovered_after_tainted_certificate(&self) -> bool {
        self.recovered_after_tainted_certificate
    }
}
