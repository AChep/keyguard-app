#![no_std]
#![cfg_attr(docsrs, feature(doc_auto_cfg))]
#![doc = include_str!("../README.md")]
#![doc(
    html_logo_url = "https://raw.githubusercontent.com/RustCrypto/media/6ee8e381/logo.svg",
    html_favicon_url = "https://raw.githubusercontent.com/RustCrypto/media/6ee8e381/logo.svg"
)]
#![forbid(unsafe_code)]
#![warn(
    clippy::arithmetic_side_effects,
    clippy::panic,
    clippy::panic_in_result_fn,
    clippy::unwrap_used,
    missing_docs,
    rust_2018_idioms,
    unused_lifetimes,
    unused_qualifications
)]

#[cfg(feature = "std")]
extern crate std;

mod error;

#[cfg(feature = "chacha20poly1305")]
mod chacha20poly1305;

pub use crate::error::{Error, Result};

use core::{fmt, str};
use encoding::{Label, LabelError};

#[cfg(feature = "aes-ctr")]
use cipher::StreamCipherCore;

#[cfg(feature = "aes-gcm")]
use aes_gcm::{aead::AeadInPlace, Aes128Gcm, Aes256Gcm};

#[cfg(feature = "chacha20poly1305")]
use crate::chacha20poly1305::ChaCha20Poly1305;

#[cfg(any(feature = "aes-cbc", feature = "aes-ctr"))]
use aes::{Aes128, Aes192, Aes256};

#[cfg(any(feature = "aes-cbc", feature = "tdes"))]
use {
    cbc::{Decryptor, Encryptor},
    cipher::{block_padding::NoPadding, BlockCipher, BlockDecryptMut, BlockEncryptMut},
};

#[cfg(any(feature = "aes-cbc", feature = "aes-gcm", feature = "tdes"))]
use cipher::KeyInit;

#[cfg(any(feature = "aes-cbc", feature = "aes-ctr", feature = "tdes"))]
use cipher::KeyIvInit;

#[cfg(feature = "tdes")]
use des::TdesEde3;

/// AES-128 in block chaining (CBC) mode
const AES128_CBC: &str = "aes128-cbc";

/// AES-192 in block chaining (CBC) mode
const AES192_CBC: &str = "aes192-cbc";

/// AES-256 in block chaining (CBC) mode
const AES256_CBC: &str = "aes256-cbc";

/// AES-128 in counter (CTR) mode
const AES128_CTR: &str = "aes128-ctr";

/// AES-192 in counter (CTR) mode
const AES192_CTR: &str = "aes192-ctr";

/// AES-256 in counter (CTR) mode
const AES256_CTR: &str = "aes256-ctr";

/// AES-128 in Galois/Counter Mode (GCM).
const AES128_GCM: &str = "aes128-gcm@openssh.com";

/// AES-256 in Galois/Counter Mode (GCM).
const AES256_GCM: &str = "aes256-gcm@openssh.com";

/// ChaCha20-Poly1305
const CHACHA20_POLY1305: &str = "chacha20-poly1305@openssh.com";

/// Triple-DES in block chaining (CBC) mode
const TDES_CBC: &str = "3des-cbc";

/// Nonce for AEAD modes.
///
/// This is used by e.g. `aes128-gcm@openssh.com`/`aes256-gcm@openssh.com` and
/// `chacha20-poly1305@openssh.com`.
pub type Nonce = [u8; 12];

/// Authentication tag for ciphertext data.
///
/// This is used by e.g. `aes128-gcm@openssh.com`/`aes256-gcm@openssh.com` and
/// `chacha20-poly1305@openssh.com`.
pub type Tag = [u8; 16];

/// Counter mode with a 32-bit big endian counter.
#[cfg(feature = "aes-ctr")]
type Ctr128BE<Cipher> = ctr::CtrCore<Cipher, ctr::flavors::Ctr128BE>;

/// Cipher algorithms.
#[derive(Copy, Clone, Debug, Eq, Hash, PartialEq, PartialOrd, Ord)]
#[non_exhaustive]
pub enum Cipher {
    /// No cipher.
    None,

    /// AES-128 in cipher block chaining (CBC) mode.
    Aes128Cbc,

    /// AES-192 in cipher block chaining (CBC) mode.
    Aes192Cbc,

