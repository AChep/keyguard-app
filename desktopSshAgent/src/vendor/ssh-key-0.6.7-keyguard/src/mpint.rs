//! Multiple precision integer

use crate::{Error, Result};
use alloc::{boxed::Box, vec::Vec};
use core::fmt;
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};
use subtle::{Choice, ConstantTimeEq};
use zeroize::Zeroize;

#[cfg(any(feature = "dsa", feature = "rsa"))]
use zeroize::Zeroizing;

/// Multiple precision integer, a.k.a. "mpint".
///
/// This type is used for representing the big integer components of
/// DSA and RSA keys.
///
/// Described in [RFC4251 § 5](https://datatracker.ietf.org/doc/html/rfc4251#section-5):
///
/// > Represents multiple precision integers in two's complement format,
/// > stored as a string, 8 bits per byte, MSB first.  Negative numbers
/// > have the value 1 as the most significant bit of the first byte of
/// > the data partition.  If the most significant bit would be set for
/// > a positive number, the number MUST be preceded by a zero byte.
/// > Unnecessary leading bytes with the value 0 or 255 MUST NOT be
/// > included.  The value zero MUST be stored as a string with zero
/// > bytes of data.
/// >
/// > By convention, a number that is used in modular computations in
/// > Z_n SHOULD be represented in the range 0 <= x < n.
///
/// ## Examples
///
/// | value (hex)     | representation (hex) |
/// |-----------------|----------------------|
/// | 0               | `00 00 00 00`
/// | 9a378f9b2e332a7 | `00 00 00 08 09 a3 78 f9 b2 e3 32 a7`
/// | 80              | `00 00 00 02 00 80`
/// |-1234            | `00 00 00 02 ed cc`
/// | -deadbeef       | `00 00 00 05 ff 21 52 41 11`
#[derive(Clone, PartialOrd, Ord)]
pub struct Mpint {
    /// Inner big endian-serialized integer value
    inner: Box<[u8]>,
}

impl Mpint {
    /// Create a new multiple precision integer from the given
    /// big endian-encoded byte slice.
    ///
    /// Note that this method expects a leading zero on positive integers whose
    /// MSB is set, but does *NOT* expect a 4-byte length prefix.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self> {
        bytes.try_into()
    }

    /// Create a new multiple precision integer from the given big endian
    /// encoded byte slice representing a positive integer.
    ///
    /// The input may begin with leading zeros, which will be stripped when
    /// converted to [`Mpint`] encoding.
    pub fn from_positive_bytes(mut bytes: &[u8]) -> Result<Self> {
        let mut inner = Vec::with_capacity(bytes.len());

        while bytes.first().copied() == Some(0) {
            bytes = &bytes[1..];
        }

        match bytes.first().copied() {
            Some(n) if n >= 0x80 => inner.push(0),
            _ => (),
        }

        inner.extend_from_slice(bytes);
        inner.into_boxed_slice().try_into()
    }

    /// Get the big integer data encoded as big endian bytes.
    ///
    /// This slice will contain a leading zero if the value is positive but the
    /// MSB is also set. Use [`Mpint::as_positive_bytes`] to ensure the number
    /// is positive and strip the leading zero byte if it exists.
    pub fn as_bytes(&self) -> &[u8] {
        &self.inner
    }

    /// Get the bytes of a positive integer.
    ///
    /// # Returns
    /// - `Some(bytes)` if the number is positive. The leading zero byte will be stripped.
    /// - `None` if the value is negative
    pub fn as_positive_bytes(&self) -> Option<&[u8]> {
        match self.as_bytes() {
            [0x00, rest @ ..] => Some(rest),
            [byte, ..] if *byte < 0x80 => Some(self.as_bytes()),
            _ => None,
        }
    }
}

impl AsRef<[u8]> for Mpint {
    fn as_ref(&self) -> &[u8] {
        self.as_bytes()
    }
}

impl ConstantTimeEq for Mpint {
    fn ct_eq(&self, other: &Self) -> Choice {
        self.as_ref().ct_eq(other.as_ref())
    }
}

impl Eq for Mpint {}

impl PartialEq for Mpint {
    fn eq(&self, other: &Self) -> bool {
        self.ct_eq(other).into()
    }
}

impl Decode for Mpint {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        Vec::decode(reader)?.into_boxed_slice().try_into()
    }
}

impl Encode for Mpint {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [4, self.as_bytes().len()].checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.as_bytes().encode(writer)?;
        Ok(())
    }
}

impl TryFrom<&[u8]> for Mpint {
    type Error = Error;

