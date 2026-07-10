//! SSH private key support.
//!
//! Support for decoding SSH private keys (i.e. digital signature keys)
//! from the OpenSSH file format:
//!
//! <https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.key?annotate=HEAD>
//!
//! ## Decrypting encrypted private keys
//!
//! When the `encryption` feature of this crate is enabled, it's possible to
//! decrypt keys which have been encrypted under a password:
//!
#![cfg_attr(all(feature = "encryption", feature = "std"), doc = " ```")]
#![cfg_attr(not(all(feature = "encryption", feature = "std")), doc = " ```ignore")]
//! # fn main() -> Result<(), Box<dyn std::error::Error>> {
//! use ssh_key::PrivateKey;
//!
//! // WARNING: don't actually hardcode private keys in source code!!!
//! let encoded_key = r#"
//! -----BEGIN OPENSSH PRIVATE KEY-----
//! b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABBKH96ujW
//! umB6/WnTNPjTeaAAAAEAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN
//! 796jTiQfZfG1KaT0PtFDJ/XFSqtiAAAAoFzvbvyFMhAiwBOXF0mhUUacPUCMZXivG2up2c
//! hEnAw1b6BLRPyWbY5cC2n9ggD4ivJ1zSts6sBgjyiXQAReyrP35myYvT/OIB/NpwZM/xIJ
//! N7MHSUzlkX4adBrga3f7GS4uv4ChOoxC4XsE5HsxtGsq1X8jzqLlZTmOcxkcEneYQexrUc
//! bQP0o+gL5aKK8cQgiIlXeDbRjqhc4+h4EF6lY=
//! -----END OPENSSH PRIVATE KEY-----
//! "#;
//!
//! let encrypted_key = PrivateKey::from_openssh(encoded_key)?;
//! assert!(encrypted_key.is_encrypted());
//!
//! // WARNING: don't hardcode passwords, and this one's bad anyway
//! let password = "hunter42";
//!
//! let decrypted_key = encrypted_key.decrypt(password)?;
//! assert!(!decrypted_key.is_encrypted());
//! # Ok(())
//! # }
//! ```
//!
//! ## Encrypting plaintext private keys
//!
//! When the `encryption` feature of this crate is enabled, it's possible to
//! encrypt plaintext private keys under a provided password.
//!
//! The example below also requires enabling this crate's `getrandom` feature.
//!
#![cfg_attr(
    all(
        feature = "ed25519",
        feature = "encryption",
        feature = "getrandom",
        feature = "std"
    ),
    doc = " ```"
)]
#![cfg_attr(
    not(all(
        feature = "ed25519",
        feature = "encryption",
        feature = "getrandom",
        feature = "std"
    )),
    doc = " ```ignore"
)]
//! # fn main() -> Result<(), Box<dyn std::error::Error>> {
//! use ssh_key::{Algorithm, PrivateKey, rand_core::OsRng};
//!
//! // Generate a random key
//! let unencrypted_key = PrivateKey::random(&mut OsRng, Algorithm::Ed25519)?;
//!
//! // WARNING: don't hardcode passwords, and this one's bad anyway
//! let password = "hunter42";
//!
//! let encrypted_key = unencrypted_key.encrypt(&mut OsRng, password)?;
//! assert!(encrypted_key.is_encrypted());
//! # Ok(())
//! # }
//! ```
//!
//! ## Generating random keys
//!
//! This crate supports generation of random keys using algorithm-specific
//! backends gated on cargo features.
//!
//! The examples below require enabling this crate's `getrandom` feature as
//! well as the crate feature identified in backticks in the title of each
//! example.
//!
#![cfg_attr(
    all(feature = "ed25519", feature = "getrandom", feature = "std"),
    doc = " ```"
)]
#![cfg_attr(
    not(all(feature = "ed25519", feature = "getrandom", feature = "std")),
    doc = " ```ignore"
)]
//! # fn main() -> Result<(), Box<dyn std::error::Error>> {
//! use ssh_key::{Algorithm, PrivateKey, rand_core::OsRng};
//!
//! let private_key = PrivateKey::random(&mut OsRng, Algorithm::Ed25519)?;
//! # Ok(())
//! # }
//! ```

