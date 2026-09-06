//! Concrete secret-key and entropy adapters used by OpenPGP operations.
//!
//! RSA signing and decryption always cross `keyguard-crypto-sensitive`; rPGP
//! only supplies packet access and the traits implemented by these adapters.

use aws_lc_rs::rand as aws_lc_rand;
use pgp::{
    composed::PlainSessionKey,
    crypto::{hash::HashAlgorithm, public_key::PublicKeyAlgorithm, sym::SymmetricKeyAlgorithm},
    packet::{SecretKey, SecretSubkey},
    ser::Serialize,
    types::{
        DecryptionKey, EskType, Fingerprint, KeyDetails, KeyId, KeyVersion, Mpi, Password,
        PkeskBytes, PlainSecretParams, PublicParams, SignatureBytes, SigningKey, Timestamp,
    },
};
use rand::{CryptoRng, Error as RandError, RngCore};
use zeroize::Zeroizing;

use keyguard_crypto_sensitive::{
    RsaPrivateComponents, RsaSignatureHash, decrypt_rsa_pkcs1_v1_5, sign_rsa_pkcs1_v1_5_digest,
};

use crate::openpgp::packet::take_mpi;

use super::signer::SigningKeyRef;

/// Failure classes produced while constructing a concrete secret-key adapter.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SecretKeyAdapterError {
    InvalidArgument,
    Internal,
}

/// rand 0.8 adapter whose only entropy source is AWS-LC.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct AwsLcRng;

impl RngCore for AwsLcRng {
    fn next_u32(&mut self) -> u32 {
        let mut output = [0_u8; 4];
        self.fill_bytes(&mut output);
        u32::from_le_bytes(output)
    }

    fn next_u64(&mut self) -> u64 {
        let mut output = [0_u8; 8];
        self.fill_bytes(&mut output);
        u64::from_le_bytes(output)
    }

    fn fill_bytes(&mut self, destination: &mut [u8]) {
        // `RngCore::fill_bytes` cannot report failure. Panicking is a deliberate
        // fail-closed bridge: every native export catches unwind and reports only
        // the stable PANIC code, so an entropy failure can never become predictable
        // output or partially initialized cryptographic material.
        self.try_fill_bytes(destination)
            .expect("AWS-LC random generation failed")
    }

    fn try_fill_bytes(&mut self, destination: &mut [u8]) -> Result<(), RandError> {
        aws_lc_rand::fill(destination)
            .map_err(|_| RandError::new(std::io::Error::other("AWS-LC random generation failed")))
    }
}

impl CryptoRng for AwsLcRng {}

/// Bounded zeroizing chunks used while assembling secret outputs.
#[derive(Default)]
pub(crate) struct SecretChunks {
    chunks: Vec<Zeroizing<Vec<u8>>>,
    length: usize,
}

impl SecretChunks {
    pub(crate) fn push(&mut self, chunk: Zeroizing<Vec<u8>>, limit: usize) -> Result<(), ()> {
        let length = self
            .length
            .checked_add(chunk.len())
            .filter(|length| *length <= limit)
            .ok_or(())?;
        self.chunks.try_reserve(1).map_err(|_| ())?;
        self.chunks.push(chunk);
        self.length = length;
        Ok(())
    }

