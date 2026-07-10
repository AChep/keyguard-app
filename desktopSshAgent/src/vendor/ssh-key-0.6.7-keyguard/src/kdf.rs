//! Key Derivation Functions.
//!
//! These are used for deriving an encryption key from a password.

use crate::{Error, KdfAlg, Result};
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};

#[cfg(feature = "alloc")]
use alloc::vec::Vec;

#[cfg(feature = "encryption")]
use {crate::Cipher, bcrypt_pbkdf::bcrypt_pbkdf, rand_core::CryptoRngCore, zeroize::Zeroizing};

/// Default number of rounds to use for bcrypt-pbkdf.
#[cfg(feature = "encryption")]
const DEFAULT_BCRYPT_ROUNDS: u32 = 16;

/// Default salt size. Matches OpenSSH.
#[cfg(feature = "encryption")]
const DEFAULT_SALT_SIZE: usize = 16;

/// Key Derivation Functions (KDF).
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum Kdf {
    /// No KDF.
    None,

    /// bcrypt-pbkdf options.
    #[cfg(feature = "alloc")]
    Bcrypt {
        /// Salt
        salt: Vec<u8>,

        /// Rounds
        rounds: u32,
    },
}

impl Kdf {
    /// Initialize KDF configuration for the given algorithm.
    #[cfg(feature = "encryption")]
    pub fn new(algorithm: KdfAlg, rng: &mut impl CryptoRngCore) -> Result<Self> {
        let mut salt = vec![0u8; DEFAULT_SALT_SIZE];
        rng.fill_bytes(&mut salt);

        match algorithm {
            KdfAlg::None => {
                // Disallow explicit initialization with a `none` algorithm
                Err(Error::AlgorithmUnknown)
            }
            KdfAlg::Bcrypt => Ok(Kdf::Bcrypt {
                salt,
                rounds: DEFAULT_BCRYPT_ROUNDS,
            }),
        }
    }

    /// Get the KDF algorithm.
    pub fn algorithm(&self) -> KdfAlg {
        match self {
            Self::None => KdfAlg::None,
            #[cfg(feature = "alloc")]
            Self::Bcrypt { .. } => KdfAlg::Bcrypt,
        }
    }

    /// Derive an encryption key from the given password.
    #[cfg(feature = "encryption")]
    pub fn derive(&self, password: impl AsRef<[u8]>, output: &mut [u8]) -> Result<()> {
        match self {
            Kdf::None => Err(Error::Decrypted),
            Kdf::Bcrypt { salt, rounds } => {
                bcrypt_pbkdf(password, salt, *rounds, output).map_err(|_| Error::Crypto)?;
                Ok(())
            }
        }
    }

    /// Derive key and IV for the given [`Cipher`].
    ///
    /// Returns two byte vectors containing the key and IV respectively.
    #[cfg(feature = "encryption")]
    pub fn derive_key_and_iv(
        &self,
        cipher: Cipher,
        password: impl AsRef<[u8]>,
    ) -> Result<(Zeroizing<Vec<u8>>, Vec<u8>)> {
        let (key_size, iv_size) = cipher.key_and_iv_size().ok_or(Error::Decrypted)?;
        let okm_size = key_size
            .checked_add(iv_size)
            .ok_or(encoding::Error::Length)?;

        let mut okm = Zeroizing::new(vec![0u8; okm_size]);
        self.derive(password, &mut okm)?;
        let iv = okm.split_off(key_size);
        Ok((okm, iv))
    }

    /// Is the KDF configured as `none`?
    pub fn is_none(&self) -> bool {
        self == &Self::None
    }

    /// Is the KDF configured as anything other than `none`?
    pub fn is_some(&self) -> bool {
        !self.is_none()
    }

    /// Is the KDF configured as `bcrypt` (i.e. bcrypt-pbkdf)?
    #[cfg(feature = "alloc")]
    pub fn is_bcrypt(&self) -> bool {
        matches!(self, Self::Bcrypt { .. })
    }
}

impl Default for Kdf {
    fn default() -> Self {
        Self::None
    }
}

impl Decode for Kdf {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        match KdfAlg::decode(reader)? {
            KdfAlg::None => {
                if usize::decode(reader)? == 0 {
                    Ok(Self::None)
                } else {
                    Err(Error::AlgorithmUnknown)
                }
            }
            KdfAlg::Bcrypt => {
                #[cfg(not(feature = "alloc"))]
                return Err(Error::AlgorithmUnknown);

                #[cfg(feature = "alloc")]
                reader.read_prefixed(|reader| {
                    Ok(Self::Bcrypt {
                        salt: Vec::decode(reader)?,
                        rounds: u32::decode(reader)?,
                    })
                })
            }
        }
    }
}

impl Encode for Kdf {
    fn encoded_len(&self) -> encoding::Result<usize> {
        let kdfopts_prefixed_len = match self {
            Self::None => 4,
            #[cfg(feature = "alloc")]
            Self::Bcrypt { salt, .. } => [12, salt.len()].checked_sum()?,
        };

        [self.algorithm().encoded_len()?, kdfopts_prefixed_len].checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.algorithm().encode(writer)?;

        match self {
            Self::None => 0usize.encode(writer)?,
            #[cfg(feature = "alloc")]
            Self::Bcrypt { salt, rounds } => {
                [8, salt.len()].checked_sum()?.encode(writer)?;
                salt.encode(writer)?;
                rounds.encode(writer)?
            }
        }

        Ok(())
    }
}