#[cfg(feature = "alloc")]
mod dsa;
#[cfg(feature = "ecdsa")]
mod ecdsa;
mod ed25519;
mod keypair;
#[cfg(feature = "alloc")]
mod opaque;
#[cfg(feature = "alloc")]
mod rsa;
#[cfg(feature = "alloc")]
mod sk;

pub use self::{
    ed25519::{Ed25519Keypair, Ed25519PrivateKey},
    keypair::KeypairData,
};

#[cfg(feature = "alloc")]
pub use crate::{
    private::{
        dsa::{DsaKeypair, DsaPrivateKey},
        opaque::{OpaqueKeypair, OpaqueKeypairBytes, OpaquePrivateKeyBytes},
        rsa::{RsaKeypair, RsaPrivateKey},
        sk::SkEd25519,
    },
    SshSig,
};

#[cfg(feature = "ecdsa")]
pub use self::ecdsa::{EcdsaKeypair, EcdsaPrivateKey};

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
pub use self::sk::SkEcdsaSha2NistP256;

use crate::{public, Algorithm, Cipher, Error, Fingerprint, HashAlg, Kdf, PublicKey, Result};
use cipher::Tag;
use core::str;
use encoding::{
    pem::{LineEnding, PemLabel},
    CheckedSum, Decode, DecodePem, Encode, EncodePem, Reader, Writer,
};
use subtle::{Choice, ConstantTimeEq};

#[cfg(feature = "alloc")]
use {
    alloc::{string::String, vec::Vec},
    zeroize::Zeroizing,
};

#[cfg(feature = "rand_core")]
use rand_core::CryptoRngCore;

#[cfg(feature = "std")]
use std::{fs, path::Path};

#[cfg(all(unix, feature = "std"))]
use std::{io::Write, os::unix::fs::OpenOptionsExt};

/// Error message for infallible conversions (used by `expect`)
const CONVERSION_ERROR_MSG: &str = "SSH private key conversion error";

/// Default key size to use for RSA keys in bits.
#[cfg(all(feature = "rand_core", feature = "rsa"))]
const DEFAULT_RSA_KEY_SIZE: usize = 4096;

/// Maximum supported block size.
///
/// This is the block size used by e.g. AES.
const MAX_BLOCK_SIZE: usize = 16;

/// Padding bytes to use.
const PADDING_BYTES: [u8; MAX_BLOCK_SIZE - 1] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];

/// Unix file permissions for SSH private keys.
#[cfg(all(unix, feature = "std"))]
const UNIX_FILE_PERMISSIONS: u32 = 0o600;

/// SSH private key.
#[derive(Clone, Debug)]
pub struct PrivateKey {
    /// Cipher algorithm.
    cipher: Cipher,

    /// KDF options.
    kdf: Kdf,

    /// "Checkint" value used to verify successful decryption.
    checkint: Option<u32>,

    /// Public key.
    public_key: PublicKey,

    /// Private keypair data.
    key_data: KeypairData,

    /// Authentication tag for authenticated encryption modes.
    auth_tag: Option<Tag>,
}

impl PrivateKey {
    /// Magic string used to identify keys in this format.
    const AUTH_MAGIC: &'static [u8] = b"openssh-key-v1\0";

    /// Create a new unencrypted private key with the given keypair data and comment.
    ///
    /// On `no_std` platforms, use `PrivateKey::from(key_data)` instead.
    #[cfg(feature = "alloc")]
    pub fn new(key_data: KeypairData, comment: impl Into<String>) -> Result<Self> {
        if key_data.is_encrypted() {
            return Err(Error::Encrypted);
        }

        let mut private_key = Self::try_from(key_data)?;
        private_key.public_key.comment = comment.into();
        Ok(private_key)
    }

    /// Parse an OpenSSH-formatted PEM private key.
    ///
    /// OpenSSH-formatted private keys begin with the following:
    ///
    /// ```text
    /// -----BEGIN OPENSSH PRIVATE KEY-----
    /// ```
    pub fn from_openssh(pem: impl AsRef<[u8]>) -> Result<Self> {
        Self::decode_pem(pem)
    }

    /// Parse a raw binary SSH private key.
    pub fn from_bytes(mut bytes: &[u8]) -> Result<Self> {
        let reader = &mut bytes;
        let private_key = Self::decode(reader)?;
        Ok(reader.finish(private_key)?)
    }

