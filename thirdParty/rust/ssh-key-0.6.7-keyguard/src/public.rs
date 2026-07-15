//! SSH public key support.
//!
//! Support for decoding SSH public keys from the OpenSSH file format.

#[cfg(feature = "alloc")]
mod dsa;
#[cfg(feature = "ecdsa")]
mod ecdsa;
mod ed25519;
mod key_data;
#[cfg(feature = "alloc")]
mod opaque;
#[cfg(feature = "alloc")]
mod rsa;
mod sk;
mod ssh_format;

pub use self::{ed25519::Ed25519PublicKey, key_data::KeyData, sk::SkEd25519};

#[cfg(feature = "alloc")]
pub use self::{
    dsa::DsaPublicKey,
    opaque::{OpaquePublicKey, OpaquePublicKeyBytes},
    rsa::RsaPublicKey,
};

#[cfg(feature = "ecdsa")]
pub use self::{ecdsa::EcdsaPublicKey, sk::SkEcdsaSha2NistP256};

pub(crate) use self::ssh_format::SshFormat;

use crate::{Algorithm, Error, Fingerprint, HashAlg, Result};
use core::str::FromStr;
use encoding::{Base64Reader, Decode, Reader};

#[cfg(feature = "alloc")]
use {
    crate::SshSig,
    alloc::{
        borrow::ToOwned,
        string::{String, ToString},
        vec::Vec,
    },
    encoding::Encode,
};

#[cfg(all(feature = "alloc", feature = "serde"))]
use serde::{de, ser, Deserialize, Serialize};

#[cfg(feature = "std")]
use std::{fs, path::Path};

#[cfg(doc)]
use crate::PrivateKey;

/// SSH public key.
///
/// # OpenSSH encoding
///
/// The OpenSSH encoding of an SSH public key looks like following:
///
/// ```text
/// ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti user@example.com
/// ```
///
/// It consists of the following three parts:
///
/// 1. Algorithm identifier (in this example `ssh-ed25519`)
/// 2. Key data encoded as Base64
/// 3. Comment (optional): arbitrary label describing a key. Usually an email address
///
/// The [`PublicKey::from_openssh`] and [`PublicKey::to_openssh`] methods can be
/// used to decode/encode public keys, or alternatively, the [`FromStr`] and
/// [`ToString`] impls.
///
/// # `serde` support
///
/// When the `serde` feature of this crate is enabled, this type receives impls
/// of [`Deserialize`][`serde::Deserialize`] and [`Serialize`][`serde::Serialize`].
///
/// The serialization uses a binary encoding with binary formats like bincode
/// and CBOR, and the OpenSSH string serialization when used with
/// human-readable formats like JSON and TOML.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct PublicKey {
    /// Key data.
    pub(crate) key_data: KeyData,

    /// Comment on the key (e.g. email address)
    #[cfg(feature = "alloc")]
    pub(crate) comment: String,
}

impl PublicKey {
    /// Create a new public key with the given comment.
    ///
    /// On `no_std` platforms, use `PublicKey::from(key_data)` instead.
    #[cfg(feature = "alloc")]
    pub fn new(key_data: KeyData, comment: impl Into<String>) -> Self {
        Self {
            key_data,
            comment: comment.into(),
        }
    }

    /// Parse an OpenSSH-formatted public key.
    ///
    /// OpenSSH-formatted public keys look like the following:
    ///
    /// ```text
    /// ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti foo@bar.com
    /// ```
    pub fn from_openssh(public_key: &str) -> Result<Self> {
        let encapsulation = SshFormat::decode(public_key.trim_end().as_bytes())?;
        let mut reader = Base64Reader::new(encapsulation.base64_data)?;
        let key_data = KeyData::decode(&mut reader)?;

        // Verify that the algorithm in the Base64-encoded data matches the text
        if encapsulation.algorithm_id != key_data.algorithm().as_str() {
            return Err(Error::AlgorithmUnknown);
        }

        let public_key = Self {
            key_data,
            #[cfg(feature = "alloc")]
            comment: encapsulation.comment.to_owned(),
        };

        Ok(reader.finish(public_key)?)
    }

    /// Parse a raw binary SSH public key.
    pub fn from_bytes(mut bytes: &[u8]) -> Result<Self> {
        let reader = &mut bytes;
        let key_data = KeyData::decode(reader)?;
        Ok(reader.finish(key_data.into())?)
    }