    pub(crate) fn into_zeroizing(self) -> Result<Zeroizing<Vec<u8>>, ()> {
        let mut output = Zeroizing::new(Vec::new());
        output.try_reserve_exact(self.length).map_err(|_| ())?;
        let allocation = output.as_ptr();
        let capacity = output.capacity();
        for chunk in self.chunks {
            if chunk.len() > capacity.saturating_sub(output.len()) {
                return Err(());
            }
            output.extend_from_slice(&chunk);
        }
        if output.len() != self.length
            || output.capacity() != capacity
            || output.as_ptr() != allocation
        {
            return Err(());
        }
        Ok(output)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum SecretPacketRef<'a> {
    Primary(&'a SecretKey),
    Subkey(&'a SecretSubkey),
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum SecretPacketSelection {
    Primary,
    Subkey(usize),
}

impl SecretPacketSelection {
    pub(crate) fn from_ref(
        primary: Option<&SecretKey>,
        subkeys: &[SecretSubkey],
        packet: SecretPacketRef<'_>,
    ) -> Result<Self, SecretKeyAdapterError> {
        match packet {
            SecretPacketRef::Primary(key) => primary
                .filter(|primary| std::ptr::eq(*primary, key))
                .map(|_| Self::Primary)
                .ok_or(SecretKeyAdapterError::Internal),
            SecretPacketRef::Subkey(key) => subkeys
                .iter()
                .position(|subkey| std::ptr::eq(subkey, key))
                .map(Self::Subkey)
                .ok_or(SecretKeyAdapterError::Internal),
        }
    }

    pub(crate) fn packet<'a>(
        self,
        primary: Option<&'a SecretKey>,
        subkeys: &'a [SecretSubkey],
    ) -> Result<SecretPacketRef<'a>, SecretKeyAdapterError> {
        match self {
            Self::Primary => primary
                .map(SecretPacketRef::Primary)
                .ok_or(SecretKeyAdapterError::Internal),
            Self::Subkey(index) => subkeys
                .get(index)
                .map(SecretPacketRef::Subkey)
                .ok_or(SecretKeyAdapterError::Internal),
        }
    }
}

impl<'a> SecretPacketRef<'a> {
    pub(crate) fn public_key(self) -> &'a dyn KeyDetails {
        match self {
            Self::Primary(key) => key.public_key(),
            Self::Subkey(key) => key.public_key(),
        }
    }

    pub(crate) fn unlock<T>(
        self,
        password: &Password,
        operation: impl FnOnce(&PublicParams, &PlainSecretParams) -> pgp::errors::Result<T>,
    ) -> pgp::errors::Result<pgp::errors::Result<T>> {
        match self {
            Self::Primary(key) => key.unlock(password, operation),
            Self::Subkey(key) => key.unlock(password, operation),
        }
    }
}

/// rPGP signing/decryption-key adapter that routes RSA private operations to
/// the audited AWS-LC boundary.
#[derive(Clone, Copy, Debug)]
pub(crate) struct AwsLcRsaSecretKey<'a> {
    packet: SecretPacketRef<'a>,
}

impl<'a> AwsLcRsaSecretKey<'a> {
    pub(crate) fn new(packet: SecretPacketRef<'a>) -> Result<Self, SecretKeyAdapterError> {
        if !is_rsa_private_algorithm(packet.algorithm()) {
            return Err(SecretKeyAdapterError::InvalidArgument);
        }
        Ok(Self { packet })
    }

    fn private_components(
        packet: SecretPacketRef<'_>,
        public: &PublicParams,
        private: &PlainSecretParams,
    ) -> pgp::errors::Result<RsaPrivateComponents> {
        // `new` already proved the algorithm; re-check before the unlocked
        // private material is extracted.
        if !is_rsa_private_algorithm(packet.algorithm()) {
            return Err("unsupported RSA algorithm identifier".to_owned().into());
        }
        rsa_private_components(public, private)
    }
}

/// Shared secret-key signer acquisition used by target-specific certificate
/// mutations. RSA is always routed through AWS-LC; other supported algorithms
/// use rPGP's packet implementation.
pub(crate) struct OpenPgpSecretSigner<'a> {
    fallback: &'a dyn SigningKey,
    rsa: Option<AwsLcRsaSecretKey<'a>>,
}

impl<'a> OpenPgpSecretSigner<'a> {
    pub(crate) fn new(
        packet: SecretPacketRef<'a>,
        fallback: &'a dyn SigningKey,
    ) -> Result<Self, SecretKeyAdapterError> {
        let rsa = is_rsa_private_algorithm(packet.algorithm())
            .then(|| AwsLcRsaSecretKey::new(packet))
            .transpose()?;
        Ok(Self { fallback, rsa })
    }