    /// Encode OpenSSH-formatted (PEM) private key.
    pub fn encode_openssh<'o>(
        &self,
        line_ending: LineEnding,
        out: &'o mut [u8],
    ) -> Result<&'o str> {
        Ok(self.encode_pem(line_ending, out)?)
    }

    /// Encode an OpenSSH-formatted PEM private key, allocating a
    /// self-zeroizing [`String`] for the result.
    #[cfg(feature = "alloc")]
    pub fn to_openssh(&self, line_ending: LineEnding) -> Result<Zeroizing<String>> {
        Ok(self.encode_pem_string(line_ending).map(Zeroizing::new)?)
    }

    /// Serialize SSH private key as raw bytes.
    #[cfg(feature = "alloc")]
    pub fn to_bytes(&self) -> Result<Zeroizing<Vec<u8>>> {
        let mut private_key_bytes = Vec::with_capacity(self.encoded_len()?);
        self.encode(&mut private_key_bytes)?;
        Ok(Zeroizing::new(private_key_bytes))
    }

    /// Sign the given message using this private key, returning an [`SshSig`].
    ///
    /// These signatures can be produced using `ssh-keygen -Y sign`. They're
    /// encoded as PEM and begin with the following:
    ///
    /// ```text
    /// -----BEGIN SSH SIGNATURE-----
    /// ```
    ///
    /// See [PROTOCOL.sshsig] for more information.
    ///
    /// # Usage
    ///
    /// See also: [`PublicKey::verify`].
    ///
    #[cfg_attr(feature = "ed25519", doc = "```")]
    #[cfg_attr(not(feature = "ed25519"), doc = "```ignore")]
    /// # fn main() -> Result<(), ssh_key::Error> {
    /// use ssh_key::{PrivateKey, HashAlg, SshSig};
    ///
    /// // Message to be signed.
    /// let message = b"testing";
    ///
    /// // Example domain/namespace used for the message.
    /// let namespace = "example";
    ///
    /// // Private key to use when computing the signature.
    /// // WARNING: don't actually hardcode private keys in source code!!!
    /// let encoded_private_key = r#"
    /// -----BEGIN OPENSSH PRIVATE KEY-----
    /// b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
    /// QyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYgAAAJgAIAxdACAM
    /// XQAAAAtzc2gtZWQyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYg
    /// AAAEC2BsIi0QwW2uFscKTUUXNHLsYX4FxlaSDSblbAj7WR7bM+rvN+ot98qgEN796jTiQf
    /// ZfG1KaT0PtFDJ/XFSqtiAAAAEHVzZXJAZXhhbXBsZS5jb20BAgMEBQ==
    /// -----END OPENSSH PRIVATE KEY-----
    /// "#;
    ///
    /// let private_key = encoded_private_key.parse::<PrivateKey>()?;
    /// let signature = private_key.sign(namespace, HashAlg::default(), message)?;
    /// // assert!(private_key.public_key().verify(namespace, message, &signature).is_ok());
    /// # Ok(())
    /// # }
    /// ```
    ///
    /// [PROTOCOL.sshsig]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.sshsig?annotate=HEAD
    #[cfg(feature = "alloc")]
    pub fn sign(&self, namespace: &str, hash_alg: HashAlg, msg: &[u8]) -> Result<SshSig> {
        SshSig::sign(self, namespace, hash_alg, msg)
    }

    /// Read private key from an OpenSSH-formatted PEM file.
    #[cfg(feature = "std")]
    pub fn read_openssh_file(path: &Path) -> Result<Self> {
        // TODO(tarcieri): verify file permissions match `UNIX_FILE_PERMISSIONS`
        let pem = Zeroizing::new(fs::read_to_string(path)?);
        Self::from_openssh(&*pem)
    }

    /// Write private key as an OpenSSH-formatted PEM file.
    #[cfg(feature = "std")]
    pub fn write_openssh_file(&self, path: &Path, line_ending: LineEnding) -> Result<()> {
        let pem = self.to_openssh(line_ending)?;

        #[cfg(not(unix))]
        fs::write(path, pem.as_bytes())?;
        #[cfg(unix)]
        fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .mode(UNIX_FILE_PERMISSIONS)
            .open(path)
            .and_then(|mut file| file.write_all(pem.as_bytes()))?;

        Ok(())
    }

    /// Attempt to decrypt an encrypted private key using the provided
    /// password to derive an encryption key.
    ///
    /// Returns [`Error::Decrypted`] if the private key is already decrypted.
    #[cfg(feature = "encryption")]
    pub fn decrypt(&self, password: impl AsRef<[u8]>) -> Result<Self> {
        let (key, iv) = self.kdf.derive_key_and_iv(self.cipher, password)?;

        let ciphertext = self.key_data.encrypted().ok_or(Error::Decrypted)?;
        let mut buffer = Zeroizing::new(ciphertext.to_vec());
        self.cipher.decrypt(&key, &iv, &mut buffer, self.auth_tag)?;

        Self::decode_privatekey_comment_pair(
            &mut &**buffer,
            self.public_key.key_data.clone(),
            self.cipher.block_size(),
        )
    }

    /// Encrypt an unencrypted private key using the provided password to
    /// derive an encryption key.
    ///
    /// Uses the following algorithms:
    /// - Cipher: [`Cipher::Aes256Ctr`]
    /// - KDF: [`Kdf::Bcrypt`] (i.e. `bcrypt-pbkdf`)
    ///
    /// Returns [`Error::Encrypted`] if the private key is already encrypted.
    #[cfg(feature = "encryption")]
    pub fn encrypt(
        &self,
        rng: &mut impl CryptoRngCore,
        password: impl AsRef<[u8]>,
    ) -> Result<Self> {
        self.encrypt_with_cipher(rng, Cipher::Aes256Ctr, password)
    }

    /// Encrypt an unencrypted private key using the provided password to
    /// derive an encryption key for the provided [`Cipher`].
    ///
    /// Returns [`Error::Encrypted`] if the private key is already encrypted.
    #[cfg(feature = "encryption")]
    pub fn encrypt_with_cipher(
        &self,
        rng: &mut impl CryptoRngCore,
        cipher: Cipher,
        password: impl AsRef<[u8]>,
    ) -> Result<Self> {
        let checkint = rng.next_u32();

        self.encrypt_with(
            cipher,
            Kdf::new(Default::default(), rng)?,
            checkint,
            password,
        )
    }

    /// Encrypt an unencrypted private key using the provided cipher and KDF
    /// configuration.
    ///
    /// Returns [`Error::Encrypted`] if the private key is already encrypted.
    #[cfg(feature = "encryption")]
    pub fn encrypt_with(
        &self,
        cipher: Cipher,
        kdf: Kdf,
        checkint: u32,
        password: impl AsRef<[u8]>,
    ) -> Result<Self> {
        if self.is_encrypted() {
            return Err(Error::Encrypted);
        }

        let (key_bytes, iv_bytes) = kdf.derive_key_and_iv(cipher, password)?;
        let msg_len = self.encoded_privatekey_comment_pair_len(cipher)?;
        let mut out = Vec::with_capacity(msg_len);

        // Encode and encrypt private key
        self.encode_privatekey_comment_pair(&mut out, cipher, checkint)?;
        let auth_tag = cipher.encrypt(&key_bytes, &iv_bytes, out.as_mut_slice())?;

        Ok(Self {
            cipher,
            kdf,
            checkint: None,
            public_key: self.public_key.key_data.clone().into(),
            key_data: KeypairData::Encrypted(out),
            auth_tag,
        })
    }

    /// Get the digital signature [`Algorithm`] used by this key.
    pub fn algorithm(&self) -> Algorithm {
        self.public_key.algorithm()
    }

    /// Comment on the key (e.g. email address).
    pub fn comment(&self) -> &str {
        self.public_key.comment()
    }

    /// Cipher algorithm (a.k.a. `ciphername`).
    pub fn cipher(&self) -> Cipher {
        self.cipher
    }

    /// Compute key fingerprint.
    ///
    /// Use [`Default::default()`] to use the default hash function (SHA-256).
    pub fn fingerprint(&self, hash_alg: HashAlg) -> Fingerprint {
        self.public_key.fingerprint(hash_alg)
    }

    /// Is this key encrypted?
    pub fn is_encrypted(&self) -> bool {
        let ret = self.key_data.is_encrypted();
        debug_assert_eq!(ret, self.cipher.is_some());
        ret
    }

    /// Key Derivation Function (KDF) used to encrypt this key.
    ///
    /// Returns [`Kdf::None`] if this key is not encrypted.
    pub fn kdf(&self) -> &Kdf {
        &self.kdf
    }

    /// Keypair data.
    pub fn key_data(&self) -> &KeypairData {
        &self.key_data
    }

    /// Get the [`PublicKey`] which corresponds to this private key.
    pub fn public_key(&self) -> &PublicKey {
        &self.public_key
    }

    /// Generate a random key which uses the given algorithm.
    ///
    /// # Returns
    /// - `Error::AlgorithmUnknown` if the algorithm is unsupported.
    #[cfg(feature = "rand_core")]
    #[allow(unreachable_code, unused_variables)]
    pub fn random(rng: &mut impl CryptoRngCore, algorithm: Algorithm) -> Result<Self> {
        let checkint = rng.next_u32();
        let key_data = match algorithm {
            #[cfg(feature = "dsa")]
            Algorithm::Dsa => KeypairData::from(DsaKeypair::random(rng)?),
            #[cfg(any(feature = "p256", feature = "p384", feature = "p521"))]
            Algorithm::Ecdsa { curve } => KeypairData::from(EcdsaKeypair::random(rng, curve)?),
            #[cfg(feature = "ed25519")]
            Algorithm::Ed25519 => KeypairData::from(Ed25519Keypair::random(rng)),
            #[cfg(feature = "rsa")]
            Algorithm::Rsa { .. } => {
                KeypairData::from(RsaKeypair::random(rng, DEFAULT_RSA_KEY_SIZE)?)
            }
            _ => return Err(Error::AlgorithmUnknown),
        };
        let public_key = public::KeyData::try_from(&key_data)?;

        Ok(Self {
            cipher: Cipher::None,
            kdf: Kdf::None,
            checkint: Some(checkint),
            public_key: public_key.into(),
            key_data,
            auth_tag: None,
        })
    }

    /// Set the comment on the key.
    #[cfg(feature = "alloc")]
    pub fn set_comment(&mut self, comment: impl Into<String>) {
        self.public_key.set_comment(comment);
    }

    /// Decode [`KeypairData`] along with its associated checkints and comment,
    /// storing the comment in the provided public key on success.
    ///
    /// This method also checks padding for validity and ensures that the
    /// decoded private key matches the provided public key.
    ///
    /// For private key format specification, see OpenSSH [PROTOCOL.key] § 3:
    ///
    /// ```text
    /// uint32  checkint
    /// uint32  checkint
    /// byte[]  privatekey1
    /// string  comment1
    /// byte[]  privatekey2
    /// string  comment2
    /// ...
    /// string  privatekeyN
    /// string  commentN
    /// char    1
    /// char    2
    /// char    3
    /// ...
    /// char    padlen % 255
    /// ```
    ///
    /// [PROTOCOL.key]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.key?annotate=HEAD
    fn decode_privatekey_comment_pair(
        reader: &mut impl Reader,
        public_key: public::KeyData,
        block_size: usize,
    ) -> Result<Self> {
        debug_assert!(block_size <= MAX_BLOCK_SIZE);

        // Ensure input data is padding-aligned
        if reader.remaining_len().checked_rem(block_size) != Some(0) {
            return Err(encoding::Error::Length.into());
        }

        let checkint1 = u32::decode(reader)?;
        let checkint2 = u32::decode(reader)?;

        if checkint1 != checkint2 {
            return Err(Error::Crypto);
        }

        let key_data = KeypairData::decode(reader)?;

        // Ensure public key matches private key
        if public_key != public::KeyData::try_from(&key_data)? {
            return Err(Error::PublicKey);
        }

        let mut public_key = PublicKey::from(public_key);
        public_key.decode_comment(reader)?;

        let padding_len = reader.remaining_len();

        if padding_len >= block_size {
            return Err(encoding::Error::Length.into());
        }

        if padding_len != 0 {
            let mut padding = [0u8; MAX_BLOCK_SIZE];
            reader.read(&mut padding[..padding_len])?;

            if PADDING_BYTES[..padding_len] != padding[..padding_len] {
                return Err(Error::FormatEncoding);
            }
        }

        if !reader.is_finished() {
            return Err(Error::TrailingData {
                remaining: reader.remaining_len(),
            });
        }

        Ok(Self {
            cipher: Cipher::None,
            kdf: Kdf::None,
            checkint: Some(checkint1),
            public_key,
            key_data,
            auth_tag: None,
        })
    }

    /// Encode [`KeypairData`] along with its associated checkints, comment,
    /// and padding.
    fn encode_privatekey_comment_pair(
        &self,
        writer: &mut impl Writer,
        cipher: Cipher,
        checkint: u32,
    ) -> encoding::Result<()> {
        let unpadded_len = self.unpadded_privatekey_comment_pair_len()?;
        let padding_len = cipher.padding_len(unpadded_len);

        checkint.encode(writer)?;
        checkint.encode(writer)?;
        self.key_data.encode(writer)?;
        self.comment().encode(writer)?;
        writer.write(&PADDING_BYTES[..padding_len])?;
        Ok(())
    }

    /// Get the length of this private key when encoded with the given comment
    /// and padded using the padding size for the given cipher.
    fn encoded_privatekey_comment_pair_len(&self, cipher: Cipher) -> encoding::Result<usize> {
        let len = self.unpadded_privatekey_comment_pair_len()?;
        [len, cipher.padding_len(len)].checked_sum()
    }

    /// Get the length of this private key when encoded with the given comment.
    ///
    /// This length is just the checkints, private key data, and comment sans
    /// any padding.
    fn unpadded_privatekey_comment_pair_len(&self) -> encoding::Result<usize> {
        // This method is intended for use with unencrypted keys only
        debug_assert!(!self.is_encrypted(), "called on encrypted key");

        [
            8, // 2 x uint32 checkints,
            self.key_data.encoded_len()?,
            self.comment().encoded_len()?,
        ]
        .checked_sum()
    }
}

