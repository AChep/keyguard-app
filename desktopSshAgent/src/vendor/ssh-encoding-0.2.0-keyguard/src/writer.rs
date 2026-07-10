//! Writer trait and associated implementations.

use crate::Result;

#[cfg(feature = "alloc")]
use alloc::vec::Vec;

#[cfg(feature = "sha2")]
use sha2::{Digest, Sha256, Sha512};

/// Constant-time Base64 writer implementation.
#[cfg(feature = "base64")]
pub type Base64Writer<'o> = base64::Encoder<'o, base64::Base64>;

/// Writer trait which encodes the SSH binary format to various output
/// encodings.
pub trait Writer: Sized {
    /// Write the given bytes to the writer.
    fn write(&mut self, bytes: &[u8]) -> Result<()>;
}

#[cfg(feature = "alloc")]
impl Writer for Vec<u8> {
    fn write(&mut self, bytes: &[u8]) -> Result<()> {
        self.extend_from_slice(bytes);
        Ok(())
    }
}

#[cfg(feature = "base64")]
impl Writer for Base64Writer<'_> {
    fn write(&mut self, bytes: &[u8]) -> Result<()> {
        Ok(self.encode(bytes)?)
    }
}

#[cfg(feature = "pem")]
impl Writer for pem::Encoder<'_, '_> {
    fn write(&mut self, bytes: &[u8]) -> Result<()> {
        Ok(self.encode(bytes)?)
    }
}

#[cfg(feature = "sha2")]
impl Writer for Sha256 {
    fn write(&mut self, bytes: &[u8]) -> Result<()> {
        self.update(bytes);
        Ok(())
    }
}

#[cfg(feature = "sha2")]
impl Writer for Sha512 {
    fn write(&mut self, bytes: &[u8]) -> Result<()> {
        self.update(bytes);
        Ok(())
    }
}