    /// AES-256 in cipher block chaining (CBC) mode.
    Aes256Cbc,

    /// AES-128 in counter (CTR) mode.
    Aes128Ctr,

    /// AES-192 in counter (CTR) mode.
    Aes192Ctr,

    /// AES-256 in counter (CTR) mode.
    Aes256Ctr,

    /// AES-128 in Galois/Counter Mode (GCM).
    Aes128Gcm,

    /// AES-256 in Galois/Counter Mode (GCM).
    Aes256Gcm,

    /// ChaCha20-Poly1305
    ChaCha20Poly1305,

    /// TripleDES in block chaining (CBC) mode
    TDesCbc,
}

impl Cipher {
    /// Decode cipher algorithm from the given `ciphername`.
    ///
    /// # Supported cipher names
    /// - `aes256-ctr`
    pub fn new(ciphername: &str) -> core::result::Result<Self, LabelError> {
        ciphername.parse()
    }

    /// Get the string identifier which corresponds to this algorithm.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::Aes128Cbc => AES128_CBC,
            Self::Aes192Cbc => AES192_CBC,
            Self::Aes256Cbc => AES256_CBC,
            Self::Aes128Ctr => AES128_CTR,
            Self::Aes192Ctr => AES192_CTR,
            Self::Aes256Ctr => AES256_CTR,
            Self::Aes128Gcm => AES128_GCM,
            Self::Aes256Gcm => AES256_GCM,
            Self::ChaCha20Poly1305 => CHACHA20_POLY1305,
            Self::TDesCbc => TDES_CBC,
        }
    }

    /// Get the key and IV size for this cipher in bytes.
    pub fn key_and_iv_size(self) -> Option<(usize, usize)> {
        match self {
            Self::None => None,
            Self::Aes128Cbc => Some((16, 16)),
            Self::Aes192Cbc => Some((24, 16)),
            Self::Aes256Cbc => Some((32, 16)),
            Self::Aes128Ctr => Some((16, 16)),
            Self::Aes192Ctr => Some((24, 16)),
            Self::Aes256Ctr => Some((32, 16)),
            Self::Aes128Gcm => Some((16, 12)),
            Self::Aes256Gcm => Some((32, 12)),
            Self::ChaCha20Poly1305 => Some((64, 0)),
            Self::TDesCbc => Some((24, 8)),
        }
    }

    /// Get the block size for this cipher in bytes.
    pub fn block_size(self) -> usize {
        match self {
            Self::None | Self::ChaCha20Poly1305 | Self::TDesCbc => 8,
            Self::Aes128Cbc
            | Self::Aes192Cbc
            | Self::Aes256Cbc
            | Self::Aes128Ctr
            | Self::Aes192Ctr
            | Self::Aes256Ctr
            | Self::Aes128Gcm
            | Self::Aes256Gcm => 16,
        }
    }

    /// Compute the length of padding necessary to pad the given input to
    /// the block size.
    #[allow(clippy::arithmetic_side_effects)]
    pub fn padding_len(self, input_size: usize) -> usize {
        match input_size % self.block_size() {
            0 => 0,
            input_rem => self.block_size() - input_rem,
        }
    }

    /// Does this cipher have an authentication tag? (i.e. is it an AEAD mode?)
    pub fn has_tag(self) -> bool {
        matches!(
            self,
            Self::Aes128Gcm | Self::Aes256Gcm | Self::ChaCha20Poly1305
        )
    }

    /// Is this cipher `none`?
    pub fn is_none(self) -> bool {
        self == Self::None
    }

    /// Is the cipher anything other than `none`?
    pub fn is_some(self) -> bool {
        !self.is_none()
    }

    /// Decrypt the ciphertext in the `buffer` in-place using this cipher.
    pub fn decrypt(self, key: &[u8], iv: &[u8], buffer: &mut [u8], tag: Option<Tag>) -> Result<()> {
        match self {
            #[cfg(feature = "aes-cbc")]
            Self::Aes128Cbc => {
                if tag.is_some() {
                    return Err(Error::TagSize);
                }
                cbc_decrypt::<Aes128>(key, iv, buffer)
            }
            #[cfg(feature = "aes-cbc")]
            Self::Aes192Cbc => {
                if tag.is_some() {
                    return Err(Error::TagSize);
                }
                cbc_decrypt::<Aes192>(key, iv, buffer)
            }
            #[cfg(feature = "aes-cbc")]
            Self::Aes256Cbc => {
                if tag.is_some() {
                    return Err(Error::TagSize);
                }
                cbc_decrypt::<Aes256>(key, iv, buffer)
            }
            #[cfg(feature = "aes-ctr")]
            Self::Aes128Ctr | Self::Aes192Ctr | Self::Aes256Ctr => {
                if tag.is_some() {
                    return Err(Error::TagSize);
                }

                // Counter mode encryption and decryption are the same operation
                self.encrypt(key, iv, buffer)?;
                Ok(())
            }
            #[cfg(feature = "aes-gcm")]
            Self::Aes128Gcm => {
                let cipher = Aes128Gcm::new_from_slice(key).map_err(|_| Error::KeySize)?;
                if iv.len() != Nonce::default().len() {
                    return Err(Error::IvSize);
                }
                let nonce = aes_gcm::Nonce::from_slice(iv);
                let tag = tag.ok_or(Error::TagSize)?;
                cipher
                    .decrypt_in_place_detached(nonce, &[], buffer, &tag.into())
                    .map_err(|_| Error::Crypto)?;

                Ok(())
            }
            #[cfg(feature = "aes-gcm")]
            Self::Aes256Gcm => {
                let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| Error::KeySize)?;
                if iv.len() != Nonce::default().len() {
                    return Err(Error::IvSize);
                }
                let nonce = aes_gcm::Nonce::from_slice(iv);
                let tag = tag.ok_or(Error::TagSize)?;
                cipher
                    .decrypt_in_place_detached(nonce, &[], buffer, &tag.into())
                    .map_err(|_| Error::Crypto)?;

                Ok(())
            }
            #[cfg(feature = "chacha20poly1305")]
            Self::ChaCha20Poly1305 => {
                let tag = tag.ok_or(Error::TagSize)?;
                ChaCha20Poly1305::new(key, iv)?.decrypt(buffer, tag)
            }
            #[cfg(feature = "tdes")]
            Self::TDesCbc => {
                if tag.is_some() {
                    return Err(Error::TagSize);
                }
                cbc_decrypt::<TdesEde3>(key, iv, buffer)
            }
            _ => {
                // Suppress unused variable warnings.
                let (_, _, _, _) = (key, iv, buffer, tag);
                Err(self.unsupported())
            }
        }
    }

    /// Encrypt the ciphertext in the `buffer` in-place using this cipher.
    pub fn encrypt(self, key: &[u8], iv: &[u8], buffer: &mut [u8]) -> Result<Option<Tag>> {
        match self {
            #[cfg(feature = "aes-cbc")]
            Self::Aes128Cbc => {
                cbc_encrypt::<Aes128>(key, iv, buffer)?;
                Ok(None)
            }
            #[cfg(feature = "aes-cbc")]
            Self::Aes192Cbc => {
                cbc_encrypt::<Aes192>(key, iv, buffer)?;
                Ok(None)
            }
            #[cfg(feature = "aes-cbc")]
            Self::Aes256Cbc => {
                cbc_encrypt::<Aes256>(key, iv, buffer)?;
                Ok(None)
            }
            #[cfg(feature = "aes-ctr")]
            Self::Aes128Ctr => {
                ctr_encrypt::<Ctr128BE<Aes128>>(key, iv, buffer)?;
                Ok(None)
            }
            #[cfg(feature = "aes-ctr")]
            Self::Aes192Ctr => {
                ctr_encrypt::<Ctr128BE<Aes192>>(key, iv, buffer)?;
                Ok(None)
            }
            #[cfg(feature = "aes-ctr")]
            Self::Aes256Ctr => {
                ctr_encrypt::<Ctr128BE<Aes256>>(key, iv, buffer)?;
                Ok(None)
            }
            #[cfg(feature = "aes-gcm")]
            Self::Aes128Gcm => {
                let cipher = Aes128Gcm::new_from_slice(key).map_err(|_| Error::KeySize)?;
                if iv.len() != Nonce::default().len() {
                    return Err(Error::IvSize);
                }
                let nonce = aes_gcm::Nonce::from_slice(iv);
                let tag = cipher
                    .encrypt_in_place_detached(nonce, &[], buffer)
                    .map_err(|_| Error::Crypto)?;

                Ok(Some(tag.into()))
            }
            #[cfg(feature = "aes-gcm")]
            Self::Aes256Gcm => {
                let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| Error::KeySize)?;
                if iv.len() != Nonce::default().len() {
                    return Err(Error::IvSize);
                }
                let nonce = aes_gcm::Nonce::from_slice(iv);
                let tag = cipher
                    .encrypt_in_place_detached(nonce, &[], buffer)
                    .map_err(|_| Error::Crypto)?;

                Ok(Some(tag.into()))
            }
            #[cfg(feature = "chacha20poly1305")]
            Self::ChaCha20Poly1305 => {
                let tag = ChaCha20Poly1305::new(key, iv)?.encrypt(buffer);
                Ok(Some(tag))
            }
            #[cfg(feature = "tdes")]
            Self::TDesCbc => {
                cbc_encrypt::<TdesEde3>(key, iv, buffer)?;
                Ok(None)
            }
            _ => {
                // Suppress unused variable warnings.
                let (_, _, _) = (key, iv, buffer);
                Err(self.unsupported())
            }
        }
    }

    /// Create an unsupported cipher error.
    fn unsupported(self) -> Error {
        Error::UnsupportedCipher(self)
    }
}