impl ConstantTimeEq for PrivateKey {
    fn ct_eq(&self, other: &Self) -> Choice {
        // Constant-time with respect to private key data
        self.key_data.ct_eq(&other.key_data)
            & Choice::from(
                (self.cipher == other.cipher
                    && self.kdf == other.kdf
                    && self.public_key == other.public_key) as u8,
            )
    }
}

impl Eq for PrivateKey {}

impl PartialEq for PrivateKey {
    fn eq(&self, other: &Self) -> bool {
        self.ct_eq(other).into()
    }
}

impl Decode for PrivateKey {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        let mut auth_magic = [0u8; Self::AUTH_MAGIC.len()];
        reader.read(&mut auth_magic)?;

        if auth_magic != Self::AUTH_MAGIC {
            return Err(Error::FormatEncoding);
        }

        let cipher = Cipher::decode(reader)?;
        let kdf = Kdf::decode(reader)?;
        let nkeys = usize::decode(reader)?;

        // TODO(tarcieri): support more than one key?
        if nkeys != 1 {
            return Err(encoding::Error::Length.into());
        }

        let public_key = reader.read_prefixed(public::KeyData::decode)?;

        // Handle encrypted private key
        #[cfg(not(feature = "alloc"))]
        if cipher.is_some() {
            return Err(Error::Encrypted);
        }
        #[cfg(feature = "alloc")]
        if cipher.is_some() {
            let ciphertext = Vec::decode(reader)?;

            // Ensure ciphertext is padded to the expected length
            if ciphertext.len().checked_rem(cipher.block_size()) != Some(0) {
                return Err(Error::Crypto);
            }

            let auth_tag = if cipher.has_tag() {
                let mut tag = Tag::default();
                reader.read(&mut tag)?;
                Some(tag)
            } else {
                None
            };

            if !reader.is_finished() {
                return Err(Error::TrailingData {
                    remaining: reader.remaining_len(),
                });
            }

            return Ok(Self {
                cipher,
                kdf,
                checkint: None,
                public_key: public_key.into(),
                key_data: KeypairData::Encrypted(ciphertext),
                auth_tag,
            });
        }

