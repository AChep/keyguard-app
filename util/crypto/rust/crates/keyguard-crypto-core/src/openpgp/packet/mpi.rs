//! RFC 9580 multiprecision-integer field framing.
//!
//! MPI framing is shared wire vocabulary: index projection, message parsing,
//! and the concrete cryptographic adapters all split serialized key material
//! with these helpers instead of re-deriving the bit-length header rules.

use pgp::ser::Serialize;

/// Splits one RFC 9580 MPI (bit-length header plus value) off `bytes`.
pub(crate) fn take_mpi(bytes: &[u8]) -> Option<(&[u8], &[u8])> {
    let bits = usize::from(u16::from_be_bytes([*bytes.first()?, *bytes.get(1)?]));
    let length = bits.div_ceil(8);
    let value = bytes.get(2..2 + length)?;
    Some((value, bytes.get(2 + length..)?))
}

pub(crate) fn serialize_params(params: &impl Serialize) -> Option<Vec<u8>> {
    let mut bytes = Vec::new();
    params.to_writer(&mut bytes).ok()?;
    Some(bytes)
}
