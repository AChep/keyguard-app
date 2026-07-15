//! Convenience trait for decoding/encoding string labels.

use crate::{Decode, Encode, Error, Reader, Writer};
use core::{fmt, str::FromStr};

#[cfg(feature = "alloc")]
use alloc::string::String;

/// Maximum size of any algorithm name/identifier.
const MAX_LABEL_SIZE: usize = 48;

/// Labels for e.g. cryptographic algorithms.
///
/// Receives a blanket impl of [`Decode`] and [`Encode`].
pub trait Label: AsRef<str> + FromStr<Err = LabelError> {}

impl<T: Label> Decode for T {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self, Error> {
        let mut buf = [0u8; MAX_LABEL_SIZE];
        Ok(reader.read_string(buf.as_mut())?.parse()?)
    }
}

impl<T: Label> Encode for T {
    fn encoded_len(&self) -> Result<usize, Error> {
        self.as_ref().encoded_len()
    }

    fn encode(&self, writer: &mut impl Writer) -> Result<(), Error> {
        self.as_ref().encode(writer)
    }
}

/// Errors related to labels.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct LabelError {
    /// The label that was considered invalid.
    #[cfg(feature = "alloc")]
    label: String,
}

impl LabelError {
    /// Create a new [`LabelError`] for the given invalid label.
    #[cfg_attr(not(feature = "alloc"), allow(unused_variables))]
    pub fn new(label: &str) -> Self {
        Self {
            #[cfg(feature = "alloc")]
            label: label.into(),
        }
    }

    /// The invalid label string (if available).
    #[inline]
    pub fn label(&self) -> &str {
        #[cfg(not(feature = "alloc"))]
        {
            ""
        }
        #[cfg(feature = "alloc")]
        {
            &self.label
        }
    }
}

impl fmt::Display for LabelError {
    #[cfg(not(feature = "alloc"))]
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("invalid label")
    }

    #[cfg(feature = "alloc")]
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "invalid label: '{}'", self.label)
    }
}

#[cfg(feature = "std")]
impl std::error::Error for LabelError {}