    pub(crate) fn as_ref(&self) -> SigningKeyRef<'_> {
        SigningKeyRef(self.rsa.as_ref().map_or(self.fallback, |key| key))
    }
}

impl KeyDetails for SecretPacketRef<'_> {
    fn version(&self) -> KeyVersion {
        self.public_key().version()
    }

    fn legacy_key_id(&self) -> KeyId {
        self.public_key().legacy_key_id()
    }

    fn fingerprint(&self) -> Fingerprint {
        self.public_key().fingerprint()
    }

    fn algorithm(&self) -> PublicKeyAlgorithm {
        self.public_key().algorithm()
    }

    fn created_at(&self) -> Timestamp {
        self.public_key().created_at()
    }

    fn legacy_v3_expiration_days(&self) -> Option<u16> {
        self.public_key().legacy_v3_expiration_days()
    }

    fn public_params(&self) -> &PublicParams {
        self.public_key().public_params()
    }
}

impl KeyDetails for AwsLcRsaSecretKey<'_> {
    fn version(&self) -> KeyVersion {
        self.packet.version()
    }

    fn legacy_key_id(&self) -> KeyId {
        self.packet.legacy_key_id()
    }

    fn fingerprint(&self) -> Fingerprint {
        self.packet.fingerprint()
    }

    fn algorithm(&self) -> PublicKeyAlgorithm {
        self.packet.algorithm()
    }

    fn created_at(&self) -> Timestamp {
        self.packet.created_at()
    }

    fn legacy_v3_expiration_days(&self) -> Option<u16> {
        self.packet.legacy_v3_expiration_days()
    }

    fn public_params(&self) -> &PublicParams {
        self.packet.public_params()
    }
}

impl SigningKey for AwsLcRsaSecretKey<'_> {
    fn sign(
        &self,
        key_password: &Password,
        hash: HashAlgorithm,
        digest: &[u8],
    ) -> pgp::errors::Result<SignatureBytes> {
        if self.packet.algorithm() == PublicKeyAlgorithm::RSAEncrypt {
            return Err("RSA encryption-only key cannot sign".to_owned().into());
        }
        let hash = match hash {
            HashAlgorithm::Sha1 => RsaSignatureHash::Sha1,
            HashAlgorithm::Sha256 => RsaSignatureHash::Sha256,
            HashAlgorithm::Sha512 => RsaSignatureHash::Sha512,
            _ => return Err("unsupported AWS-LC RSA signature hash".to_owned().into()),
        };
        self.packet.unlock(key_password, |public, private| {
            let components = Self::private_components(self.packet, public, private)?;
            let signature = sign_rsa_pkcs1_v1_5_digest(&components, hash, digest)
                .map_err(|_| pgp::errors::Error::from("AWS-LC RSA signing failed".to_owned()))?;
            Ok(SignatureBytes::Mpis(vec![Mpi::from_slice(&signature)]))
        })?
    }

    fn hash_alg(&self) -> HashAlgorithm {
        HashAlgorithm::Sha256
    }
}

impl DecryptionKey for AwsLcRsaSecretKey<'_> {
    fn decrypt(
        &self,
        key_password: &Password,
        values: &PkeskBytes,
        typ: EskType,
    ) -> pgp::errors::Result<pgp::errors::Result<PlainSessionKey>> {
        if self.packet.algorithm() == PublicKeyAlgorithm::RSASign {
            return Ok(Err("RSA signing-only key cannot decrypt".to_owned().into()));
        }
        let PkeskBytes::Rsa { mpi } = values else {
            return Ok(Err("inconsistent RSA PKESK".to_owned().into()));
        };
        self.packet.unlock(key_password, |public, private| {
            let components = Self::private_components(self.packet, public, private)?;
            let plaintext = decrypt_rsa_pkcs1_v1_5(&components, mpi.as_ref())
                .map_err(|_| pgp::errors::Error::from("AWS-LC RSA decryption failed".to_owned()))?;
            decode_plain_session_key(&plaintext, typ)
        })
    }
}