    fn try_from(bytes: &[u8]) -> Result<Self> {
        Vec::from(bytes).into_boxed_slice().try_into()
    }
}

impl TryFrom<Box<[u8]>> for Mpint {
    type Error = Error;

    fn try_from(bytes: Box<[u8]>) -> Result<Self> {
        match &*bytes {
            // Unnecessary leading 0
            [0x00] => Err(Error::FormatEncoding),
            // Unnecessary leading 0
            [0x00, n, ..] if *n < 0x80 => Err(Error::FormatEncoding),
            _ => Ok(Self { inner: bytes }),
        }
    }
}

impl Zeroize for Mpint {
    fn zeroize(&mut self) {
        self.inner.zeroize();
    }
}

impl fmt::Debug for Mpint {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Mpint({self:X})")
    }
}

impl fmt::Display for Mpint {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:X}")
    }
}

impl fmt::LowerHex for Mpint {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.as_bytes() {
            write!(f, "{byte:02x}")?;
        }
        Ok(())
    }
}

impl fmt::UpperHex for Mpint {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.as_bytes() {
            write!(f, "{byte:02X}")?;
        }
        Ok(())
    }
}

#[cfg(any(feature = "dsa", feature = "rsa"))]
impl TryFrom<bigint::BigUint> for Mpint {
    type Error = Error;

    fn try_from(uint: bigint::BigUint) -> Result<Mpint> {
        Mpint::try_from(&uint)
    }
}

#[cfg(any(feature = "dsa", feature = "rsa"))]
impl TryFrom<&bigint::BigUint> for Mpint {
    type Error = Error;

    fn try_from(uint: &bigint::BigUint) -> Result<Mpint> {
        let bytes = Zeroizing::new(uint.to_bytes_be());
        Mpint::from_positive_bytes(bytes.as_slice())
    }
}

#[cfg(any(feature = "dsa", feature = "rsa"))]
impl TryFrom<Mpint> for bigint::BigUint {
    type Error = Error;

    fn try_from(mpint: Mpint) -> Result<bigint::BigUint> {
        bigint::BigUint::try_from(&mpint)
    }
}

#[cfg(any(feature = "dsa", feature = "rsa"))]
impl TryFrom<&Mpint> for bigint::BigUint {
    type Error = Error;

    fn try_from(mpint: &Mpint) -> Result<bigint::BigUint> {
        mpint
            .as_positive_bytes()
            .map(bigint::BigUint::from_bytes_be)
            .ok_or(Error::Crypto)
    }
}

#[cfg(test)]
mod tests {
    use super::Mpint;
    use hex_literal::hex;

    #[test]
    fn decode_0() {
        let n = Mpint::from_bytes(b"").unwrap();
        assert_eq!(b"", n.as_bytes())
    }

    #[test]
    fn reject_extra_leading_zeroes() {
        assert!(Mpint::from_bytes(&hex!("00")).is_err());
        assert!(Mpint::from_bytes(&hex!("00 00")).is_err());
        assert!(Mpint::from_bytes(&hex!("00 01")).is_err());
    }

    #[test]
    fn decode_9a378f9b2e332a7() {
        assert!(Mpint::from_bytes(&hex!("09 a3 78 f9 b2 e3 32 a7")).is_ok());
    }

    #[test]
    fn decode_80() {
        let n = Mpint::from_bytes(&hex!("00 80")).unwrap();

        // Leading zero stripped
        assert_eq!(&hex!("80"), n.as_positive_bytes().unwrap())
    }
    #[test]
    fn from_positive_bytes_strips_leading_zeroes() {
        assert_eq!(
            Mpint::from_positive_bytes(&hex!("00")).unwrap().as_ref(),
            b""
        );
        assert_eq!(
            Mpint::from_positive_bytes(&hex!("00 00")).unwrap().as_ref(),
            b""
        );
        assert_eq!(
            Mpint::from_positive_bytes(&hex!("00 01")).unwrap().as_ref(),
            b"\x01"
        );
    }

    // TODO(tarcieri): drop support for negative numbers?
    #[test]
    fn decode_neg_1234() {
        let n = Mpint::from_bytes(&hex!("ed cc")).unwrap();
        assert!(n.as_positive_bytes().is_none());
    }

    // TODO(tarcieri): drop support for negative numbers?
    #[test]
    fn decode_neg_deadbeef() {
        let n = Mpint::from_bytes(&hex!("ff 21 52 41 11")).unwrap();
        assert!(n.as_positive_bytes().is_none());
    }
}