        // Processing unencrypted key. No KDF should be set.
        if kdf.is_some() {
            return Err(Error::Crypto);
        }

        reader.read_prefixed(|reader| {
            Self::decode_privatekey_comment_pair(reader, public_key, cipher.block_size())
        })
    }
}

impl Encode for PrivateKey {
    fn encoded_len(&self) -> encoding::Result<usize> {
        let private_key_len = if self.is_encrypted() {
            self.key_data.encoded_len_prefixed()?
        } else {
            [4, self.encoded_privatekey_comment_pair_len(Cipher::None)?].checked_sum()?
        };

        [
            Self::AUTH_MAGIC.len(),
            self.cipher.encoded_len()?,
            self.kdf.encoded_len()?,
            4, // number of keys (uint32)
            self.public_key.key_data().encoded_len_prefixed()?,
            private_key_len,
            self.auth_tag.map(|tag| tag.len()).unwrap_or(0),
        ]
        .checked_sum()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        writer.write(Self::AUTH_MAGIC)?;
        self.cipher.encode(writer)?;
        self.kdf.encode(writer)?;

        // TODO(tarcieri): support for encoding more than one private key
        1usize.encode(writer)?;

        // Encode public key
        self.public_key.key_data().encode_prefixed(writer)?;

        // Encode private key
        if self.is_encrypted() {
            self.key_data.encode_prefixed(writer)?;

            if let Some(tag) = &self.auth_tag {
                writer.write(tag)?;
            }
        } else {
            self.encoded_privatekey_comment_pair_len(Cipher::None)?
                .encode(writer)?;

            let checkint = self.checkint.unwrap_or_else(|| self.key_data.checkint());
            self.encode_privatekey_comment_pair(writer, Cipher::None, checkint)?;
        }

        Ok(())
    }
}