pub(crate) fn is_rsa_private_algorithm(algorithm: PublicKeyAlgorithm) -> bool {
    matches!(
        algorithm,
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt | PublicKeyAlgorithm::RSASign
    )
}

/// Extracts the n/e/d component set shared by message operations and the
/// gpg-agent, zeroizing and discarding the OpenPGP CRT components.
pub(in crate::openpgp) fn rsa_private_components(
    public: &PublicParams,
    private: &PlainSecretParams,
) -> pgp::errors::Result<RsaPrivateComponents> {
    let PublicParams::RSA(public) = public else {
        return Err("inconsistent RSA public parameters".to_owned().into());
    };
    let PlainSecretParams::RSA(private) = private else {
        return Err("inconsistent RSA private parameters".to_owned().into());
    };

    let mut serialized = Vec::new();
    public.to_writer(&mut serialized)?;
    let mut public_mpis = parse_mpis(&serialized, 2)
        .ok_or_else(|| pgp::errors::Error::from("invalid RSA public parameters".to_owned()))?;
    let modulus = public_mpis.remove(0);
    let public_exponent = public_mpis.remove(0);
    let (private_exponent, prime_p, prime_q, coefficient) = private.to_bytes();
    let private_exponent = Zeroizing::new(private_exponent);
    let _prime_p = Zeroizing::new(prime_p);
    let _prime_q = Zeroizing::new(prime_q);
    let _coefficient = Zeroizing::new(coefficient);

    // OpenPGP's `u` is p^-1 mod q while AWS-LC's CRT coefficient is
    // q^-1 mod p. Supplying n/e/d lets the sensitive boundary recover and
    // validate the correctly oriented CRT set.
    Ok(RsaPrivateComponents::new(
        modulus,
        public_exponent,
        private_exponent.to_vec(),
        None,
    ))
}

fn parse_mpis(input: &[u8], count: usize) -> Option<Vec<Vec<u8>>> {
    let mut remainder = input;
    let mut output = Vec::with_capacity(count);
    for _ in 0..count {
        let (value, rest) = take_mpi(remainder)?;
        if value.is_empty() || value.first() == Some(&0) {
            return None;
        }
        output.push(value.to_vec());
        remainder = rest;
    }
    remainder.is_empty().then_some(output)
}

fn decode_plain_session_key(
    plaintext: &[u8],
    typ: EskType,
) -> pgp::errors::Result<PlainSessionKey> {
    match typ {
        EskType::V3_4 => {
            if plaintext.len() < 4 {
                return Err("invalid OpenPGP RSA session key".to_owned().into());
            }
            let algorithm = SymmetricKeyAlgorithm::from(plaintext[0]);
            let key = &plaintext[1..plaintext.len() - 2];
            if key.len() != algorithm.key_size() {
                return Err("invalid OpenPGP RSA session key size".to_owned().into());
            }
            verify_simple_checksum(key, &plaintext[plaintext.len() - 2..])?;
            Ok(PlainSessionKey::V3_4 {
                sym_alg: algorithm,
                key: key.into(),
            })
        }
        EskType::V6 => {
            if plaintext.len() < 3 {
                return Err("invalid OpenPGP RSA v6 session key".to_owned().into());
            }
            let key = &plaintext[..plaintext.len() - 2];
            verify_simple_checksum(key, &plaintext[plaintext.len() - 2..])?;
            Ok(PlainSessionKey::V6 { key: key.into() })
        }
    }
}

fn verify_simple_checksum(key: &[u8], checksum: &[u8]) -> pgp::errors::Result<()> {
    let expected = u16::from_be_bytes(
        checksum
            .try_into()
            .map_err(|_| pgp::errors::Error::from("invalid OpenPGP checksum".to_owned()))?,
    );
    let actual = key
        .iter()
        .fold(0_u16, |sum, value| sum.wrapping_add(u16::from(*value)));
    if actual != expected {
        return Err("invalid OpenPGP checksum".to_owned().into());
    }
    Ok(())
}