    /// Encode OpenSSH-formatted public key.
    pub fn encode_openssh<'o>(&self, out: &'o mut [u8]) -> Result<&'o str> {
        SshFormat::encode(
            self.algorithm().as_str(),
            &self.key_data,
            self.comment(),
            out,
        )
    }

    /// Encode an OpenSSH-formatted public key, allocating a [`String`] for
    /// the result.
    #[cfg(feature = "alloc")]
    pub fn to_openssh(&self) -> Result<String> {
        SshFormat::encode_string(self.algorithm().as_str(), &self.key_data, self.comment())
    }

    /// Serialize SSH public key as raw bytes.
    #[cfg(feature = "alloc")]
    pub fn to_bytes(&self) -> Result<Vec<u8>> {
        let mut public_key_bytes = Vec::new();
        self.key_data.encode(&mut public_key_bytes)?;
        Ok(public_key_bytes)
    }

    /// Verify the [`SshSig`] signature over the given message using this
    /// public key.
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
    /// See also: [`PrivateKey::sign`].
    ///
    #[cfg_attr(feature = "ed25519", doc = "```")]
    #[cfg_attr(not(feature = "ed25519"), doc = "```ignore")]
    /// # fn main() -> Result<(), ssh_key::Error> {
    /// use ssh_key::{PublicKey, SshSig};
    ///
    /// // Message to be verified.
    /// let message = b"testing";
    ///
    /// // Example domain/namespace used for the message.
    /// let namespace = "example";
    ///
    /// // Public key which computed the signature.
    /// let encoded_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti user@example.com";
    ///
    /// // Example signature to be verified.
    /// let signature_str = r#"
    /// -----BEGIN SSH SIGNATURE-----
    /// U1NIU0lHAAAAAQAAADMAAAALc3NoLWVkMjU1MTkAAAAgsz6u836i33yqAQ3v3qNOJB9l8b
    /// UppPQ+0UMn9cVKq2IAAAAHZXhhbXBsZQAAAAAAAAAGc2hhNTEyAAAAUwAAAAtzc2gtZWQy
    /// NTUxOQAAAEBPEav+tMGNnox4MuzM7rlHyVBajCn8B0kAyiOWwPKprNsG3i6X+voz/WCSik
    /// /FowYwqhgCABUJSvRX3AERVBUP
    /// -----END SSH SIGNATURE-----
    /// "#;
    ///
    /// let public_key = encoded_public_key.parse::<PublicKey>()?;
    /// let signature = signature_str.parse::<SshSig>()?;
    /// public_key.verify(namespace, message, &signature)?;
    /// # Ok(())
    /// # }
    /// ```
    ///
    /// [PROTOCOL.sshsig]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.sshsig?annotate=HEAD
    #[cfg(feature = "alloc")]
    pub fn verify(&self, namespace: &str, msg: &[u8], signature: &SshSig) -> Result<()> {
        if self.key_data() != signature.public_key() {
            return Err(Error::PublicKey);
        }

        if namespace != signature.namespace() {
            return Err(Error::Namespace);
        }

        signature.verify(msg)
    }

    /// Read public key from an OpenSSH-formatted file.
    #[cfg(feature = "std")]
    pub fn read_openssh_file(path: &Path) -> Result<Self> {
        let input = fs::read_to_string(path)?;
        Self::from_openssh(&input)
    }

    /// Write public key as an OpenSSH-formatted file.
    #[cfg(feature = "std")]
    pub fn write_openssh_file(&self, path: &Path) -> Result<()> {
        let mut encoded = self.to_openssh()?;
        encoded.push('\n'); // TODO(tarcieri): OS-specific line endings?

        fs::write(path, encoded.as_bytes())?;
        Ok(())
    }

    /// Get the digital signature [`Algorithm`] used by this key.
    pub fn algorithm(&self) -> Algorithm {
        self.key_data.algorithm()
    }

    /// Comment on the key (e.g. email address).
    #[cfg(not(feature = "alloc"))]
    pub fn comment(&self) -> &str {
        ""
    }

    /// Comment on the key (e.g. email address).
    #[cfg(feature = "alloc")]
    pub fn comment(&self) -> &str {
        &self.comment
    }

    /// Public key data.
    pub fn key_data(&self) -> &KeyData {
        &self.key_data
    }

    /// Compute key fingerprint.
    ///
    /// Use [`Default::default()`] to use the default hash function (SHA-256).
    pub fn fingerprint(&self, hash_alg: HashAlg) -> Fingerprint {
        self.key_data.fingerprint(hash_alg)
    }

    /// Set the comment on the key.
    #[cfg(feature = "alloc")]
    pub fn set_comment(&mut self, comment: impl Into<String>) {
        self.comment = comment.into();
    }

    /// Decode comment (e.g. email address).
    ///
    /// This is a stub implementation that ignores the comment.
    #[cfg(not(feature = "alloc"))]
    pub(crate) fn decode_comment(&mut self, reader: &mut impl Reader) -> Result<()> {
        reader.drain_prefixed()?;
        Ok(())
    }

    /// Decode comment (e.g. email address)
    #[cfg(feature = "alloc")]
    pub(crate) fn decode_comment(&mut self, reader: &mut impl Reader) -> Result<()> {
        self.comment = String::decode(reader)?;
        Ok(())
    }
}

