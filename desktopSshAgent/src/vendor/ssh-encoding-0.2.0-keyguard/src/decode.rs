//! Decoder-side implementation of the SSH protocol's data type representations
//! as described in [RFC4251 § 5].
//!
//! [RFC4251 § 5]: https://datatracker.ietf.org/doc/html/rfc4251#section-5

use crate::{reader::Reader, Error, Result};

#[cfg(feature = "alloc")]
use alloc::{string::String, vec::Vec};

#[cfg(feature = "bytes")]
use bytes::Bytes;

#[cfg(feature = "pem")]
use {crate::PEM_LINE_WIDTH, pem::PemLabel};

/// Maximum size of a `usize` this library will accept.
const MAX_SIZE: usize = 0xFFFFF;

/// Decoding trait.
///
/// This trait describes how to decode a given type.
pub trait Decode: Sized {
    /// Type returned in the event of a decoding error.
    type Error: From<Error>;

    /// Attempt to decode a value of this type using the provided [`Reader`].
    fn decode(reader: &mut impl Reader) -> core::result::Result<Self, Self::Error>;
}

/// Decoding trait for PEM documents.
///
/// This is an extension trait which is auto-impl'd for types which impl the
/// [`Decode`], [`PemLabel`], and [`Sized`] traits.
#[cfg(feature = "pem")]
pub trait DecodePem: Decode + PemLabel + Sized {
    /// Decode the provided PEM-encoded string, interpreting the Base64-encoded
    /// body of the document using the [`Decode`] trait.
    fn decode_pem(pem: impl AsRef<[u8]>) -> core::result::Result<Self, Self::Error>;
}

#[cfg(feature = "pem")]
impl<T: Decode + PemLabel + Sized> DecodePem for T {
    fn decode_pem(pem: impl AsRef<[u8]>) -> core::result::Result<Self, Self::Error> {
        let mut reader =
            pem::Decoder::new_wrapped(pem.as_ref(), PEM_LINE_WIDTH).map_err(Error::from)?;

        Self::validate_pem_label(reader.type_label()).map_err(Error::from)?;
        let ret = Self::decode(&mut reader)?;
        Ok(reader.finish(ret)?)
    }
}

/// Decode a single `byte` from the input data.
impl Decode for u8 {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let mut buf = [0];
        reader.read(&mut buf)?;
        Ok(buf[0])
    }
}

/// Decode a `uint32` as described in [RFC4251 § 5]:
///
/// > Represents a 32-bit unsigned integer.  Stored as four bytes in the
/// > order of decreasing significance (network byte order).
/// > For example: the value 699921578 (0x29b7f4aa) is stored as 29 b7 f4 aa.
///
/// [RFC4251 § 5]: https://datatracker.ietf.org/doc/html/rfc4251#section-5
impl Decode for u32 {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let mut bytes = [0u8; 4];
        reader.read(&mut bytes)?;
        Ok(u32::from_be_bytes(bytes))
    }
}

/// Decode a `uint64` as described in [RFC4251 § 5]:
///
/// > Represents a 64-bit unsigned integer.  Stored as eight bytes in
/// > the order of decreasing significance (network byte order).
///
/// [RFC4251 § 5]: https://datatracker.ietf.org/doc/html/rfc4251#section-5
impl Decode for u64 {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let mut bytes = [0u8; 8];
        reader.read(&mut bytes)?;
        Ok(u64::from_be_bytes(bytes))
    }
}

/// Decode a `usize`.
///
/// Uses [`Decode`] impl on `u32` and then converts to a `usize`, handling
/// potential overflow if `usize` is smaller than `u32`.
///
/// Enforces a library-internal limit of 1048575, as the main use case for
/// `usize` is length prefixes.
impl Decode for usize {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let n = usize::try_from(u32::decode(reader)?)?;

        if n <= MAX_SIZE {
            Ok(n)
        } else {
            Err(Error::Overflow)
        }
    }
}

/// Decodes a byte array from `byte[n]` as described in [RFC4251 § 5]:
///
/// > A byte represents an arbitrary 8-bit value (octet).  Fixed length
/// > data is sometimes represented as an array of bytes, written
/// > `byte[n]`, where n is the number of bytes in the array.
///
/// [RFC4251 § 5]: https://datatracker.ietf.org/doc/html/rfc4251#section-5
impl<const N: usize> Decode for [u8; N] {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        reader.read_prefixed(|reader| {
            let mut result = [(); N].map(|_| 0);
            reader.read(&mut result)?;
            Ok(result)
        })
    }
}

/// Decodes `Vec<u8>` from `byte[n]` as described in [RFC4251 § 5]:
///
/// > A byte represents an arbitrary 8-bit value (octet).  Fixed length
/// > data is sometimes represented as an array of bytes, written
/// > `byte[n]`, where n is the number of bytes in the array.
///
/// [RFC4251 § 5]: https://datatracker.ietf.org/doc/html/rfc4251#section-5
#[cfg(feature = "alloc")]
impl Decode for Vec<u8> {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        reader.read_prefixed(|reader| {
            let mut result = vec![0u8; reader.remaining_len()];
            reader.read(&mut result)?;
            Ok(result)
        })
    }
}

#[cfg(feature = "alloc")]
impl Decode for String {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        String::from_utf8(Vec::decode(reader)?).map_err(|_| Error::CharacterEncoding)
    }
}

#[cfg(feature = "alloc")]
impl Decode for Vec<String> {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        reader.read_prefixed(|reader| {
            let mut entries = Self::new();

            while !reader.is_finished() {
                entries.push(String::decode(reader)?);
            }

            Ok(entries)
        })
    }
}

/// Decodes `Bytes` from `byte[n]` as described in [RFC4251 § 5]:
///
/// > A byte represents an arbitrary 8-bit value (octet).  Fixed length
/// > data is sometimes represented as an array of bytes, written
/// > `byte[n]`, where n is the number of bytes in the array.
///
/// [RFC4251 § 5]: https://datatracker.ietf.org/doc/html/rfc4251#section-5
#[cfg(feature = "bytes")]
impl Decode for Bytes {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        Vec::<u8>::decode(reader).map(Into::into)
    }
}