impl From<PrivateKey> for PublicKey {
    fn from(private_key: PrivateKey) -> PublicKey {
        private_key.public_key
    }
}

impl From<&PrivateKey> for PublicKey {
    fn from(private_key: &PrivateKey) -> PublicKey {
        private_key.public_key.clone()
    }
}

impl From<PrivateKey> for public::KeyData {
    fn from(private_key: PrivateKey) -> public::KeyData {
        private_key.public_key.key_data
    }
}

impl From<&PrivateKey> for public::KeyData {
    fn from(private_key: &PrivateKey) -> public::KeyData {
        private_key.public_key.key_data.clone()
    }
}

#[cfg(feature = "alloc")]
impl From<DsaKeypair> for PrivateKey {
    fn from(keypair: DsaKeypair) -> PrivateKey {
        KeypairData::from(keypair)
            .try_into()
            .expect(CONVERSION_ERROR_MSG)
    }
}

#[cfg(feature = "ecdsa")]
impl From<EcdsaKeypair> for PrivateKey {
    fn from(keypair: EcdsaKeypair) -> PrivateKey {
        KeypairData::from(keypair)
            .try_into()
            .expect(CONVERSION_ERROR_MSG)
    }
}

impl From<Ed25519Keypair> for PrivateKey {
    fn from(keypair: Ed25519Keypair) -> PrivateKey {
        KeypairData::from(keypair)
            .try_into()
            .expect(CONVERSION_ERROR_MSG)
    }
}