impl From<KeyData> for PublicKey {
    fn from(key_data: KeyData) -> PublicKey {
        PublicKey {
            key_data,
            #[cfg(feature = "alloc")]
            comment: String::new(),
        }
    }
}

impl From<PublicKey> for KeyData {
    fn from(public_key: PublicKey) -> KeyData {
        public_key.key_data
    }
}

impl From<&PublicKey> for KeyData {
    fn from(public_key: &PublicKey) -> KeyData {
        public_key.key_data.clone()
    }
}

#[cfg(feature = "alloc")]
impl From<DsaPublicKey> for PublicKey {
    fn from(public_key: DsaPublicKey) -> PublicKey {
        KeyData::from(public_key).into()
    }
}

#[cfg(feature = "ecdsa")]
impl From<EcdsaPublicKey> for PublicKey {
    fn from(public_key: EcdsaPublicKey) -> PublicKey {
        KeyData::from(public_key).into()
    }
}

impl From<Ed25519PublicKey> for PublicKey {
    fn from(public_key: Ed25519PublicKey) -> PublicKey {
        KeyData::from(public_key).into()
    }
}

#[cfg(feature = "alloc")]
impl From<RsaPublicKey> for PublicKey {
    fn from(public_key: RsaPublicKey) -> PublicKey {
        KeyData::from(public_key).into()
    }
}

#[cfg(feature = "ecdsa")]
impl From<SkEcdsaSha2NistP256> for PublicKey {
    fn from(public_key: SkEcdsaSha2NistP256) -> PublicKey {
        KeyData::from(public_key).into()
    }
}

impl From<SkEd25519> for PublicKey {
    fn from(public_key: SkEd25519) -> PublicKey {
        KeyData::from(public_key).into()
    }
}

impl FromStr for PublicKey {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        Self::from_openssh(s)
    }
}

#[cfg(feature = "alloc")]
impl ToString for PublicKey {
    fn to_string(&self) -> String {
        self.to_openssh().expect("SSH public key encoding error")
    }
}

#[cfg(all(feature = "alloc", feature = "serde"))]
impl<'de> Deserialize<'de> for PublicKey {
    fn deserialize<D>(deserializer: D) -> core::result::Result<Self, D::Error>
    where
        D: de::Deserializer<'de>,
    {
        if deserializer.is_human_readable() {
            let string = String::deserialize(deserializer)?;
            Self::from_openssh(&string).map_err(de::Error::custom)
        } else {
            let bytes = Vec::<u8>::deserialize(deserializer)?;
            Self::from_bytes(&bytes).map_err(de::Error::custom)
        }
    }
}

#[cfg(all(feature = "alloc", feature = "serde"))]
impl Serialize for PublicKey {
    fn serialize<S>(&self, serializer: S) -> core::result::Result<S::Ok, S::Error>
    where
        S: ser::Serializer,
    {
        if serializer.is_human_readable() {
            self.to_openssh()
                .map_err(ser::Error::custom)?
                .serialize(serializer)
        } else {
            self.to_bytes()
                .map_err(ser::Error::custom)?
                .serialize(serializer)
        }
    }
}
