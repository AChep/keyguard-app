//! Security Key (FIDO/U2F) private keys as described in [PROTOCOL.u2f].
//!
//! [PROTOCOL.u2f]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.u2f?annotate=HEAD

use crate::{public, Error, Result};
use alloc::vec::Vec;
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};

/// Security Key (FIDO/U2F) ECDSA/NIST P-256 private key as specified in
/// [PROTOCOL.u2f](https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.u2f?annotate=HEAD).
#[cfg(all(feature = "alloc", feature = "ecdsa"))]
#[derive(Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub struct SkEcdsaSha2NistP256 {
    /// Public key.
    public: public::SkEcdsaSha2NistP256,

    /// Flags.
    flags: u8,

    /// FIDO/U2F key handle.
    key_handle: Vec<u8>,

    /// Reserved data.
    reserved: Vec<u8>,
}

#[cfg(feature = "ecdsa")]
impl SkEcdsaSha2NistP256 {
    /// Construct new instance of SkEcdsaSha2NistP256.
    #[cfg(feature = "alloc")]
    pub fn new(
        public: public::SkEcdsaSha2NistP256,
        flags: u8,
        key_handle: impl Into<Vec<u8>>,
    ) -> Result<Self> {
        let key_handle = key_handle.into();

        if key_handle.len() <= 255 {
            Ok(SkEcdsaSha2NistP256 {
                public,
                flags,
                key_handle,
                reserved: Vec::<u8>::new(),
            })
        } else {
            Err(encoding::Error::Length.into())
        }
    }

    /// Get the ECDSA/NIST P-256 public key.
    pub fn public(&self) -> &public::SkEcdsaSha2NistP256 {
        &self.public
    }

    /// Get flags.
    pub fn flags(&self) -> u8 {
        self.flags
    }

    /// Get FIDO/U2F key handle.
    pub fn key_handle(&self) -> &[u8] {
        &self.key_handle
    }
}

#[cfg(feature = "ecdsa")]
impl Decode for SkEcdsaSha2NistP256 {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        Ok(Self {
            public: public::SkEcdsaSha2NistP256::decode(reader)?,
            flags: u8::decode(reader)?,
            key_handle: Vec::decode(reader)?,
            reserved: Vec::decode(reader)?,
        })
    }
}

#[cfg(feature = "ecdsa")]
impl Encode for SkEcdsaSha2NistP256 {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [
            self.public.encoded_len()?,
            self.flags.encoded_len()?,
            self.key_handle.encoded_len()?,
            self.reserved.encoded_len()?,
        ]
        .checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.public.encode(writer)?;
        self.flags.encode(writer)?;
        self.key_handle.encode(writer)?;
        self.reserved.encode(writer)?;
        Ok(())
    }
}

/// Security Key (FIDO/U2F) Ed25519 private key as specified in
/// [PROTOCOL.u2f](https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.u2f?annotate=HEAD).
#[cfg(feature = "alloc")]
#[derive(Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub struct SkEd25519 {
    /// Public key.
    public: public::SkEd25519,

    /// Flags.
    flags: u8,

    /// FIDO/U2F key handle.
    key_handle: Vec<u8>,

    /// Reserved data.
    reserved: Vec<u8>,
}

impl SkEd25519 {
    /// Construct new instance of SkEd25519.
    #[cfg(feature = "alloc")]
    pub fn new(
        public: public::SkEd25519,
        flags: u8,
        key_handle: impl Into<Vec<u8>>,
    ) -> Result<Self> {
        let key_handle = key_handle.into();

        if key_handle.len() <= 255 {
            Ok(SkEd25519 {
                public,
                flags,
                key_handle,
                reserved: Vec::<u8>::new(),
            })
        } else {
            Err(encoding::Error::Length.into())
        }
    }

    /// Get the Ed25519 public key.
    pub fn public(&self) -> &public::SkEd25519 {
        &self.public
    }

    /// Get flags.
    pub fn flags(&self) -> u8 {
        self.flags
    }

    /// Get FIDO/U2F key handle.
    pub fn key_handle(&self) -> &[u8] {
        &self.key_handle
    }
}

impl Decode for SkEd25519 {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        Ok(Self {
            public: public::SkEd25519::decode(reader)?,
            flags: u8::decode(reader)?,
            key_handle: Vec::decode(reader)?,
            reserved: Vec::decode(reader)?,
        })
    }
}

impl Encode for SkEd25519 {
    fn encoded_len(&self) -> encoding::Result<usize> {
        [
            self.public.encoded_len()?,
            self.flags.encoded_len()?,
            self.key_handle.encoded_len()?,
            self.reserved.encoded_len()?,
        ]
        .checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.public.encode(writer)?;
        self.flags.encode(writer)?;
        self.key_handle.encode(writer)?;
        self.reserved.encode(writer)?;
        Ok(())
    }
}