impl AsRef<str> for Cipher {
    fn as_ref(&self) -> &str {
        self.as_str()
    }
}

impl Label for Cipher {}

impl fmt::Display for Cipher {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl str::FromStr for Cipher {
    type Err = LabelError;

    fn from_str(ciphername: &str) -> core::result::Result<Self, LabelError> {
        match ciphername {
            "none" => Ok(Self::None),
            AES128_CBC => Ok(Self::Aes128Cbc),
            AES192_CBC => Ok(Self::Aes192Cbc),
            AES256_CBC => Ok(Self::Aes256Cbc),
            AES128_CTR => Ok(Self::Aes128Ctr),
            AES192_CTR => Ok(Self::Aes192Ctr),
            AES256_CTR => Ok(Self::Aes256Ctr),
            AES128_GCM => Ok(Self::Aes128Gcm),
            AES256_GCM => Ok(Self::Aes256Gcm),
            CHACHA20_POLY1305 => Ok(Self::ChaCha20Poly1305),
            TDES_CBC => Ok(Self::TDesCbc),
            _ => Err(LabelError::new(ciphername)),
        }
    }
}

#[cfg(any(feature = "aes-cbc", feature = "tdes"))]
fn cbc_encrypt<C>(key: &[u8], iv: &[u8], buffer: &mut [u8]) -> Result<()>
where
    C: BlockEncryptMut + BlockCipher + KeyInit,
{
    let cipher = Encryptor::<C>::new_from_slices(key, iv).map_err(|_| Error::KeySize)?;

    // Since the passed in buffer is already padded, using NoPadding here
    cipher
        .encrypt_padded_mut::<NoPadding>(buffer, buffer.len())
        .map_err(|_| Error::Crypto)?;

    Ok(())
}

#[cfg(any(feature = "aes-cbc", feature = "tdes"))]
fn cbc_decrypt<C>(key: &[u8], iv: &[u8], buffer: &mut [u8]) -> Result<()>
where
    C: BlockDecryptMut + BlockCipher + KeyInit,
{
    let cipher = Decryptor::<C>::new_from_slices(key, iv).map_err(|_| Error::KeySize)?;

    // Since the passed in buffer is already padded, using NoPadding here
    cipher
        .decrypt_padded_mut::<NoPadding>(buffer)
        .map_err(|_| Error::Crypto)?;

    Ok(())
}

#[cfg(feature = "aes-ctr")]
fn ctr_encrypt<C>(key: &[u8], iv: &[u8], buffer: &mut [u8]) -> Result<()>
where
    C: StreamCipherCore + KeyIvInit,
{
    let cipher = C::new_from_slices(key, iv).map_err(|_| Error::KeySize)?;

    cipher
        .try_apply_keystream_partial(buffer.into())
        .map_err(|_| Error::Crypto)?;

    Ok(())
}
