//! Bounded, lossless OpenPGP packet framing.
//!
//! Composed certificate parsers intentionally discard packets that are not
//! part of their authenticated model. Import and mutation code must instead
//! retain the original packet framing and only rewrite explicitly selected
//! packet bodies.

pub(crate) mod armor;

mod dearmor;
mod framing;
mod length;
mod mpi;
mod stream;
mod types;

pub(crate) use dearmor::{TolerantArmorReader, dearmor_bounded};
pub(crate) use framing::{parse_fixed_packet_body, write_fixed_packet};
pub(crate) use length::{MAX_PARTIAL_BODY_CHUNKS, partial_body_length, two_octet_new_length};
pub(crate) use mpi::{serialize_params, take_mpi};
pub(crate) use stream::RawPacketStream;
pub(crate) use types::{
    FixedPacketWriteError, MARKER_TAG, MAX_CERTIFICATE_PACKETS, PADDING_TAG, PUBLIC_KEY_TAG,
    PUBLIC_SUBKEY_TAG, RawPacketError, RawPacketSpan, SECRET_KEY_TAG, SECRET_SUBKEY_TAG,
    SIGNATURE_TAG, TRUST_TAG, USER_ATTRIBUTE_TAG, USER_ID_TAG,
};

#[cfg(test)]
use dearmor::{DEARMOR_SCRATCH_BYTES, dearmor_single, decode_bounded, find_bytes};
#[cfg(test)]
use framing::MAX_KEYRING_RECOVERY_BYTES;
#[cfg(test)]
mod tests;