#[cfg(feature = "alloc")]
impl From<RsaKeypair> for PrivateKey {
    fn from(keypair: RsaKeypair) -> PrivateKey {
        KeypairData::from(keypair)
            .try_into()
            .expect(CONVERSION_ERROR_MSG)
    }
}

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
impl From<SkEcdsaSha2NistP256> for PrivateKey {
    fn from(keypair: SkEcdsaSha2NistP256) -> PrivateKey {
        KeypairData::from(keypair)
            .try_into()
            .expect(CONVERSION_ERROR_MSG)
    }
}

#[cfg(feature = "alloc")]
impl From<SkEd25519> for PrivateKey {
    fn from(keypair: SkEd25519) -> PrivateKey {
        KeypairData::from(keypair)
            .try_into()
            .expect(CONVERSION_ERROR_MSG)
    }
}

impl TryFrom<KeypairData> for PrivateKey {
    type Error = Error;

    fn try_from(key_data: KeypairData) -> Result<PrivateKey> {
        let public_key = public::KeyData::try_from(&key_data)?;

        Ok(Self {
            cipher: Cipher::None,
            kdf: Kdf::None,
            checkint: None,
            public_key: public_key.into(),
            key_data,
            auth_tag: None,
        })
    }
}

impl PemLabel for PrivateKey {
    const PEM_LABEL: &'static str = "OPENSSH PRIVATE KEY";
}

impl str::FromStr for PrivateKey {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        Self::from_openssh(s)
    }
}
