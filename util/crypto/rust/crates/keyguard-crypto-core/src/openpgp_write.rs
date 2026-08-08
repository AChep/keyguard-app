//! OpenPGP write path and authenticated decryption.
//!
//! rPGP supplies packet composition, armor, public-key encryption, and the
//! non-RSA key implementations. All randomness comes from AWS-LC. RSA private
//! generation, signing, and decryption cross the audited sensitive adapter and
//! never invoke rPGP's RustCrypto RSA private operations.

use std::{
    collections::HashSet,
    io::{BufReader, Cursor, Read, Write},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc::{self, Receiver, SyncSender, TryRecvError},
    },
    thread::{self, JoinHandle},
};

use aes::Aes256;
use aws_lc_rs::rand as aws_lc_rand;
use flate2::{Compression, read::DeflateEncoder as DeflateReader, write::DeflateEncoder};
use ocb3::{
    AeadInPlace, KeyInit, Nonce, Ocb3,
    consts::{U15, U16},
};
use pgp::{
    armor::{self, BlockType, Headers},
    composed::{
        ArmorOptions, DecryptionOptions, Deserializable, DetachedSignature, Esk, Message,
        PlainSessionKey, PublicOrSecret, RawSessionKey, SignedKeyDetails, SignedPublicKey,
        SignedSecretKey, SignedSecretSubKey, SubpacketConfig, TheRing,
    },
    crypto::{
        aead::AeadAlgorithm, ecc_curve::ECCCurve, hash::HashAlgorithm,
        public_key::PublicKeyAlgorithm, sym::SymmetricKeyAlgorithm,
    },
    packet::{
        Features, KeyFlags, OnePassSignature, PacketHeader, PacketTrait, PubKeyInner,
        PublicKeyEncryptedSessionKey, PublicSubkey, SecretKey, SecretSubkey, SignatureConfig,
        SignatureHasher, SignatureType, SignatureVersionSpecific, Subpacket, SubpacketData, UserId,
    },
    ser::Serialize,
    types::{
        CompressionAlgorithm, DecryptionKey, Duration, EncryptionKey, EskType, Fingerprint,
        KeyDetails, KeyId, KeyVersion, Mpi, Password, PkeskBytes, PkeskVersion, PlainSecretParams,
        PublicParams, S2kParams, SecretParams, Seipdv1ReadMode, SignatureBytes, SigningKey, Tag,
        Timestamp, VerifyingKey,
    },
};
use prost::Message as _;
use rand::{CryptoRng, Error as RandError, RngCore};
use thiserror::Error;
use zeroize::{Zeroize, Zeroizing};

use keyguard_crypto_sensitive::{
    RsaPrivateComponents, RsaSignatureHash, decrypt_rsa_pkcs1_v1_5, generate_rsa_pkcs1_der,
    sign_rsa_pkcs1_v1_5_digest,
};

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    openpgp_packets::{RawPacketError, RawPacketSpan, RawPacketStream},
    openpgp_read::{
        CertificatePolicy, OpenPgpReadBudget, PublicComponent, all_components,
        component_is_expired, encryption_component_usable, evaluate_preverified_signature,
        fingerprint_hex, inspect_certificate, normalize_fingerprint, parse_public_key_documents,
        reference_time, signing_component_usable,
    },
    protocol::{
        OpenPgpClearSignStreamOpenRequest, OpenPgpDecryptFinal, OpenPgpDecryptRequest,
        OpenPgpDecryptResult, OpenPgpDecryptStreamOpenRequest,
        OpenPgpDetachedSignStreamOpenRequest, OpenPgpEncryptFinal, OpenPgpEncryptRequest,
        OpenPgpEncryptResult, OpenPgpEncryptStreamOpenRequest, OpenPgpKeyGenerateRequest,
        OpenPgpKeyImportError, OpenPgpKeyImportErrorReason, OpenPgpKeyImportNeedsPassphrase,
        OpenPgpKeyImportRequest, OpenPgpKeyImportResult, OpenPgpKeyImportSuccess, OpenPgpKeyKind,
        OpenPgpKeyMaterial, OpenPgpLiteralMetadata, OpenPgpProtectionMode, OpenPgpSignKind,
        OpenPgpSignRequest, OpenPgpVerification, open_pgp_key_import_result,
    },
};

const MAX_OPENPGP_KEYS: usize = 64;
const MAX_OPENPGP_COMPONENTS: usize = 64;
const MAX_OPENPGP_NESTING: usize = 64;
const MAX_OPENPGP_PRIVATE_KEY_ATTEMPTS: usize = 64;
const MAX_OPENPGP_PACKETS: usize = 4_096;
const MAX_USER_ID_BYTES: usize = 16 * 1024;
const MAX_FILE_NAME_BYTES: usize = 4 * 1024;
const MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES: usize = 64 * 1024;
const GNUPG_AEAD_CHUNK_OCTET: u8 = 10;
const GNUPG_AEAD_CHUNK_BYTES: usize = 1 << (GNUPG_AEAD_CHUNK_OCTET as usize + 6);
const AEAD_TAG_BYTES: usize = 16;
const OPENPGP_PARTIAL_PACKET_BYTES: usize = 64 * 1024;
const OPENPGP_PARTIAL_PACKET_OCTET: u8 = 0xf0;
const MAX_OPENPGP_STREAM_WORKERS: usize = 4;
const STREAM_CHANNEL_DEPTH: usize = 4;
const CLEAR_SIGN_HEADER: &[u8] = b"-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n";

static OPENPGP_STREAM_WORKERS: AtomicUsize = AtomicUsize::new(0);

type Aes256Ocb = Ocb3<Aes256, U15, U16>;

/// Stable internal write-path failure classification.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum OpenPgpWriteError {
    /// A request enum, key, message, or control value is malformed.
    #[error("invalid OpenPGP write request")]
    InvalidArgument,
    /// All supplied private candidates, or any strict recipient, uses v2/v3.
    #[error("unsupported legacy OpenPGP key version")]
    UnsupportedKeyVersion(u8),
    /// No policy-valid signing, recipient, or decryption component exists.
    #[error("no usable OpenPGP key")]
    MissingKey,
    /// An encrypted message failed MDC or AEAD authentication.
    #[error("OpenPGP authentication failed")]
    AuthenticationFailed,
    /// An explicit parser, allocation, or work bound was exceeded.
    #[error("OpenPGP write resource limit exceeded")]
    ResourceLimit,
    /// A cryptographic backend rejected the operation.
    #[error("OpenPGP cryptographic operation failed")]
    CryptoFailure,
    /// A worker or internal composition invariant failed.
    #[error("OpenPGP write operation failed")]
    Internal,
    /// A streaming worker panic was contained before crossing the native boundary.
    #[error("OpenPGP streaming worker panicked")]
    Panic,
}

/// rand 0.8 adapter whose only entropy source is AWS-LC.
#[derive(Clone, Copy, Debug, Default)]
struct AwsLcRng;

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

impl std::fmt::Debug for PublicComponent {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PublicComponent")
            .field("fingerprint", &fingerprint_hex(self))
            .finish_non_exhaustive()
    }
}

impl KeyDetails for PublicComponent {
    fn version(&self) -> KeyVersion {
        match self {
            Self::Primary(key) => key.version(),
            Self::Subkey(key) => key.version(),
        }
    }

    fn legacy_key_id(&self) -> KeyId {
        match self {
            Self::Primary(key) => key.legacy_key_id(),
            Self::Subkey(key) => key.legacy_key_id(),
        }
    }

    fn fingerprint(&self) -> Fingerprint {
        match self {
            Self::Primary(key) => key.fingerprint(),
            Self::Subkey(key) => key.fingerprint(),
        }
    }

    fn algorithm(&self) -> PublicKeyAlgorithm {
        match self {
            Self::Primary(key) => key.algorithm(),
            Self::Subkey(key) => key.algorithm(),
        }
    }

    fn created_at(&self) -> Timestamp {
        match self {
            Self::Primary(key) => key.created_at(),
            Self::Subkey(key) => key.created_at(),
        }
    }

    fn legacy_v3_expiration_days(&self) -> Option<u16> {
        match self {
            Self::Primary(key) => key.legacy_v3_expiration_days(),
            Self::Subkey(key) => key.legacy_v3_expiration_days(),
        }
    }

    fn public_params(&self) -> &PublicParams {
        match self {
            Self::Primary(key) => key.public_params(),
            Self::Subkey(key) => key.public_params(),
        }
    }
}

impl EncryptionKey for PublicComponent {
    fn encrypt<R: CryptoRng + rand::Rng>(
        &self,
        rng: R,
        plain: &[u8],
        typ: EskType,
    ) -> pgp::errors::Result<PkeskBytes> {
        match self {
            Self::Primary(key) => key.encrypt(rng, plain, typ),
            Self::Subkey(key) => key.encrypt(rng, plain, typ),
        }
    }
}

impl VerifyingKey for PublicComponent {
    fn verify(
        &self,
        hash: HashAlgorithm,
        data: &[u8],
        signature: &SignatureBytes,
    ) -> pgp::errors::Result<()> {
        match self {
            Self::Primary(key) => key.verify(hash, data, signature),
            Self::Subkey(key) => key.verify(hash, data, signature),
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum SecretPacketRef<'a> {
    Primary(&'a SecretKey),
    Subkey(&'a SecretSubkey),
}

#[derive(Clone, Copy, Debug)]
enum SecretPacketSelection {
    Primary,
    Subkey(usize),
}

impl SecretPacketSelection {
    fn from_ref(
        secret: &SignedSecretKey,
        packet: SecretPacketRef<'_>,
    ) -> Result<Self, OpenPgpWriteError> {
        match packet {
            SecretPacketRef::Primary(_) => Ok(Self::Primary),
            SecretPacketRef::Subkey(key) => secret
                .secret_subkeys
                .iter()
                .position(|subkey| std::ptr::eq(&subkey.key, key))
                .map(Self::Subkey)
                .ok_or(OpenPgpWriteError::Internal),
        }
    }

    fn packet<'a>(
        self,
        secret: &'a SignedSecretKey,
    ) -> Result<SecretPacketRef<'a>, OpenPgpWriteError> {
        match self {
            Self::Primary => Ok(SecretPacketRef::Primary(&secret.primary_key)),
            Self::Subkey(index) => secret
                .secret_subkeys
                .get(index)
                .map(|subkey| SecretPacketRef::Subkey(&subkey.key))
                .ok_or(OpenPgpWriteError::Internal),
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

#[derive(Clone, Copy)]
struct SigningKeyRef<'a>(&'a dyn SigningKey);

impl std::fmt::Debug for SigningKeyRef<'_> {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("SigningKeyRef")
            .finish_non_exhaustive()
    }
}

impl KeyDetails for SigningKeyRef<'_> {
    fn version(&self) -> KeyVersion {
        self.0.version()
    }

    fn legacy_key_id(&self) -> KeyId {
        self.0.legacy_key_id()
    }

    fn fingerprint(&self) -> Fingerprint {
        self.0.fingerprint()
    }

    fn algorithm(&self) -> PublicKeyAlgorithm {
        self.0.algorithm()
    }

    fn created_at(&self) -> Timestamp {
        self.0.created_at()
    }

    fn legacy_v3_expiration_days(&self) -> Option<u16> {
        self.0.legacy_v3_expiration_days()
    }

    fn public_params(&self) -> &PublicParams {
        self.0.public_params()
    }
}

impl SigningKey for SigningKeyRef<'_> {
    fn sign(
        &self,
        key_pw: &Password,
        hash: HashAlgorithm,
        data: &[u8],
    ) -> pgp::errors::Result<SignatureBytes> {
        self.0.sign(key_pw, hash, data)
    }

    fn hash_alg(&self) -> HashAlgorithm {
        self.0.hash_alg()
    }
}

/// rPGP signing/decryption-key adapter that routes RSA private operations to
/// the audited AWS-LC boundary.
#[derive(Clone, Copy, Debug)]
pub(crate) struct AwsLcRsaSecretKey<'a> {
    packet: SecretPacketRef<'a>,
}

impl<'a> AwsLcRsaSecretKey<'a> {
    pub(crate) fn new(packet: SecretPacketRef<'a>) -> Result<Self, OpenPgpWriteError> {
        if !matches!(
            packet.algorithm(),
            PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt | PublicKeyAlgorithm::RSASign
        ) {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        Ok(Self { packet })
    }

    fn private_components(
        packet: SecretPacketRef<'_>,
        public: &PublicParams,
        private: &PlainSecretParams,
    ) -> pgp::errors::Result<RsaPrivateComponents> {
        let PublicParams::RSA(public) = public else {
            return Err("inconsistent RSA public parameters".to_owned().into());
        };
        let PlainSecretParams::RSA(private) = private else {
            return Err("inconsistent RSA private parameters".to_owned().into());
        };
        if !matches!(
            packet.algorithm(),
            PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt | PublicKeyAlgorithm::RSASign
        ) {
            return Err("unsupported RSA algorithm identifier".to_owned().into());
        }

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

fn is_rsa_private_algorithm(algorithm: PublicKeyAlgorithm) -> bool {
    matches!(
        algorithm,
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt | PublicKeyAlgorithm::RSASign
    )
}

/// Generates a complete v4 certificate and returns an encoded
/// [`OpenPgpKeyMaterial`] payload.
pub(crate) fn generate_key_request(
    request: OpenPgpKeyGenerateRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let user_id = request.user_id.trim();
    if user_id.is_empty()
        || user_id.len() > MAX_USER_ID_BYTES
        || request.creation_time_epoch_seconds > u64::from(u32::MAX)
        || request.expiration_seconds == Some(0)
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let created_at = Timestamp::from_secs(request.creation_time_epoch_seconds as u32);
    let kind =
        OpenPgpKeyKind::try_from(request.kind).map_err(|_| OpenPgpWriteError::InvalidArgument)?;

    let certificate = match kind {
        OpenPgpKeyKind::LegacyEd25519X25519 => {
            generate_modern_certificate(user_id, created_at, request.expiration_seconds)?
        }
        OpenPgpKeyKind::Rsa => generate_rsa_certificate(
            user_id,
            created_at,
            request.expiration_seconds,
            request.rsa_bits,
        )?,
        OpenPgpKeyKind::Unspecified => return Err(OpenPgpWriteError::InvalidArgument),
    };
    encode_key_material(&certificate).map(|material| material.encode_to_vec())
}

/// Imports the first transferable secret key, removes password protection from
/// every secret component, and returns an encoded typed domain result.
pub(crate) fn import_key_request(
    mut request: OpenPgpKeyImportRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let key_data = Zeroizing::new(std::mem::take(&mut request.key_data));
    let passphrase = request.passphrase_utf8.take().map(Zeroizing::new);
    if key_data.iter().all(u8::is_ascii_whitespace) {
        return Ok(import_error(OpenPgpKeyImportErrorReason::Empty));
    }
    if key_data.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    if passphrase
        .as_deref()
        .is_some_and(|value| std::str::from_utf8(value).is_err())
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }

    let stream = match RawPacketStream::parse(key_data.as_slice(), MAX_OPENPGP_PACKETS) {
        Ok(stream) => stream,
        Err(RawPacketError::ResourceLimit) => return Err(OpenPgpWriteError::ResourceLimit),
        Err(RawPacketError::Malformed) => {
            return Ok(import_error(OpenPgpKeyImportErrorReason::MalformedKey));
        }
    };
    let Some(certificate_range) = stream.first_secret_certificate() else {
        let reason = if stream.packets().iter().any(|packet| packet.tag() == 6) {
            OpenPgpKeyImportErrorReason::UnsupportedFormat
        } else {
            OpenPgpKeyImportErrorReason::MalformedKey
        };
        return Ok(import_error(reason));
    };
    let material = match import_packet_material(
        &stream,
        certificate_range,
        passphrase.as_deref().map(Vec::as_slice),
    ) {
        Ok(material) => material,
        Err(ImportPacketError::NeedsPassphrase) => {
            return Ok(OpenPgpKeyImportResult {
                result: Some(open_pgp_key_import_result::Result::NeedsPassphrase(
                    OpenPgpKeyImportNeedsPassphrase {
                        format_label: "OpenPGP".to_owned(),
                    },
                )),
            }
            .encode_to_vec());
        }
        Err(ImportPacketError::InvalidPassphrase) => {
            return Ok(import_error(OpenPgpKeyImportErrorReason::InvalidPassphrase));
        }
        Err(ImportPacketError::UnsupportedFormat) => {
            return Ok(import_error(OpenPgpKeyImportErrorReason::UnsupportedFormat));
        }
        Err(ImportPacketError::Malformed) => {
            return Ok(import_error(OpenPgpKeyImportErrorReason::MalformedKey));
        }
        Err(ImportPacketError::ResourceLimit) => return Err(OpenPgpWriteError::ResourceLimit),
        Err(ImportPacketError::Internal) => return Err(OpenPgpWriteError::Internal),
    };
    Ok(OpenPgpKeyImportResult {
        result: Some(open_pgp_key_import_result::Result::Success(
            OpenPgpKeyImportSuccess {
                key_material: Some(material),
            },
        )),
    }
    .encode_to_vec())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ImportPacketError {
    Malformed,
    UnsupportedFormat,
    NeedsPassphrase,
    InvalidPassphrase,
    ResourceLimit,
    Internal,
}

enum ImportSecretPacket {
    Primary(SecretKey),
    Subkey(SecretSubkey),
}

impl ImportSecretPacket {
    fn version(&self) -> KeyVersion {
        match self {
            Self::Primary(key) => key.version(),
            Self::Subkey(key) => key.version(),
        }
    }

    fn public_len(&self) -> usize {
        match self {
            Self::Primary(key) => Serialize::write_len(key.public_key()),
            Self::Subkey(key) => Serialize::write_len(key.public_key()),
        }
    }

    fn is_encrypted(&self) -> bool {
        match self {
            Self::Primary(key) => key.secret_params().is_encrypted(),
            Self::Subkey(key) => key.secret_params().is_encrypted(),
        }
    }

    fn fingerprint(&self) -> Fingerprint {
        match self {
            Self::Primary(key) => key.fingerprint(),
            Self::Subkey(key) => key.fingerprint(),
        }
    }

    fn write_public_body(&self, output: &mut Vec<u8>) -> pgp::errors::Result<()> {
        match self {
            Self::Primary(key) => key.public_key().to_writer(output),
            Self::Subkey(key) => key.public_key().to_writer(output),
        }
    }

    fn write_secret_body(&self, output: &mut Vec<u8>) -> pgp::errors::Result<()> {
        match self {
            Self::Primary(key) => key.to_writer(output),
            Self::Subkey(key) => key.to_writer(output),
        }
    }

    fn remove_password(
        &mut self,
        password: &Password,
        original_s2k_usage: u8,
    ) -> pgp::errors::Result<()> {
        match self {
            Self::Primary(key) => {
                remove_primary_password_compatible(key, password, original_s2k_usage)
            }
            Self::Subkey(key) => {
                remove_subkey_password_compatible(key, password, original_s2k_usage)
            }
        }
    }
}

struct ParsedImportPacket<'a> {
    span: &'a RawPacketSpan,
    secret: Option<ImportSecretPacket>,
    body: Option<Zeroizing<Vec<u8>>>,
    public_len: usize,
}

fn import_packet_material(
    stream: &RawPacketStream,
    certificate_range: std::ops::Range<usize>,
    passphrase: Option<&[u8]>,
) -> Result<OpenPgpKeyMaterial, ImportPacketError> {
    let spans = stream
        .packets()
        .get(certificate_range)
        .ok_or(ImportPacketError::Malformed)?;
    let mut parsed = Vec::with_capacity(spans.len());
    let mut subkey_components = 0usize;
    for (position, span) in spans.iter().enumerate() {
        if !allowed_transferable_secret_tag(span.tag(), position == 0) {
            return Err(ImportPacketError::Malformed);
        }
        if matches!(span.tag(), 7 | 14) {
            subkey_components = subkey_components
                .checked_add(1)
                .filter(|count| *count <= MAX_OPENPGP_COMPONENTS)
                .ok_or(ImportPacketError::ResourceLimit)?;
        }
        let secret = match span.tag() {
            5 | 7 => Some(parse_import_secret_packet(stream, span)?),
            _ => None,
        };
        if span.tag() == 14 {
            let subkey = parse_import_public_subkey(stream, span)?;
            if matches!(subkey.version(), KeyVersion::V2 | KeyVersion::V3) {
                return Err(ImportPacketError::UnsupportedFormat);
            }
        }
        let (body, public_len) = if let Some(secret) = &secret {
            let body = stream.body(span);
            let public_len = secret.public_len();
            let mut serialized_public = Vec::with_capacity(public_len);
            secret
                .write_public_body(&mut serialized_public)
                .map_err(|_| ImportPacketError::Malformed)?;
            if serialized_public.len() != public_len
                || body.get(..public_len) != Some(serialized_public.as_slice())
                || body.get(public_len).is_none()
            {
                return Err(ImportPacketError::UnsupportedFormat);
            }
            (Some(body), public_len)
        } else {
            (None, 0)
        };
        parsed.push(ParsedImportPacket {
            span,
            secret,
            body,
            public_len,
        });
    }
    if parsed
        .first()
        .and_then(|packet| packet.secret.as_ref())
        .is_none()
    {
        return Err(ImportPacketError::Malformed);
    }
    if parsed.iter().any(|packet| {
        packet
            .secret
            .as_ref()
            .is_some_and(|secret| matches!(secret.version(), KeyVersion::V2 | KeyVersion::V3))
    }) {
        return Err(ImportPacketError::UnsupportedFormat);
    }
    if passphrase.is_none()
        && parsed.iter().any(|packet| {
            packet
                .secret
                .as_ref()
                .is_some_and(ImportSecretPacket::is_encrypted)
        })
    {
        return Err(ImportPacketError::NeedsPassphrase);
    }

    let password = passphrase.map_or_else(Password::empty, Password::from);
    let primary_fingerprint = parsed
        .first()
        .and_then(|packet| packet.secret.as_ref())
        .map(ImportSecretPacket::fingerprint)
        .ok_or(ImportPacketError::Malformed)?;
    let mut private_packets = Zeroizing::new(Vec::new());
    let mut public_packets = Vec::new();
    for packet in &mut parsed {
        let Some(secret) = packet.secret.as_mut() else {
            private_packets.extend_from_slice(stream.raw(packet.span));
            public_packets.extend_from_slice(stream.raw(packet.span));
            continue;
        };
        let body = packet.body.as_ref().ok_or(ImportPacketError::Internal)?;
        write_fixed_packet(
            public_packet_tag(packet.span.tag()),
            &body[..packet.public_len],
            &mut public_packets,
        )?;
        if !secret.is_encrypted() {
            private_packets.extend_from_slice(stream.raw(packet.span));
            continue;
        }
        let usage = body
            .get(packet.public_len)
            .copied()
            .ok_or(ImportPacketError::Malformed)?;
        secret
            .remove_password(&password, usage)
            .map_err(|_| ImportPacketError::InvalidPassphrase)?;
        let mut unlocked_body = Zeroizing::new(Vec::new());
        secret
            .write_secret_body(&mut unlocked_body)
            .map_err(|_| ImportPacketError::Internal)?;
        if unlocked_body.get(..packet.public_len) != Some(&body[..packet.public_len]) {
            return Err(ImportPacketError::UnsupportedFormat);
        }
        let suffix = unlocked_body
            .get(packet.public_len..)
            .ok_or(ImportPacketError::Internal)?;
        let mut preserved_body = Zeroizing::new(Vec::with_capacity(body.len()));
        preserved_body.extend_from_slice(&body[..packet.public_len]);
        preserved_body.extend_from_slice(suffix);
        write_fixed_packet(packet.span.tag(), &preserved_body, &mut private_packets)?;
    }

    Ok(OpenPgpKeyMaterial {
        private_key_armored: armor_key_packets(&private_packets, BlockType::PrivateKey)?,
        public_key_armored: armor_key_packets(&public_packets, BlockType::PublicKey)?,
        fingerprint: format!("{primary_fingerprint:X}"),
    })
}

fn parse_import_secret_packet(
    stream: &RawPacketStream,
    span: &RawPacketSpan,
) -> Result<ImportSecretPacket, ImportPacketError> {
    let body = stream.body(span);
    let length = u32::try_from(span.body_len()).map_err(|_| ImportPacketError::ResourceLimit)?;
    let tag = Tag::from(span.tag());
    let header = PacketHeader::new_fixed(tag, length);
    let mut reader = Cursor::new(body.as_slice());
    let secret = match span.tag() {
        5 => SecretKey::try_from_reader(header, &mut reader).map(ImportSecretPacket::Primary),
        7 => SecretSubkey::try_from_reader(header, &mut reader).map(ImportSecretPacket::Subkey),
        _ => return Err(ImportPacketError::Malformed),
    }
    .map_err(|_| ImportPacketError::Malformed)?;
    if usize::try_from(reader.position()).ok() != Some(body.len()) {
        return Err(ImportPacketError::Malformed);
    }
    Ok(secret)
}

fn parse_import_public_subkey(
    stream: &RawPacketStream,
    span: &RawPacketSpan,
) -> Result<PublicSubkey, ImportPacketError> {
    let body = stream.body(span);
    let length = u32::try_from(span.body_len()).map_err(|_| ImportPacketError::ResourceLimit)?;
    let header = PacketHeader::new_fixed(Tag::PublicSubkey, length);
    let mut reader = Cursor::new(body.as_slice());
    let subkey = PublicSubkey::try_from_reader(header, &mut reader)
        .map_err(|_| ImportPacketError::Malformed)?;
    if usize::try_from(reader.position()).ok() != Some(body.len()) {
        return Err(ImportPacketError::Malformed);
    }
    Ok(subkey)
}

fn allowed_transferable_secret_tag(tag: u8, is_first: bool) -> bool {
    match tag {
        5 => is_first,
        2 | 7 | 10 | 12 | 13 | 14 | 17 | 21 | 40..=63 => !is_first,
        _ => false,
    }
}

fn public_packet_tag(secret_tag: u8) -> u8 {
    match secret_tag {
        5 => 6,
        7 => 14,
        _ => secret_tag,
    }
}

fn write_fixed_packet(tag: u8, body: &[u8], output: &mut Vec<u8>) -> Result<(), ImportPacketError> {
    let length = u32::try_from(body.len()).map_err(|_| ImportPacketError::ResourceLimit)?;
    PacketHeader::new_fixed(Tag::from(tag), length)
        .to_writer(output)
        .map_err(|_| ImportPacketError::Internal)?;
    output.extend_from_slice(body);
    Ok(())
}

fn armor_key_packets(packets: &[u8], block_type: BlockType) -> Result<Vec<u8>, ImportPacketError> {
    let options = ArmorOptions::default();
    let mut output = Vec::new();
    armor::write(
        &RawPackets(packets),
        block_type,
        &mut output,
        options.headers,
        options.include_checksum,
    )
    .map_err(|_| ImportPacketError::Internal)?;
    Ok(output)
}

fn remove_primary_password_compatible(
    key: &mut SecretKey,
    password: &Password,
    original_s2k_usage: u8,
) -> pgp::errors::Result<()> {
    if original_s2k_usage != 255 {
        return key.remove_password(password);
    }
    let plain = unlock_malleable_cfb(key.secret_params(), key.public_key(), password)?;
    *key = SecretKey::new(key.public_key().clone(), SecretParams::Plain(plain))?;
    Ok(())
}

fn remove_subkey_password_compatible(
    key: &mut SecretSubkey,
    password: &Password,
    original_s2k_usage: u8,
) -> pgp::errors::Result<()> {
    if original_s2k_usage != 255 {
        return key.remove_password(password);
    }
    let plain = unlock_malleable_cfb(key.secret_params(), key.public_key(), password)?;
    *key = SecretSubkey::new(key.public_key().clone(), SecretParams::Plain(plain))?;
    Ok(())
}

fn unlock_malleable_cfb<K>(
    secret_params: &SecretParams,
    public: &K,
    password: &Password,
) -> pgp::errors::Result<PlainSecretParams>
where
    K: KeyDetails + Serialize,
{
    let SecretParams::Encrypted(encrypted) = secret_params else {
        return Err("inconsistent protected OpenPGP key".to_owned().into());
    };
    // rPGP 0.20 parses legacy usage 255 as usage 254. Keep this compatibility
    // path gated by the original packet octet so SHA-1-protected keys never
    // fall back to the weaker two-octet checksum.
    let S2kParams::Cfb { sym_alg, s2k, iv } = encrypted.string_to_key_params() else {
        return Err("inconsistent legacy OpenPGP protection".to_owned().into());
    };
    let derived = s2k.derive_key(&password.read(), sym_alg.key_size())?;
    let mut plaintext = Zeroizing::new(encrypted.data().to_vec());
    sym_alg.decrypt_with_iv_regular(derived.as_ref(), iv, &mut plaintext)?;
    PlainSecretParams::try_from_reader(
        Cursor::new(plaintext.as_slice()),
        public.version(),
        public.algorithm(),
        public.public_params(),
    )
}

/// Signs bounded content with a policy-valid primary key or signing subkey.
pub(crate) fn sign_request(mut request: OpenPgpSignRequest) -> Result<Vec<u8>, OpenPgpWriteError> {
    if request.content.len() > MAX_CONTROL_ENVELOPE_BYTES
        || request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
    {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    let kind =
        OpenPgpSignKind::try_from(request.kind).map_err(|_| OpenPgpWriteError::InvalidArgument)?;
    if kind == OpenPgpSignKind::Unspecified
        || request
            .signature_time_epoch_seconds
            .is_some_and(|value| value > u64::from(u32::MAX))
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }

    let private_key = std::mem::take(&mut request.private_key);
    let preferred_fingerprint = std::mem::take(&mut request.preferred_fingerprint);
    match kind {
        OpenPgpSignKind::Detached => {
            let mut session = DetachedSigningSession::open(OpenPgpDetachedSignStreamOpenRequest {
                private_key,
                preferred_fingerprint,
                armored: request.armored,
                signature_time_epoch_seconds: request.signature_time_epoch_seconds,
                reference_time_epoch_seconds: request.reference_time_epoch_seconds,
            })?;
            session.update(&request.content)?;
            session.finish()
        }
        OpenPgpSignKind::ClearText => {
            let mut session = ClearSigningSession::open(OpenPgpClearSignStreamOpenRequest {
                private_key,
                preferred_fingerprint,
                signature_time_epoch_seconds: request.signature_time_epoch_seconds,
                reference_time_epoch_seconds: request.reference_time_epoch_seconds,
            })?;
            let mut output = session.update(&request.content)?;
            output.extend_from_slice(&session.finish()?);
            Ok(output)
        }
        OpenPgpSignKind::Unspecified => Err(OpenPgpWriteError::InvalidArgument),
    }
}

/// Incremental detached signer. Only the SHA-256 state grows with input; key
/// material and output remain bounded by the stream-open control envelope.
pub(crate) struct DetachedSigningSession {
    secret: SignedSecretKey,
    selection: SecretPacketSelection,
    hasher: SignatureHasher,
    armored: bool,
}

impl DetachedSigningSession {
    pub(crate) fn open(
        mut request: OpenPgpDetachedSignStreamOpenRequest,
    ) -> Result<Self, OpenPgpWriteError> {
        if request.private_key.is_empty()
            || request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
            || request
                .signature_time_epoch_seconds
                .is_some_and(|value| value > u64::from(u32::MAX))
        {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
        let secret = parse_secret_key(private_key.as_slice())?;
        let packet = select_signing_packet(
            &secret,
            &request.preferred_fingerprint,
            reference_time(request.reference_time_epoch_seconds),
        )?;
        let selection = SecretPacketSelection::from_ref(&secret, packet)?;
        let signature_time = request
            .signature_time_epoch_seconds
            .map_or_else(Timestamp::now, |value| Timestamp::from_secs(value as u32));
        let hasher = detached_signature_hasher(packet, signature_time, SignatureType::Binary)?;
        Ok(Self {
            secret,
            selection,
            hasher,
            armored: request.armored,
        })
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<(), OpenPgpWriteError> {
        self.hasher
            .write_all(data)
            .map_err(|_| OpenPgpWriteError::Internal)
    }

    pub(crate) fn finish(self) -> Result<Vec<u8>, OpenPgpWriteError> {
        let packet = self.selection.packet(&self.secret)?;
        let signature = sign_hasher_with_packet(self.hasher, packet)?;
        let signature = DetachedSignature::new(signature);
        if self.armored {
            signature
                .to_armored_bytes(ArmorOptions::default())
                .map_err(|_| OpenPgpWriteError::Internal)
        } else {
            signature
                .to_bytes()
                .map_err(|_| OpenPgpWriteError::Internal)
        }
    }
}

fn detached_signature_hasher(
    packet: SecretPacketRef<'_>,
    signature_time: Timestamp,
    signature_type: SignatureType,
) -> Result<SignatureHasher, OpenPgpWriteError> {
    if is_rsa_private_algorithm(packet.algorithm()) {
        let adapter = AwsLcRsaSecretKey::new(packet)?;
        signature_hasher_with_key(&adapter, signature_time, signature_type)
    } else {
        match packet {
            SecretPacketRef::Primary(key) => {
                signature_hasher_with_key(key, signature_time, signature_type)
            }
            SecretPacketRef::Subkey(key) => {
                signature_hasher_with_key(key, signature_time, signature_type)
            }
        }
    }
}

fn sign_hasher_with_packet(
    hasher: SignatureHasher,
    packet: SecretPacketRef<'_>,
) -> Result<pgp::packet::Signature, OpenPgpWriteError> {
    if is_rsa_private_algorithm(packet.algorithm()) {
        let adapter = AwsLcRsaSecretKey::new(packet)?;
        hasher.sign(&adapter, &Password::empty())
    } else {
        match packet {
            SecretPacketRef::Primary(key) => hasher.sign(key, &Password::empty()),
            SecretPacketRef::Subkey(key) => hasher.sign(key, &Password::empty()),
        }
    }
    .map_err(|_| OpenPgpWriteError::CryptoFailure)
}

fn signature_hasher_with_key(
    key: &impl SigningKey,
    signature_time: Timestamp,
    signature_type: SignatureType,
) -> Result<SignatureHasher, OpenPgpWriteError> {
    let mut config = data_signature_config(key, signature_type)?;
    let SubpacketConfig::UserDefined { hashed, unhashed } =
        signing_subpackets(key, signature_time)?
    else {
        return Err(OpenPgpWriteError::Internal);
    };
    config.hashed_subpackets = hashed;
    config.unhashed_subpackets = unhashed;
    config
        .into_hasher()
        .map_err(|_| OpenPgpWriteError::CryptoFailure)
}

/// Incremental cleartext signer. Only up to 64 KiB of trailing horizontal
/// whitespace and an incomplete UTF-8 code point are retained between chunks.
pub(crate) struct ClearSigningSession {
    secret: SignedSecretKey,
    selection: SecretPacketSelection,
    hasher: SignatureHasher,
    pending_whitespace: Zeroizing<Vec<u8>>,
    utf8_tail: Zeroizing<Vec<u8>>,
    started: bool,
    line_start: bool,
    canonical_needs_break: bool,
    previous_input_was_cr: bool,
    output_ended_with_line_break: bool,
}

impl ClearSigningSession {
    pub(crate) fn open(
        mut request: OpenPgpClearSignStreamOpenRequest,
    ) -> Result<Self, OpenPgpWriteError> {
        if request.private_key.is_empty()
            || request.private_key.len() > MAX_CONTROL_ENVELOPE_BYTES
            || request
                .signature_time_epoch_seconds
                .is_some_and(|value| value > u64::from(u32::MAX))
        {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
        let secret = parse_secret_key(private_key.as_slice())?;
        let packet = select_signing_packet(
            &secret,
            &request.preferred_fingerprint,
            reference_time(request.reference_time_epoch_seconds),
        )?;
        let selection = SecretPacketSelection::from_ref(&secret, packet)?;
        let signature_time = request
            .signature_time_epoch_seconds
            .map_or_else(Timestamp::now, |value| Timestamp::from_secs(value as u32));
        let hasher = detached_signature_hasher(packet, signature_time, SignatureType::Text)?;
        Ok(Self {
            secret,
            selection,
            hasher,
            pending_whitespace: Zeroizing::new(Vec::new()),
            utf8_tail: Zeroizing::new(Vec::new()),
            started: false,
            line_start: true,
            canonical_needs_break: false,
            previous_input_was_cr: false,
            output_ended_with_line_break: false,
        })
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
        self.validate_pending_whitespace_run(data)?;
        self.validate_utf8(data)?;
        let header_length = if self.started {
            0
        } else {
            CLEAR_SIGN_HEADER.len()
        };
        let mut output = Vec::with_capacity(data.len() + header_length + 2);
        if !self.started {
            output.extend_from_slice(CLEAR_SIGN_HEADER);
            self.started = true;
        }
        // Canonical bytes are hashed as contiguous `data` runs rather than one
        // byte at a time. Whitespace stays provisional until a later
        // non-whitespace byte on the same line confirms it; only whitespace
        // that is still provisional at the end of the chunk is copied into
        // `pending_whitespace`.
        let mut hash_run: Option<(usize, usize)> = None;
        let mut whitespace_start: Option<usize> = None;
        for (index, &byte) in data.iter().enumerate() {
            if self.previous_input_was_cr && byte == b'\n' {
                output.push(byte);
                self.previous_input_was_cr = false;
                self.line_start = true;
                self.output_ended_with_line_break = true;
                continue;
            }
            self.previous_input_was_cr = false;
            if matches!(byte, b'\r' | b'\n') {
                self.hash_canonical_run(data, hash_run.take())?;
                whitespace_start = None;
                if self.canonical_needs_break {
                    self.hasher
                        .write_all(b"\r\n")
                        .map_err(|_| OpenPgpWriteError::Internal)?;
                }
                self.pending_whitespace.clear();
                self.canonical_needs_break = true;
                self.previous_input_was_cr = byte == b'\r';
                self.line_start = true;
                self.output_ended_with_line_break = true;
                output.push(byte);
                continue;
            }
            if self.canonical_needs_break {
                self.hasher
                    .write_all(b"\r\n")
                    .map_err(|_| OpenPgpWriteError::Internal)?;
                self.canonical_needs_break = false;
            }
            if matches!(byte, b' ' | b'\t') {
                whitespace_start.get_or_insert(index);
            } else {
                // Carried-over whitespace can only precede the first confirmed
                // byte of this chunk, so the run is necessarily empty here.
                if !self.pending_whitespace.is_empty() {
                    self.hasher
                        .write_all(self.pending_whitespace.as_slice())
                        .map_err(|_| OpenPgpWriteError::Internal)?;
                    self.pending_whitespace.clear();
                }
                match hash_run.as_mut() {
                    Some((_, end)) => *end = index + 1,
                    None => hash_run = Some((whitespace_start.unwrap_or(index), index + 1)),
                }
                whitespace_start = None;
            }
            if self.line_start && byte == b'-' {
                output.extend_from_slice(b"- ");
            }
            output.push(byte);
            self.line_start = false;
            self.output_ended_with_line_break = false;
        }
        self.hash_canonical_run(data, hash_run)?;
        if let Some(start) = whitespace_start {
            self.pending_whitespace.extend_from_slice(&data[start..]);
        }
        Ok(output)
    }

    pub(crate) fn finish(mut self) -> Result<Vec<u8>, OpenPgpWriteError> {
        if !self.utf8_tail.is_empty() {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        self.pending_whitespace.clear();
        let packet = self.selection.packet(&self.secret)?;
        let signature = sign_hasher_with_packet(self.hasher, packet)?;
        let armored = DetachedSignature::new(signature)
            .to_armored_bytes(ArmorOptions::default())
            .map_err(|_| OpenPgpWriteError::Internal)?;
        let header_length = if self.started {
            0
        } else {
            CLEAR_SIGN_HEADER.len()
        };
        let mut output = Vec::with_capacity(armored.len() + header_length + 1);
        if !self.started {
            output.extend_from_slice(CLEAR_SIGN_HEADER);
        }
        if !self.output_ended_with_line_break {
            output.push(b'\n');
        }
        output.extend_from_slice(&armored);
        Ok(output)
    }

    fn hash_canonical_run(
        &mut self,
        data: &[u8],
        run: Option<(usize, usize)>,
    ) -> Result<(), OpenPgpWriteError> {
        if let Some((start, end)) = run {
            self.hasher
                .write_all(&data[start..end])
                .map_err(|_| OpenPgpWriteError::Internal)?;
        }
        Ok(())
    }

    fn validate_pending_whitespace_run(&self, data: &[u8]) -> Result<(), OpenPgpWriteError> {
        let mut pending = self.pending_whitespace.len();
        for &byte in data {
            if matches!(byte, b' ' | b'\t') {
                pending = pending
                    .checked_add(1)
                    .filter(|length| *length <= MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES)
                    .ok_or(OpenPgpWriteError::ResourceLimit)?;
            } else {
                pending = 0;
            }
        }
        Ok(())
    }

    fn validate_utf8(&mut self, data: &[u8]) -> Result<(), OpenPgpWriteError> {
        let mut remaining = data;
        if !self.utf8_tail.is_empty() {
            // The retained tail is a valid prefix of a single code point, so
            // its lead byte determines the full sequence length. Completing it
            // needs at most three bytes; the chunk itself is validated
            // borrowed, without copying it.
            let sequence_length = match self.utf8_tail[0] {
                byte if byte >= 0xF0 => 4,
                byte if byte >= 0xE0 => 3,
                _ => 2,
            };
            let needed = sequence_length - self.utf8_tail.len();
            if remaining.len() < needed {
                self.utf8_tail.extend_from_slice(remaining);
                return Ok(());
            }
            let (head, rest) = remaining.split_at(needed);
            let mut sequence = Zeroizing::new([0_u8; 4]);
            sequence[..self.utf8_tail.len()].copy_from_slice(&self.utf8_tail);
            sequence[self.utf8_tail.len()..sequence_length].copy_from_slice(head);
            if std::str::from_utf8(&sequence[..sequence_length]).is_err() {
                return Err(OpenPgpWriteError::InvalidArgument);
            }
            self.utf8_tail.clear();
            remaining = rest;
        }
        match std::str::from_utf8(remaining) {
            Ok(_) => {}
            Err(error) if error.error_len().is_none() => {
                self.utf8_tail
                    .extend_from_slice(&remaining[error.valid_up_to()..]);
            }
            Err(_) => return Err(OpenPgpWriteError::InvalidArgument),
        }
        Ok(())
    }
}

fn parse_secret_key(input: &[u8]) -> Result<SignedSecretKey, OpenPgpWriteError> {
    let packets =
        RawPacketStream::parse(input, MAX_OPENPGP_PACKETS).map_err(|error| match error {
            RawPacketError::Malformed => OpenPgpWriteError::InvalidArgument,
            RawPacketError::ResourceLimit => OpenPgpWriteError::ResourceLimit,
        })?;
    let semantic = packets.semantic_bytes();
    let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(semantic.as_slice()))
        .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
    if let Some(version) = legacy_secret_version(&secret) {
        return Err(OpenPgpWriteError::UnsupportedKeyVersion(version));
    }
    if secret.public_subkeys.len() + secret.secret_subkeys.len() > MAX_OPENPGP_COMPONENTS {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    Ok(secret)
}

fn legacy_secret_version(secret: &SignedSecretKey) -> Option<u8> {
    std::iter::once(secret.primary_key.version())
        .chain(
            secret
                .public_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .chain(
            secret
                .secret_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .find_map(legacy_version_number)
}

fn legacy_public_version(certificate: &SignedPublicKey) -> Option<u8> {
    std::iter::once(certificate.primary_key.version())
        .chain(
            certificate
                .public_subkeys
                .iter()
                .map(|subkey| subkey.key.version()),
        )
        .find_map(legacy_version_number)
}

fn legacy_version_number(version: KeyVersion) -> Option<u8> {
    match version {
        KeyVersion::V2 => Some(2),
        KeyVersion::V3 => Some(3),
        _ => None,
    }
}

fn select_signing_packet<'a>(
    secret: &'a SignedSecretKey,
    preferred_fingerprint: &str,
    reference_time: u64,
) -> Result<SecretPacketRef<'a>, OpenPgpWriteError> {
    let public = secret.to_public_key();
    let candidates = all_components(std::slice::from_ref(&public));
    let mut budget = OpenPgpReadBudget::default();
    let policy = inspect_certificate(&public, &candidates, reference_time, &mut budget)
        .map_err(map_read_error)?;
    if !policy.primary.authenticated
        || policy.primary.revoked
        || component_is_expired(&policy.primary, reference_time)
    {
        return Err(OpenPgpWriteError::MissingKey);
    }

    let primary_usable = signing_component_usable(&policy.primary, reference_time, false);
    let public_offset = secret.public_subkeys.len();
    let usable_subkeys = secret
        .secret_subkeys
        .iter()
        .enumerate()
        .filter_map(|(index, secret_subkey)| {
            let component = policy.subkeys.get(public_offset + index)?;
            signing_component_usable(component, reference_time, true).then_some((
                index,
                secret_subkey.key.created_at().as_secs(),
                fingerprint_hex(&secret_subkey.key),
            ))
        })
        .collect::<Vec<_>>();

    let preferred = normalize_fingerprint(preferred_fingerprint);
    let newest_subkey = usable_subkeys
        .iter()
        .max_by_key(|(_, created_at, fingerprint)| (*created_at, fingerprint.clone()))
        .map(|(index, _, _)| *index);
    if !preferred_fingerprint.is_empty() {
        if preferred.is_empty() {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        if fingerprint_hex(&secret.primary_key) == preferred {
            if let Some(index) = newest_subkey {
                return Ok(SecretPacketRef::Subkey(&secret.secret_subkeys[index].key));
            }
            return primary_usable
                .then_some(SecretPacketRef::Primary(&secret.primary_key))
                .ok_or(OpenPgpWriteError::MissingKey);
        }
        let index = usable_subkeys
            .iter()
            .find_map(|(index, _, fingerprint)| (fingerprint == &preferred).then_some(*index))
            .ok_or(OpenPgpWriteError::MissingKey)?;
        return Ok(SecretPacketRef::Subkey(&secret.secret_subkeys[index].key));
    }

    if let Some(index) = newest_subkey {
        return Ok(SecretPacketRef::Subkey(&secret.secret_subkeys[index].key));
    }
    primary_usable
        .then_some(SecretPacketRef::Primary(&secret.primary_key))
        .ok_or(OpenPgpWriteError::MissingKey)
}

fn signing_subpackets(
    key: &(impl SigningKey + ?Sized),
    signature_time: Timestamp,
) -> Result<SubpacketConfig, OpenPgpWriteError> {
    let hashed = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(signature_time))
            .map_err(pgp_internal)?,
        Subpacket::regular(SubpacketData::IssuerFingerprint(key.fingerprint()))
            .map_err(pgp_internal)?,
    ];
    let unhashed = if key.version() <= KeyVersion::V4 {
        vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(key.legacy_key_id()))
                .map_err(pgp_internal)?,
        ]
    } else {
        Vec::new()
    };
    Ok(SubpacketConfig::UserDefined { hashed, unhashed })
}

fn data_signature_config(
    key: &(impl SigningKey + ?Sized),
    typ: SignatureType,
) -> Result<SignatureConfig, OpenPgpWriteError> {
    match key.version() {
        KeyVersion::V4 => Ok(SignatureConfig::v4(
            typ,
            key.algorithm(),
            HashAlgorithm::Sha256,
        )),
        KeyVersion::V6 => {
            SignatureConfig::v6(AwsLcRng, typ, key.algorithm(), HashAlgorithm::Sha256)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)
        }
        _ => Err(OpenPgpWriteError::InvalidArgument),
    }
}

/// Encrypts bounded content for all policy-valid recipients, selecting GnuPG
/// OCB only when every selected certificate authentically advertises support.
pub(crate) fn encrypt_request(
    mut request: OpenPgpEncryptRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    if request.content.len() > MAX_CONTROL_ENVELOPE_BYTES
        || request.public_keys.is_empty()
        || request.public_keys.len() > MAX_OPENPGP_KEYS
        || request.file_name.is_empty()
        || request.file_name.len() > MAX_FILE_NAME_BYTES
        || request.file_name.len() > usize::from(u8::MAX)
        || (request.signing_private_key.is_none()
            && !request.preferred_signing_fingerprint.is_empty())
        || request
            .literal_time_epoch_seconds
            .is_some_and(|value| value > u64::from(u32::MAX))
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    if request
        .public_keys
        .iter()
        .any(|document| document.len() > MAX_CONTROL_ENVELOPE_BYTES)
    {
        return Err(OpenPgpWriteError::ResourceLimit);
    }

    if let Some(version) = first_legacy_public_version(&request.public_keys)? {
        return Err(OpenPgpWriteError::UnsupportedKeyVersion(version));
    }
    let mut budget = OpenPgpReadBudget::default();
    let certificates =
        parse_public_key_documents(&request.public_keys, &mut budget).map_err(map_read_error)?;
    let policy_time = reference_time(request.reference_time_epoch_seconds);
    let (recipients, all_recipients_allow_ocb) =
        select_recipients(&certificates, policy_time, &mut budget)?;
    if recipients.is_empty() {
        return Err(OpenPgpWriteError::MissingKey);
    }

    let signing_input = request.signing_private_key.take().map(Zeroizing::new);
    let signing_key = signing_input
        .as_deref()
        .map(|input| parse_secret_key(input.as_slice()))
        .transpose()?;
    let signer = signing_key
        .as_ref()
        .map(|secret| {
            select_signing_packet(secret, &request.preferred_signing_fingerprint, policy_time)
        })
        .transpose()?;
    let literal_time = request
        .literal_time_epoch_seconds
        .map_or_else(Timestamp::now, |value| Timestamp::from_secs(value as u32));
    let plaintext = Zeroizing::new(std::mem::take(&mut request.content));
    let composed = build_composed_message(
        plaintext.as_slice(),
        request.file_name.as_bytes(),
        literal_time,
        signer,
        request.enable_compression.unwrap_or(true),
    )?;

    let mode = if all_recipients_allow_ocb {
        OpenPgpProtectionMode::GnupgOcb
    } else {
        OpenPgpProtectionMode::SeipdV1Mdc
    };
    let mut encrypted = encrypt_composed_message(&composed, &recipients, mode)?;
    if request.armored {
        encrypted = armor_message(&encrypted)?;
    }
    Ok(OpenPgpEncryptResult {
        data: encrypted,
        protection_mode: mode as i32,
    }
    .encode_to_vec())
}

fn first_legacy_public_version(documents: &[Vec<u8>]) -> Result<Option<u8>, OpenPgpWriteError> {
    let mut parsed_count = 0_usize;
    for document in documents {
        let (iterator, _) = PublicOrSecret::from_reader_many(Cursor::new(document.as_slice()))
            .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
        for item in iterator {
            parsed_count = parsed_count
                .checked_add(1)
                .filter(|value| *value <= MAX_OPENPGP_KEYS)
                .ok_or(OpenPgpWriteError::ResourceLimit)?;
            let certificate = match item.map_err(|_| OpenPgpWriteError::InvalidArgument)? {
                PublicOrSecret::Public(certificate) => certificate,
                PublicOrSecret::Secret(secret) => secret.to_public_key(),
            };
            if let Some(version) = legacy_public_version(&certificate) {
                return Ok(Some(version));
            }
        }
    }
    Ok(None)
}

fn select_recipients(
    certificates: &[SignedPublicKey],
    reference_time: u64,
    budget: &mut OpenPgpReadBudget,
) -> Result<(Vec<PublicComponent>, bool), OpenPgpWriteError> {
    let candidates = all_components(certificates);
    let mut recipients = Vec::new();
    let mut fingerprints = HashSet::new();
    let mut all_allow_ocb = true;
    for certificate in certificates {
        let policy = inspect_certificate(certificate, &candidates, reference_time, budget)
            .map_err(map_read_error)?;
        if !policy.primary.authenticated
            || policy.primary.revoked
            || component_is_expired(&policy.primary, reference_time)
        {
            continue;
        }
        let selected_subkey = policy
            .subkeys
            .iter()
            .enumerate()
            .filter(|(_, component)| encryption_component_usable(component, reference_time))
            .max_by_key(|(_, component)| {
                (
                    component.key.created_at().as_secs(),
                    fingerprint_hex(component.key),
                )
            })
            .map(|(index, _)| index);
        let selected = if let Some(index) = selected_subkey {
            PublicComponent::Subkey(certificate.public_subkeys[index].key.clone())
        } else if encryption_component_usable(&policy.primary, reference_time) {
            PublicComponent::Primary(certificate.primary_key.clone())
        } else {
            continue;
        };
        let fingerprint = fingerprint_hex(&selected);
        if fingerprints.insert(fingerprint) {
            all_allow_ocb &= recipient_allows_gnupg_ocb(&policy);
            recipients.push(selected);
        }
    }
    Ok((recipients, all_allow_ocb))
}

fn recipient_allows_gnupg_ocb(policy: &CertificatePolicy<'_>) -> bool {
    if !policy.primary.authenticated || policy.primary.revoked {
        return false;
    }
    let Some(signature) = policy.primary.effective_signature else {
        return false;
    };
    let features = signature
        .features()
        .map(Vec::<u8>::from)
        .unwrap_or_default();
    signature
        .preferred_symmetric_algs()
        .contains(&SymmetricKeyAlgorithm::AES256)
        && signature
            .preferred_aead_algs()
            .contains(&(SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb))
        && features.first().is_some_and(|byte| byte & 0x02 != 0)
}

fn build_composed_message(
    content: &[u8],
    file_name: &[u8],
    literal_time: Timestamp,
    signer: Option<SecretPacketRef<'_>>,
    enable_compression: bool,
) -> Result<Zeroizing<Vec<u8>>, OpenPgpWriteError> {
    let mut inner = Zeroizing::new(Vec::new());
    let inline_signature = signer
        .map(|packet| create_inline_signature(packet, content, literal_time))
        .transpose()?;
    if let Some((one_pass, _)) = &inline_signature {
        one_pass
            .to_writer_with_header(&mut *inner)
            .map_err(|_| OpenPgpWriteError::Internal)?;
    }
    write_literal_packet(&mut inner, content, file_name, literal_time)?;
    if let Some((_, signature)) = inline_signature {
        signature
            .to_writer_with_header(&mut *inner)
            .map_err(|_| OpenPgpWriteError::Internal)?;
    }
    if !enable_compression {
        return Ok(inner);
    }

    let mut encoder = DeflateEncoder::new(SecretVec::default(), Compression::default());
    encoder
        .write_all(inner.as_slice())
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let compressed = encoder
        .finish()
        .map_err(|_| OpenPgpWriteError::Internal)?
        .into_zeroizing();
    let body_len = compressed
        .len()
        .checked_add(1)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    let mut output = Zeroizing::new(Vec::with_capacity(compressed.len() + 8));
    PacketHeader::new_fixed(Tag::CompressedData, body_len)
        .to_writer(&mut *output)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    output.push(u8::from(CompressionAlgorithm::ZIP));
    output.extend_from_slice(&compressed);
    Ok(output)
}

fn write_literal_packet(
    output: &mut Vec<u8>,
    content: &[u8],
    file_name: &[u8],
    literal_time: Timestamp,
) -> Result<(), OpenPgpWriteError> {
    let file_name_len =
        u8::try_from(file_name.len()).map_err(|_| OpenPgpWriteError::InvalidArgument)?;
    let body_len = 1_usize
        .checked_add(1)
        .and_then(|value| value.checked_add(file_name.len()))
        .and_then(|value| value.checked_add(4))
        .and_then(|value| value.checked_add(content.len()))
        .and_then(|value| u32::try_from(value).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    PacketHeader::new_fixed(Tag::LiteralData, body_len)
        .to_writer(output)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    output.push(b'b');
    output.push(file_name_len);
    output.extend_from_slice(file_name);
    output.extend_from_slice(&literal_time.as_secs().to_be_bytes());
    output.extend_from_slice(content);
    Ok(())
}

fn create_inline_signature(
    packet: SecretPacketRef<'_>,
    content: &[u8],
    signature_time: Timestamp,
) -> Result<(OnePassSignature, pgp::packet::Signature), OpenPgpWriteError> {
    if is_rsa_private_algorithm(packet.algorithm()) {
        let adapter = AwsLcRsaSecretKey::new(packet)?;
        create_inline_signature_with_key(&adapter, content, signature_time)
    } else {
        match packet {
            SecretPacketRef::Primary(key) => {
                create_inline_signature_with_key(key, content, signature_time)
            }
            SecretPacketRef::Subkey(key) => {
                create_inline_signature_with_key(key, content, signature_time)
            }
        }
    }
}

fn create_inline_signature_with_key<K>(
    key: &K,
    content: &[u8],
    signature_time: Timestamp,
) -> Result<(OnePassSignature, pgp::packet::Signature), OpenPgpWriteError>
where
    K: SigningKey,
{
    let mut config = data_signature_config(key, SignatureType::Binary)?;
    let SubpacketConfig::UserDefined { hashed, unhashed } =
        signing_subpackets(key, signature_time)?
    else {
        return Err(OpenPgpWriteError::Internal);
    };
    config.hashed_subpackets = hashed;
    config.unhashed_subpackets = unhashed;
    let one_pass = match &config.version_specific {
        SignatureVersionSpecific::V4 => OnePassSignature::v3(
            SignatureType::Binary,
            HashAlgorithm::Sha256,
            key.algorithm(),
            key.legacy_key_id(),
        ),
        SignatureVersionSpecific::V6 { salt } => {
            let Fingerprint::V6(fingerprint) = key.fingerprint() else {
                return Err(OpenPgpWriteError::InvalidArgument);
            };
            OnePassSignature::v6(
                SignatureType::Binary,
                HashAlgorithm::Sha256,
                key.algorithm(),
                salt.clone(),
                fingerprint,
            )
        }
        _ => return Err(OpenPgpWriteError::InvalidArgument),
    };
    let signature = config
        .sign(key, &Password::empty(), Cursor::new(content))
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    Ok((one_pass, signature))
}

fn encrypt_composed_message(
    plaintext: &[u8],
    recipients: &[PublicComponent],
    mode: OpenPgpProtectionMode,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    let mut rng = AwsLcRng;
    let session_key = SymmetricKeyAlgorithm::AES256.new_session_key(rng);
    let mut output = Vec::new();
    for recipient in recipients {
        PublicKeyEncryptedSessionKey::from_session_key_v3(
            &mut rng,
            &session_key,
            SymmetricKeyAlgorithm::AES256,
            recipient,
        )
        .and_then(|packet| packet.to_writer_with_header(&mut output))
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    }
    match mode {
        OpenPgpProtectionMode::SeipdV1Mdc => {
            write_seipd_v1(&mut output, plaintext, &session_key, &mut rng)?;
        }
        OpenPgpProtectionMode::GnupgOcb => {
            write_gnupg_ocb(&mut output, plaintext, &session_key, &mut rng)?;
        }
        OpenPgpProtectionMode::Unspecified => {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
    }
    Ok(output)
}

fn write_seipd_v1(
    output: &mut Vec<u8>,
    plaintext: &[u8],
    session_key: &RawSessionKey,
    rng: &mut AwsLcRng,
) -> Result<(), OpenPgpWriteError> {
    let encrypted_len = SymmetricKeyAlgorithm::AES256
        .encrypted_protected_len(plaintext.len())
        .checked_add(1)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    PacketHeader::new_fixed(Tag::SymEncryptedProtectedData, encrypted_len)
        .to_writer(output)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    output.push(1);
    SymmetricKeyAlgorithm::AES256
        .stream_encryptor(rng, session_key.as_ref(), Cursor::new(plaintext))
        .and_then(|mut encryptor| encryptor.read_to_end(output).map_err(Into::into))
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    Ok(())
}

fn write_gnupg_ocb(
    output: &mut Vec<u8>,
    plaintext: &[u8],
    session_key: &RawSessionKey,
    rng: &mut AwsLcRng,
) -> Result<(), OpenPgpWriteError> {
    let chunks = plaintext.len().div_ceil(GNUPG_AEAD_CHUNK_BYTES);
    let body_len = 4_usize
        .checked_add(15)
        .and_then(|value| value.checked_add(plaintext.len()))
        .and_then(|value| value.checked_add(chunks.checked_mul(AEAD_TAG_BYTES)?))
        .and_then(|value| value.checked_add(AEAD_TAG_BYTES))
        .and_then(|value| u32::try_from(value).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    PacketHeader::new_fixed(Tag::GnupgAeadData, body_len)
        .to_writer(output)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    output.extend_from_slice(&[
        1,
        u8::from(SymmetricKeyAlgorithm::AES256),
        u8::from(AeadAlgorithm::Ocb),
        GNUPG_AEAD_CHUNK_OCTET,
    ]);
    let mut iv = [0_u8; 15];
    rng.try_fill_bytes(&mut iv)
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    output.extend_from_slice(&iv);

    // The patched OCB implementation erases both AES-256's expanded key and
    // all OCB L-table state on Drop. Keep this explicit type shape auditable.
    let cipher = Aes256Ocb::new_from_slice(session_key.as_ref())
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let mut index = 0_u64;
    let mut written = 0_u64;
    for chunk in plaintext.chunks(GNUPG_AEAD_CHUNK_BYTES) {
        let nonce = gnupg_ocb_nonce(&iv, index);
        let associated_data = gnupg_ocb_associated_data(index);
        let mut encrypted = chunk.to_vec();
        let tag = cipher
            .encrypt_in_place_detached(
                Nonce::<U15>::from_slice(&nonce),
                &associated_data,
                &mut encrypted,
            )
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        output.extend_from_slice(&encrypted);
        output.extend_from_slice(&tag);
        encrypted.zeroize();
        written = written
            .checked_add(chunk.len() as u64)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        index = index
            .checked_add(1)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
    }
    let nonce = gnupg_ocb_nonce(&iv, index);
    let mut final_associated_data = gnupg_ocb_associated_data(index).to_vec();
    final_associated_data.extend_from_slice(&written.to_be_bytes());
    let mut empty = Vec::new();
    let final_tag = cipher
        .encrypt_in_place_detached(
            Nonce::<U15>::from_slice(&nonce),
            &final_associated_data,
            &mut empty,
        )
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    output.extend_from_slice(&final_tag);
    final_associated_data.zeroize();
    iv.zeroize();
    Ok(())
}

fn gnupg_ocb_nonce(iv: &[u8; 15], chunk_index: u64) -> [u8; 15] {
    let mut nonce = *iv;
    for (nonce_byte, index_byte) in nonce[7..].iter_mut().zip(chunk_index.to_be_bytes()) {
        *nonce_byte ^= index_byte;
    }
    nonce
}

fn gnupg_ocb_associated_data(chunk_index: u64) -> [u8; 13] {
    let mut data = [
        Tag::GnupgAeadData.encode(),
        1,
        u8::from(SymmetricKeyAlgorithm::AES256),
        u8::from(AeadAlgorithm::Ocb),
        GNUPG_AEAD_CHUNK_OCTET,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
    ];
    data[5..].copy_from_slice(&chunk_index.to_be_bytes());
    data
}

struct RawPackets<'a>(&'a [u8]);

impl Serialize for RawPackets<'_> {
    fn to_writer<W: Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        writer.write_all(self.0)?;
        Ok(())
    }

    fn write_len(&self) -> usize {
        self.0.len()
    }
}

fn armor_message(input: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
    let options = ArmorOptions::default();
    let mut output = Vec::new();
    armor::write(
        &RawPackets(input),
        BlockType::Message,
        &mut output,
        options.headers,
        options.include_checksum,
    )
    .map_err(|_| OpenPgpWriteError::Internal)?;
    Ok(output)
}

enum OpenPgpWorkerInput {
    Data { bytes: Zeroizing<Vec<u8>> },
    Finish,
}

enum OpenPgpWorkerOutput {
    Data(Zeroizing<Vec<u8>>),
    Consumed,
    Finished(Result<OpenPgpWorkerFinal, OpenPgpWriteError>),
}

enum OpenPgpWorkerFinal {
    Encrypt(OpenPgpProtectionMode),
    Decrypt(Box<OpenPgpDecryptWorkerFinal>),
}

struct OpenPgpDecryptWorkerFinal {
    verification: Option<OpenPgpVerification>,
    metadata: Option<OpenPgpLiteralMetadata>,
    decryption_key_fingerprint: Option<Fingerprint>,
    declared_charset: Option<String>,
}

struct OpenPgpWorkerPermit;

impl OpenPgpWorkerPermit {
    fn acquire() -> Result<Self, OpenPgpWriteError> {
        OPENPGP_STREAM_WORKERS
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |active| {
                (active < MAX_OPENPGP_STREAM_WORKERS).then_some(active + 1)
            })
            .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
        Ok(Self)
    }
}

impl Drop for OpenPgpWorkerPermit {
    fn drop(&mut self) {
        let previous = OPENPGP_STREAM_WORKERS.fetch_sub(1, Ordering::AcqRel);
        debug_assert!(previous > 0);
    }
}

struct OpenPgpWorkerPipe {
    input: Option<SyncSender<OpenPgpWorkerInput>>,
    output: Option<Receiver<OpenPgpWorkerOutput>>,
    join: Option<JoinHandle<()>>,
    finished: Option<Result<OpenPgpWorkerFinal, OpenPgpWriteError>>,
}

impl OpenPgpWorkerPipe {
    fn spawn(
        name: &'static str,
        worker: impl FnOnce(
            OpenPgpChannelReader,
            SyncSender<OpenPgpWorkerOutput>,
        ) -> Result<OpenPgpWorkerFinal, OpenPgpWriteError>
        + Send
        + 'static,
    ) -> Result<Self, OpenPgpWriteError> {
        let permit = OpenPgpWorkerPermit::acquire()?;
        let (input_tx, input_rx) = mpsc::sync_channel(1);
        let (output_tx, output_rx) = mpsc::sync_channel(STREAM_CHANNEL_DEPTH);
        let join = thread::Builder::new()
            .name(name.to_owned())
            .spawn(move || {
                let result = catch_unwind(AssertUnwindSafe(|| {
                    worker(
                        OpenPgpChannelReader::new(input_rx, output_tx.clone()),
                        output_tx.clone(),
                    )
                }))
                .unwrap_or(Err(OpenPgpWriteError::Panic));
                let _ = output_tx.send(OpenPgpWorkerOutput::Finished(result));
                drop(permit);
            })
            .map_err(|_| OpenPgpWriteError::Internal)?;
        Ok(Self {
            input: Some(input_tx),
            output: Some(output_rx),
            join: Some(join),
            finished: None,
        })
    }

    fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
        if data.len() > OPENPGP_PARTIAL_PACKET_BYTES {
            return Err(OpenPgpWriteError::ResourceLimit);
        }
        if let Some(result) = &self.finished {
            return Err(result
                .as_ref()
                .err()
                .copied()
                .unwrap_or(OpenPgpWriteError::InvalidArgument));
        }
        let input = self.input.as_ref().ok_or(OpenPgpWriteError::Internal)?;
        input
            .send(OpenPgpWorkerInput::Data {
                bytes: Zeroizing::new(data.to_vec()),
            })
            .map_err(|_| OpenPgpWriteError::Internal)?;
        let mut output = Zeroizing::new(Vec::new());
        loop {
            let message = self
                .output
                .as_ref()
                .ok_or(OpenPgpWriteError::Internal)?
                .recv()
                .map_err(|_| OpenPgpWriteError::Internal)?;
            match message {
                OpenPgpWorkerOutput::Consumed => break,
                message => self.accept_output(message, &mut output)?,
            }
            if let Some(result) = &self.finished {
                return Err(result
                    .as_ref()
                    .err()
                    .copied()
                    .unwrap_or(OpenPgpWriteError::Internal));
            }
        }
        self.collect_available(&mut output)?;
        if let Some(result) = &self.finished {
            return Err(result
                .as_ref()
                .err()
                .copied()
                .unwrap_or(OpenPgpWriteError::Internal));
        }
        Ok(output.to_vec())
    }

    fn finish(mut self) -> Result<(Vec<u8>, OpenPgpWorkerFinal), OpenPgpWriteError> {
        if let Some(input) = self.input.take() {
            if self.finished.is_none() {
                // A failed send means the worker already terminated. Its stable
                // result still arrives on the independent output channel below.
                let _ = input.send(OpenPgpWorkerInput::Finish);
            }
            drop(input);
        }
        let mut output = Zeroizing::new(Vec::new());
        if self.finished.is_none() {
            self.collect_until_finished(&mut output)?;
        } else {
            self.collect_available(&mut output)?;
        }
        self.output.take();
        self.join_worker()?;
        let final_result = self.finished.take().ok_or(OpenPgpWriteError::Internal)??;
        Ok((output.to_vec(), final_result))
    }

    fn collect_available(&mut self, destination: &mut Vec<u8>) -> Result<(), OpenPgpWriteError> {
        loop {
            let result = self
                .output
                .as_ref()
                .ok_or(OpenPgpWriteError::Internal)?
                .try_recv();
            match result {
                Ok(message) => self.accept_output(message, destination)?,
                Err(TryRecvError::Empty) => return Ok(()),
                Err(TryRecvError::Disconnected) => {
                    return self
                        .finished
                        .is_some()
                        .then_some(())
                        .ok_or(OpenPgpWriteError::Internal);
                }
            }
        }
    }

    fn collect_until_finished(
        &mut self,
        destination: &mut Vec<u8>,
    ) -> Result<(), OpenPgpWriteError> {
        while self.finished.is_none() {
            let message = self
                .output
                .as_ref()
                .ok_or(OpenPgpWriteError::Internal)?
                .recv()
                .map_err(|_| OpenPgpWriteError::Internal)?;
            self.accept_output(message, destination)?;
        }
        Ok(())
    }

    fn accept_output(
        &mut self,
        message: OpenPgpWorkerOutput,
        destination: &mut Vec<u8>,
    ) -> Result<(), OpenPgpWriteError> {
        match message {
            OpenPgpWorkerOutput::Data(bytes) => {
                let new_len = destination
                    .len()
                    .checked_add(bytes.len())
                    .filter(|value| *value <= MAX_CONTROL_ENVELOPE_BYTES)
                    .ok_or(OpenPgpWriteError::ResourceLimit)?;
                destination.reserve(new_len - destination.len());
                destination.extend_from_slice(bytes.as_slice());
            }
            OpenPgpWorkerOutput::Consumed => {}
            OpenPgpWorkerOutput::Finished(result) => {
                if self.finished.replace(result).is_some() {
                    return Err(OpenPgpWriteError::Internal);
                }
            }
        }
        Ok(())
    }

    fn join_worker(&mut self) -> Result<(), OpenPgpWriteError> {
        if let Some(join) = self.join.take() {
            join.join().map_err(|_| OpenPgpWriteError::Internal)?;
        }
        Ok(())
    }
}

impl Drop for OpenPgpWorkerPipe {
    fn drop(&mut self) {
        self.input.take();
        self.output.take();
        let _ = self.join_worker();
    }
}

struct OpenPgpChannelReader {
    receiver: Receiver<OpenPgpWorkerInput>,
    acknowledgements: SyncSender<OpenPgpWorkerOutput>,
    current: Option<(Zeroizing<Vec<u8>>, usize)>,
    finished: bool,
}

impl std::fmt::Debug for OpenPgpChannelReader {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OpenPgpChannelReader")
            .finish_non_exhaustive()
    }
}

impl OpenPgpChannelReader {
    fn new(
        receiver: Receiver<OpenPgpWorkerInput>,
        acknowledgements: SyncSender<OpenPgpWorkerOutput>,
    ) -> Self {
        Self {
            receiver,
            acknowledgements,
            current: None,
            finished: false,
        }
    }

    fn acknowledge_consumed(&mut self) {
        if self.current.take().is_some() {
            let _ = self.acknowledgements.send(OpenPgpWorkerOutput::Consumed);
        }
    }
}

impl Read for OpenPgpChannelReader {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        loop {
            if let Some((bytes, offset)) = &mut self.current {
                if *offset < bytes.len() {
                    let count = destination.len().min(bytes.len() - *offset);
                    destination[..count].copy_from_slice(&bytes[*offset..*offset + count]);
                    *offset += count;
                    if *offset == bytes.len() {
                        self.acknowledge_consumed();
                    }
                    return Ok(count);
                }
                self.acknowledge_consumed();
            }
            if self.finished {
                return Ok(0);
            }
            match self.receiver.recv() {
                Ok(OpenPgpWorkerInput::Data { bytes }) => {
                    self.current = Some((bytes, 0));
                }
                Ok(OpenPgpWorkerInput::Finish) | Err(_) => self.finished = true,
            }
        }
    }
}

#[derive(Debug)]
struct OpenPgpPreludeLimitedReader<R> {
    inner: R,
    active: Arc<AtomicBool>,
    exceeded: Arc<AtomicBool>,
    bytes_read: usize,
}

impl<R> OpenPgpPreludeLimitedReader<R> {
    fn new(inner: R, active: Arc<AtomicBool>, exceeded: Arc<AtomicBool>) -> Self {
        Self {
            inner,
            active,
            exceeded,
            bytes_read: 0,
        }
    }
}

impl<R: Read> Read for OpenPgpPreludeLimitedReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() || !self.active.load(Ordering::Acquire) {
            return self.inner.read(destination);
        }
        let remaining = MAX_CONTROL_ENVELOPE_BYTES.saturating_sub(self.bytes_read);
        if remaining == 0 {
            self.exceeded.store(true, Ordering::Release);
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "OpenPGP prelude resource limit exceeded",
            ));
        }
        let read_limit = destination.len().min(remaining);
        let read = self.inner.read(&mut destination[..read_limit])?;
        self.bytes_read += read;
        Ok(read)
    }
}

fn parse_streaming_message<'a, R>(
    input: R,
) -> Result<(Message<'a>, Option<Headers>), OpenPgpWriteError>
where
    R: Read + std::fmt::Debug + Send + 'a,
{
    let active = Arc::new(AtomicBool::new(true));
    let exceeded = Arc::new(AtomicBool::new(false));
    let reader = OpenPgpPreludeLimitedReader::new(input, active.clone(), exceeded.clone());
    let parsed = Message::from_reader(BufReader::new(reader));
    active.store(false, Ordering::Release);
    parsed.map_err(|_| {
        if exceeded.load(Ordering::Acquire) {
            OpenPgpWriteError::ResourceLimit
        } else {
            OpenPgpWriteError::InvalidArgument
        }
    })
}

fn declared_armor_charset(headers: Option<&Headers>) -> Option<String> {
    let mut values = headers?
        .iter()
        .filter(|(name, _)| name.eq_ignore_ascii_case("Charset"))
        .flat_map(|(_, values)| values.iter());
    let value = values.next()?;
    if values.next().is_some() {
        return None;
    }
    let value = value.trim();
    (!value.is_empty()).then(|| value.to_owned())
}

struct OpenPgpChannelWriter {
    sender: SyncSender<OpenPgpWorkerOutput>,
    pending: Zeroizing<Vec<u8>>,
}

impl OpenPgpChannelWriter {
    fn new(sender: SyncSender<OpenPgpWorkerOutput>) -> Self {
        Self {
            sender,
            pending: Zeroizing::new(Vec::with_capacity(OPENPGP_PARTIAL_PACKET_BYTES)),
        }
    }

    fn send_pending(&mut self) -> std::io::Result<()> {
        if self.pending.is_empty() {
            return Ok(());
        }
        let bytes = Zeroizing::new(std::mem::take(&mut *self.pending));
        self.sender
            .send(OpenPgpWorkerOutput::Data(bytes))
            .map_err(|_| std::io::Error::new(std::io::ErrorKind::BrokenPipe, "stream closed"))?;
        self.pending = Zeroizing::new(Vec::with_capacity(OPENPGP_PARTIAL_PACKET_BYTES));
        Ok(())
    }

    fn finish(mut self) -> std::io::Result<()> {
        self.send_pending()
    }
}

impl Write for OpenPgpChannelWriter {
    fn write(&mut self, mut source: &[u8]) -> std::io::Result<usize> {
        let original_len = source.len();
        while !source.is_empty() {
            let available = OPENPGP_PARTIAL_PACKET_BYTES - self.pending.len();
            let count = available.min(source.len());
            self.pending.extend_from_slice(&source[..count]);
            source = &source[count..];
            if self.pending.len() == OPENPGP_PARTIAL_PACKET_BYTES {
                self.send_pending()?;
            }
        }
        Ok(original_len)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

struct PartialPacketReader<R> {
    tag: Tag,
    inner: R,
    emitted_tag: bool,
    finished: bool,
    output: Zeroizing<Vec<u8>>,
    output_offset: usize,
}

impl<R> PartialPacketReader<R> {
    fn new(tag: Tag, inner: R) -> Self {
        Self {
            tag,
            inner,
            emitted_tag: false,
            finished: false,
            output: Zeroizing::new(Vec::new()),
            output_offset: 0,
        }
    }

    fn into_inner(self) -> R {
        self.inner
    }
}

impl<R: Read> PartialPacketReader<R> {
    fn refill(&mut self) -> std::io::Result<()> {
        self.output.clear();
        self.output_offset = 0;
        if !self.emitted_tag {
            self.emitted_tag = true;
            self.output.push(self.tag.encode());
            return Ok(());
        }
        if self.finished {
            return Ok(());
        }

        let mut body = Zeroizing::new(vec![0_u8; OPENPGP_PARTIAL_PACKET_BYTES]);
        let mut body_len = 0_usize;
        while body_len < body.len() {
            match self.inner.read(&mut body[body_len..]) {
                Ok(0) => break,
                Ok(read) => body_len += read,
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
        if body_len == OPENPGP_PARTIAL_PACKET_BYTES {
            self.output.push(OPENPGP_PARTIAL_PACKET_OCTET);
        } else {
            write_new_packet_length(&mut self.output, body_len)?;
            self.finished = true;
        }
        self.output.extend_from_slice(&body[..body_len]);
        Ok(())
    }
}

impl<R: Read> Read for PartialPacketReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        while self.output_offset == self.output.len() && !self.finished {
            self.refill()?;
        }
        if self.output_offset == self.output.len() {
            return Ok(0);
        }
        let count = destination
            .len()
            .min(self.output.len() - self.output_offset);
        destination[..count]
            .copy_from_slice(&self.output[self.output_offset..self.output_offset + count]);
        self.output_offset += count;
        Ok(count)
    }
}

fn write_new_packet_length(output: &mut Vec<u8>, length: usize) -> std::io::Result<()> {
    if length < 192 {
        output.push(length as u8);
    } else if length <= 8_383 {
        let encoded = length - 192;
        output.push(((encoded >> 8) + 192) as u8);
        output.push(encoded as u8);
    } else {
        let length = u32::try_from(length).map_err(|_| {
            std::io::Error::new(std::io::ErrorKind::InvalidInput, "packet length overflow")
        })?;
        output.push(0xff);
        output.extend_from_slice(&length.to_be_bytes());
    }
    Ok(())
}

struct PrefixedReader<R> {
    prefix: Zeroizing<Vec<u8>>,
    prefix_offset: usize,
    inner: R,
}

impl<R> PrefixedReader<R> {
    fn new(prefix: Vec<u8>, inner: R) -> Self {
        Self {
            prefix: Zeroizing::new(prefix),
            prefix_offset: 0,
            inner,
        }
    }
}

impl<R: Read> Read for PrefixedReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        if self.prefix_offset < self.prefix.len() {
            let count = destination
                .len()
                .min(self.prefix.len() - self.prefix_offset);
            destination[..count]
                .copy_from_slice(&self.prefix[self.prefix_offset..self.prefix_offset + count]);
            self.prefix_offset += count;
            return Ok(count);
        }
        self.inner.read(destination)
    }
}

struct LiteralBodyReader<R> {
    prefix: Zeroizing<Vec<u8>>,
    prefix_offset: usize,
    source: R,
    hasher: Option<SignatureHasher>,
}

impl<R> LiteralBodyReader<R> {
    fn new(
        source: R,
        file_name: &[u8],
        literal_time: Timestamp,
        hasher: Option<SignatureHasher>,
    ) -> Result<Self, OpenPgpWriteError> {
        let file_name_len =
            u8::try_from(file_name.len()).map_err(|_| OpenPgpWriteError::InvalidArgument)?;
        let mut prefix = Zeroizing::new(Vec::with_capacity(file_name.len() + 6));
        prefix.push(b'b');
        prefix.push(file_name_len);
        prefix.extend_from_slice(file_name);
        prefix.extend_from_slice(&literal_time.as_secs().to_be_bytes());
        Ok(Self {
            prefix,
            prefix_offset: 0,
            source,
            hasher,
        })
    }
}

impl<R: Read> Read for LiteralBodyReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        if self.prefix_offset < self.prefix.len() {
            let count = destination
                .len()
                .min(self.prefix.len() - self.prefix_offset);
            destination[..count]
                .copy_from_slice(&self.prefix[self.prefix_offset..self.prefix_offset + count]);
            self.prefix_offset += count;
            return Ok(count);
        }
        let read = self.source.read(destination)?;
        if let Some(hasher) = &mut self.hasher {
            hasher.write_all(&destination[..read])?;
        }
        Ok(read)
    }
}

struct SignedLiteralReader<'a, R> {
    prefix: Zeroizing<Vec<u8>>,
    prefix_offset: usize,
    literal: Option<PartialPacketReader<LiteralBodyReader<R>>>,
    signer: Option<&'a dyn SigningKey>,
    trailer: Zeroizing<Vec<u8>>,
    trailer_offset: usize,
    finished: bool,
}

impl<'a, R: Read> SignedLiteralReader<'a, R> {
    fn new(
        source: R,
        file_name: &[u8],
        literal_time: Timestamp,
        signer: Option<&'a dyn SigningKey>,
    ) -> Result<Self, OpenPgpWriteError> {
        let (prefix, hasher) = signer
            .map(|key| streaming_inline_signature(key, literal_time))
            .transpose()?
            .map_or_else(
                || (Vec::new(), None),
                |(prefix, hasher)| (prefix, Some(hasher)),
            );
        let literal = LiteralBodyReader::new(source, file_name, literal_time, hasher)?;
        Ok(Self {
            prefix: Zeroizing::new(prefix),
            prefix_offset: 0,
            literal: Some(PartialPacketReader::new(Tag::LiteralData, literal)),
            signer,
            trailer: Zeroizing::new(Vec::new()),
            trailer_offset: 0,
            finished: false,
        })
    }

    fn finalize_signature(&mut self) -> std::io::Result<()> {
        let literal = self
            .literal
            .take()
            .ok_or_else(|| std::io::Error::other("literal stream missing"))?
            .into_inner();
        let Some(signer) = self.signer else {
            self.finished = true;
            return Ok(());
        };
        let hasher = literal
            .hasher
            .ok_or_else(|| std::io::Error::other("signature hasher missing"))?;
        let signature = hasher
            .sign(signer, &Password::empty())
            .map_err(|_| std::io::Error::other("OpenPGP signing failed"))?;
        signature
            .to_writer_with_header(&mut *self.trailer)
            .map_err(|_| std::io::Error::other("OpenPGP signature encoding failed"))?;
        Ok(())
    }
}

impl<R: Read> Read for SignedLiteralReader<'_, R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        loop {
            if self.prefix_offset < self.prefix.len() {
                let count = destination
                    .len()
                    .min(self.prefix.len() - self.prefix_offset);
                destination[..count]
                    .copy_from_slice(&self.prefix[self.prefix_offset..self.prefix_offset + count]);
                self.prefix_offset += count;
                return Ok(count);
            }
            if let Some(literal) = &mut self.literal {
                let read = literal.read(destination)?;
                if read > 0 {
                    return Ok(read);
                }
                self.finalize_signature()?;
                continue;
            }
            if self.trailer_offset < self.trailer.len() {
                let count = destination
                    .len()
                    .min(self.trailer.len() - self.trailer_offset);
                destination[..count].copy_from_slice(
                    &self.trailer[self.trailer_offset..self.trailer_offset + count],
                );
                self.trailer_offset += count;
                if self.trailer_offset == self.trailer.len() {
                    self.finished = true;
                }
                return Ok(count);
            }
            self.finished = true;
            return Ok(0);
        }
    }
}

fn streaming_inline_signature(
    key: &dyn SigningKey,
    signature_time: Timestamp,
) -> Result<(Vec<u8>, SignatureHasher), OpenPgpWriteError> {
    let mut config = data_signature_config(key, SignatureType::Binary)?;
    let SubpacketConfig::UserDefined { hashed, unhashed } =
        signing_subpackets(key, signature_time)?
    else {
        return Err(OpenPgpWriteError::Internal);
    };
    config.hashed_subpackets = hashed;
    config.unhashed_subpackets = unhashed;
    let one_pass = match &config.version_specific {
        SignatureVersionSpecific::V4 => OnePassSignature::v3(
            SignatureType::Binary,
            HashAlgorithm::Sha256,
            key.algorithm(),
            key.legacy_key_id(),
        ),
        SignatureVersionSpecific::V6 { salt } => {
            let Fingerprint::V6(fingerprint) = key.fingerprint() else {
                return Err(OpenPgpWriteError::InvalidArgument);
            };
            OnePassSignature::v6(
                SignatureType::Binary,
                HashAlgorithm::Sha256,
                key.algorithm(),
                salt.clone(),
                fingerprint,
            )
        }
        _ => return Err(OpenPgpWriteError::InvalidArgument),
    };
    let mut prefix = Vec::new();
    one_pass
        .to_writer_with_header(&mut prefix)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let hasher = config
        .into_hasher()
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    Ok((prefix, hasher))
}

struct GnuPgpOcbEncryptReader<R> {
    source: R,
    cipher: Aes256Ocb,
    iv: Zeroizing<[u8; 15]>,
    chunk_index: u64,
    plaintext_bytes: u64,
    output: Zeroizing<Vec<u8>>,
    output_offset: usize,
    final_emitted: bool,
}

impl<R> GnuPgpOcbEncryptReader<R> {
    fn new(
        source: R,
        session_key: &RawSessionKey,
        rng: &mut AwsLcRng,
    ) -> Result<Self, OpenPgpWriteError> {
        let mut iv = Zeroizing::new([0_u8; 15]);
        rng.try_fill_bytes(&mut *iv)
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        let cipher = Aes256Ocb::new_from_slice(session_key.as_ref())
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        let mut output = Zeroizing::new(Vec::with_capacity(19));
        output.extend_from_slice(&[
            1,
            u8::from(SymmetricKeyAlgorithm::AES256),
            u8::from(AeadAlgorithm::Ocb),
            GNUPG_AEAD_CHUNK_OCTET,
        ]);
        output.extend_from_slice(iv.as_slice());
        Ok(Self {
            source,
            cipher,
            iv,
            chunk_index: 0,
            plaintext_bytes: 0,
            output,
            output_offset: 0,
            final_emitted: false,
        })
    }
}

impl<R: Read> GnuPgpOcbEncryptReader<R> {
    fn refill(&mut self) -> std::io::Result<()> {
        self.output.clear();
        self.output_offset = 0;
        if self.final_emitted {
            return Ok(());
        }
        let mut plaintext = Zeroizing::new(vec![0_u8; GNUPG_AEAD_CHUNK_BYTES]);
        let mut length = 0_usize;
        while length < plaintext.len() {
            match self.source.read(&mut plaintext[length..]) {
                Ok(0) => break,
                Ok(read) => length += read,
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
        if length > 0 {
            plaintext.truncate(length);
            let nonce = gnupg_ocb_nonce(&self.iv, self.chunk_index);
            let associated_data = gnupg_ocb_associated_data(self.chunk_index);
            let tag = self
                .cipher
                .encrypt_in_place_detached(
                    Nonce::<U15>::from_slice(&nonce),
                    &associated_data,
                    &mut plaintext,
                )
                .map_err(|_| std::io::Error::other("OpenPGP OCB encryption failed"))?;
            self.output.extend_from_slice(&plaintext);
            self.output.extend_from_slice(&tag);
            self.plaintext_bytes = self
                .plaintext_bytes
                .checked_add(
                    u64::try_from(length).map_err(|_| {
                        std::io::Error::other("OpenPGP OCB plaintext length overflow")
                    })?,
                )
                .ok_or_else(|| std::io::Error::other("OpenPGP OCB plaintext length overflow"))?;
            self.chunk_index = self
                .chunk_index
                .checked_add(1)
                .ok_or_else(|| std::io::Error::other("OpenPGP OCB chunk overflow"))?;
            return Ok(());
        }

        let nonce = gnupg_ocb_nonce(&self.iv, self.chunk_index);
        let mut associated_data =
            Zeroizing::new(gnupg_ocb_associated_data(self.chunk_index).to_vec());
        associated_data.extend_from_slice(&self.plaintext_bytes.to_be_bytes());
        let mut empty = Vec::new();
        let tag = self
            .cipher
            .encrypt_in_place_detached(
                Nonce::<U15>::from_slice(&nonce),
                &associated_data,
                &mut empty,
            )
            .map_err(|_| std::io::Error::other("OpenPGP OCB finalization failed"))?;
        self.output.extend_from_slice(&tag);
        self.final_emitted = true;
        Ok(())
    }
}

impl<R: Read> Read for GnuPgpOcbEncryptReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        while self.output_offset == self.output.len() && !self.final_emitted {
            self.refill()?;
        }
        if self.output_offset == self.output.len() {
            return Ok(0);
        }
        let count = destination
            .len()
            .min(self.output.len() - self.output_offset);
        destination[..count]
            .copy_from_slice(&self.output[self.output_offset..self.output_offset + count]);
        self.output_offset += count;
        Ok(count)
    }
}

enum OpenPgpMessageWriter {
    Binary(OpenPgpChannelWriter),
    Armored(OpenPgpArmorWriter),
}

impl OpenPgpMessageWriter {
    fn new(
        sender: SyncSender<OpenPgpWorkerOutput>,
        armored: bool,
    ) -> Result<Self, OpenPgpWriteError> {
        let writer = OpenPgpChannelWriter::new(sender);
        if armored {
            OpenPgpArmorWriter::new(writer)
                .map(Self::Armored)
                .map_err(|_| OpenPgpWriteError::Internal)
        } else {
            Ok(Self::Binary(writer))
        }
    }

    fn finish(self) -> Result<(), OpenPgpWriteError> {
        match self {
            Self::Binary(writer) => writer.finish(),
            Self::Armored(writer) => writer.finish(),
        }
        .map_err(|_| OpenPgpWriteError::Internal)
    }
}

impl Write for OpenPgpMessageWriter {
    fn write(&mut self, source: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::Binary(writer) => writer.write(source),
            Self::Armored(writer) => writer.write(source),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

struct OpenPgpArmorWriter {
    inner: OpenPgpChannelWriter,
    carry: Zeroizing<[u8; 3]>,
    carry_len: usize,
    line_length: usize,
    crc: u32,
}

impl OpenPgpArmorWriter {
    fn new(mut inner: OpenPgpChannelWriter) -> std::io::Result<Self> {
        inner.write_all(b"-----BEGIN PGP MESSAGE-----\n\n")?;
        Ok(Self {
            inner,
            carry: Zeroizing::new([0_u8; 3]),
            carry_len: 0,
            line_length: 0,
            crc: 0x00b7_04ce,
        })
    }

    fn write_quartet(&mut self, quartet: [u8; 4]) -> std::io::Result<()> {
        self.inner.write_all(&quartet)?;
        self.line_length += quartet.len();
        if self.line_length == 64 {
            self.inner.write_all(b"\n")?;
            self.line_length = 0;
        }
        Ok(())
    }

    fn finish(mut self) -> std::io::Result<()> {
        if self.carry_len > 0 {
            let quartet = encode_base64_triplet(&self.carry, self.carry_len);
            self.write_quartet(quartet)?;
            self.carry.zeroize();
            self.carry_len = 0;
        }
        if self.line_length != 0 {
            self.inner.write_all(b"\n")?;
            self.line_length = 0;
        }
        let crc = self.crc & 0x00ff_ffff;
        let crc_bytes = [
            ((crc >> 16) & 0xff) as u8,
            ((crc >> 8) & 0xff) as u8,
            (crc & 0xff) as u8,
        ];
        self.inner.write_all(b"=")?;
        self.inner
            .write_all(&encode_base64_triplet(&crc_bytes, crc_bytes.len()))?;
        self.inner.write_all(b"\n-----END PGP MESSAGE-----\n")?;
        self.inner.finish()
    }
}

impl Write for OpenPgpArmorWriter {
    fn write(&mut self, source: &[u8]) -> std::io::Result<usize> {
        for byte in source {
            self.crc = crc24_update(self.crc, *byte);
            self.carry[self.carry_len] = *byte;
            self.carry_len += 1;
            if self.carry_len == self.carry.len() {
                let quartet = encode_base64_triplet(&self.carry, self.carry.len());
                self.write_quartet(quartet)?;
                self.carry.zeroize();
                self.carry_len = 0;
            }
        }
        Ok(source.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn encode_base64_triplet(input: &[u8; 3], length: usize) -> [u8; 4] {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let value = (u32::from(input[0]) << 16) | (u32::from(input[1]) << 8) | u32::from(input[2]);
    [
        ALPHABET[((value >> 18) & 0x3f) as usize],
        ALPHABET[((value >> 12) & 0x3f) as usize],
        if length > 1 {
            ALPHABET[((value >> 6) & 0x3f) as usize]
        } else {
            b'='
        },
        if length > 2 {
            ALPHABET[(value & 0x3f) as usize]
        } else {
            b'='
        },
    ]
}

fn crc24_update(mut crc: u32, byte: u8) -> u32 {
    crc ^= u32::from(byte) << 16;
    for _ in 0..8 {
        crc <<= 1;
        if crc & 0x0100_0000 != 0 {
            crc ^= 0x0186_4cfb;
        }
    }
    crc & 0x00ff_ffff
}

struct OpenPgpEncryptWorkerConfig {
    recipients: Vec<PublicComponent>,
    signing: Option<(SignedSecretKey, SecretPacketSelection)>,
    file_name: Vec<u8>,
    literal_time: Timestamp,
    armored: bool,
    enable_compression: bool,
    mode: OpenPgpProtectionMode,
}

/// Incremental OpenPGP encryption. The worker owns all parser/compressor
/// state, and each channel is bounded independently of the file size.
pub(crate) struct OpenPgpEncryptionSession {
    worker: OpenPgpWorkerPipe,
}

impl OpenPgpEncryptionSession {
    pub(crate) fn open(
        mut request: OpenPgpEncryptStreamOpenRequest,
    ) -> Result<Self, OpenPgpWriteError> {
        if request.public_keys.is_empty()
            || request.public_keys.len() > MAX_OPENPGP_KEYS
            || request.file_name.is_empty()
            || request.file_name.len() > MAX_FILE_NAME_BYTES
            || request.file_name.len() > usize::from(u8::MAX)
            || (request.signing_private_key.is_none()
                && !request.preferred_signing_fingerprint.is_empty())
            || request
                .literal_time_epoch_seconds
                .is_some_and(|value| value > u64::from(u32::MAX))
        {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        if request
            .public_keys
            .iter()
            .any(|document| document.len() > MAX_CONTROL_ENVELOPE_BYTES)
            || request
                .signing_private_key
                .as_ref()
                .is_some_and(|key| key.len() > MAX_CONTROL_ENVELOPE_BYTES)
        {
            return Err(OpenPgpWriteError::ResourceLimit);
        }
        if let Some(version) = first_legacy_public_version(&request.public_keys)? {
            return Err(OpenPgpWriteError::UnsupportedKeyVersion(version));
        }

        let mut budget = OpenPgpReadBudget::default();
        let certificates = parse_public_key_documents(&request.public_keys, &mut budget)
            .map_err(map_read_error)?;
        let policy_time = reference_time(request.reference_time_epoch_seconds);
        let (recipients, all_recipients_allow_ocb) =
            select_recipients(&certificates, policy_time, &mut budget)?;
        if recipients.is_empty() {
            return Err(OpenPgpWriteError::MissingKey);
        }

        let signing_input = request.signing_private_key.take().map(Zeroizing::new);
        let signing = signing_input
            .as_deref()
            .map(|input| {
                let secret = parse_secret_key(input.as_slice())?;
                let packet = select_signing_packet(
                    &secret,
                    &request.preferred_signing_fingerprint,
                    policy_time,
                )?;
                let selection = SecretPacketSelection::from_ref(&secret, packet)?;
                Ok((secret, selection))
            })
            .transpose()?;
        let literal_time = request
            .literal_time_epoch_seconds
            .map_or_else(Timestamp::now, |value| Timestamp::from_secs(value as u32));
        let mode = if all_recipients_allow_ocb {
            OpenPgpProtectionMode::GnupgOcb
        } else {
            OpenPgpProtectionMode::SeipdV1Mdc
        };
        let config = OpenPgpEncryptWorkerConfig {
            recipients,
            signing,
            file_name: std::mem::take(&mut request.file_name).into_bytes(),
            literal_time,
            armored: request.armored,
            enable_compression: request.enable_compression.unwrap_or(true),
            mode,
        };
        let worker = OpenPgpWorkerPipe::spawn("keyguard-openpgp-encrypt", move |input, output| {
            run_openpgp_encrypt_worker(config, input, output)
        })?;
        Ok(Self { worker })
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
        self.worker.update(data)
    }

    pub(crate) fn finish(self) -> Result<Vec<u8>, OpenPgpWriteError> {
        let (data, final_state) = self.worker.finish()?;
        let OpenPgpWorkerFinal::Encrypt(mode) = final_state else {
            return Err(OpenPgpWriteError::Internal);
        };
        Ok(OpenPgpEncryptFinal {
            data,
            protection_mode: mode as i32,
        }
        .encode_to_vec())
    }
}

fn run_openpgp_encrypt_worker(
    config: OpenPgpEncryptWorkerConfig,
    input: OpenPgpChannelReader,
    output_sender: SyncSender<OpenPgpWorkerOutput>,
) -> Result<OpenPgpWorkerFinal, OpenPgpWriteError> {
    let OpenPgpEncryptWorkerConfig {
        recipients,
        signing,
        file_name,
        literal_time,
        armored,
        enable_compression,
        mode,
    } = config;
    let mut rng = AwsLcRng;
    let session_key = SymmetricKeyAlgorithm::AES256.new_session_key(rng);
    let mut writer = OpenPgpMessageWriter::new(output_sender, armored)?;
    for recipient in &recipients {
        PublicKeyEncryptedSessionKey::from_session_key_v3(
            &mut rng,
            &session_key,
            SymmetricKeyAlgorithm::AES256,
            recipient,
        )
        .and_then(|packet| packet.to_writer_with_header(&mut writer))
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    }

    let packet = signing
        .as_ref()
        .map(|(secret, selection)| selection.packet(secret))
        .transpose()?;
    let rsa_signer = packet
        .filter(|packet| is_rsa_private_algorithm(packet.algorithm()))
        .map(AwsLcRsaSecretKey::new)
        .transpose()?;
    let signer: Option<&dyn SigningKey> = if let Some(adapter) = &rsa_signer {
        Some(adapter)
    } else {
        packet.map(|packet| match packet {
            SecretPacketRef::Primary(key) => key as &dyn SigningKey,
            SecretPacketRef::Subkey(key) => key as &dyn SigningKey,
        })
    };
    let signed = SignedLiteralReader::new(input, &file_name, literal_time, signer)?;
    let composed: Box<dyn Read> = if enable_compression {
        let compressed = DeflateReader::new(signed, Compression::default());
        let compressed_body =
            PrefixedReader::new(vec![u8::from(CompressionAlgorithm::ZIP)], compressed);
        Box::new(PartialPacketReader::new(
            Tag::CompressedData,
            compressed_body,
        ))
    } else {
        Box::new(signed)
    };
    match mode {
        OpenPgpProtectionMode::SeipdV1Mdc => {
            let encrypted = SymmetricKeyAlgorithm::AES256
                .stream_encryptor(rng, session_key.as_ref(), composed)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
            let protected = PrefixedReader::new(vec![1], encrypted);
            let mut protected_packet =
                PartialPacketReader::new(Tag::SymEncryptedProtectedData, protected);
            std::io::copy(&mut protected_packet, &mut writer)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        }
        OpenPgpProtectionMode::GnupgOcb => {
            let encrypted = GnuPgpOcbEncryptReader::new(composed, &session_key, &mut rng)?;
            let mut protected_packet = PartialPacketReader::new(Tag::GnupgAeadData, encrypted);
            std::io::copy(&mut protected_packet, &mut writer)
                .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        }
        OpenPgpProtectionMode::Unspecified => {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
    }
    writer.finish()?;
    Ok(OpenPgpWorkerFinal::Encrypt(mode))
}

struct OpenPgpDecryptWorkerConfig {
    secrets: Vec<SignedSecretKey>,
    verification_certificates: Vec<SignedPublicKey>,
    policy_time: u64,
    allow_signed_only: bool,
}

/// Incremental authenticated OpenPGP decryption. Update output is explicitly
/// provisional; only the encoded final payload proves MDC/AEAD authentication.
pub(crate) struct OpenPgpDecryptionSession {
    worker: OpenPgpWorkerPipe,
}

impl OpenPgpDecryptionSession {
    pub(crate) fn open(
        mut request: OpenPgpDecryptStreamOpenRequest,
    ) -> Result<Self, OpenPgpWriteError> {
        let allow_signed_only = request.allow_signed_only.unwrap_or(false);
        if (!allow_signed_only && request.private_keys.is_empty())
            || request.private_keys.len() > MAX_OPENPGP_KEYS
            || request.verification_public_keys.len() > MAX_OPENPGP_KEYS
        {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        if request
            .private_keys
            .iter()
            .chain(request.verification_public_keys.iter())
            .any(|document| document.len() > MAX_CONTROL_ENVELOPE_BYTES)
        {
            return Err(OpenPgpWriteError::ResourceLimit);
        }
        let secret_inputs = std::mem::take(&mut request.private_keys)
            .into_iter()
            .map(Zeroizing::new)
            .collect::<Vec<_>>();
        let secrets = parse_secret_key_candidates(&secret_inputs)?;
        let mut budget = OpenPgpReadBudget::default();
        let verification_certificates =
            parse_public_key_documents(&request.verification_public_keys, &mut budget)
                .map_err(map_read_error)?;
        let config = OpenPgpDecryptWorkerConfig {
            secrets,
            verification_certificates,
            policy_time: reference_time(request.reference_time_epoch_seconds),
            allow_signed_only,
        };
        let worker = OpenPgpWorkerPipe::spawn("keyguard-openpgp-decrypt", move |input, output| {
            run_openpgp_decrypt_worker(config, input, output)
        })?;
        Ok(Self { worker })
    }

    pub(crate) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
        self.worker.update(data)
    }

    pub(crate) fn finish(self) -> Result<Vec<u8>, OpenPgpWriteError> {
        let (data, final_state) = self.worker.finish()?;
        let OpenPgpWorkerFinal::Decrypt(final_state) = final_state else {
            return Err(OpenPgpWriteError::Internal);
        };
        let OpenPgpDecryptWorkerFinal {
            verification,
            metadata,
            decryption_key_fingerprint,
            declared_charset,
        } = *final_state;
        Ok(OpenPgpDecryptFinal {
            data,
            verification,
            metadata,
            encrypted: decryption_key_fingerprint.is_some(),
            declared_charset,
            decryption_key_fingerprint: decryption_key_fingerprint
                .map(|fingerprint| format!("{fingerprint:X}")),
        }
        .encode_to_vec())
    }
}

fn run_openpgp_decrypt_worker(
    config: OpenPgpDecryptWorkerConfig,
    input: OpenPgpChannelReader,
    output: SyncSender<OpenPgpWorkerOutput>,
) -> Result<OpenPgpWorkerFinal, OpenPgpWriteError> {
    let (message, armor_headers) = parse_streaming_message(input)?;
    let declared_charset = declared_armor_charset(armor_headers.as_ref());
    let OpenedLiteralMessage {
        mut message,
        decryption_key_fingerprint,
    } = open_literal_message(
        message,
        &config.secrets,
        config.policy_time,
        config.allow_signed_only,
        DecryptionOptions::new()
            .enable_gnupg_aead()
            .set_seipdv1_read_mode(Seipdv1ReadMode::Streaming),
    )?;
    let encrypted = decryption_key_fingerprint.is_some();
    let mut metadata = literal_metadata(&message)?;

    let mut buffer = Zeroizing::new(vec![0_u8; OPENPGP_PARTIAL_PACKET_BYTES]);
    let mut original_size = 0_u64;
    loop {
        let read = message
            .read(&mut buffer)
            .map_err(|_| OpenPgpWriteError::AuthenticationFailed)?;
        if read == 0 {
            break;
        }
        original_size = original_size
            .checked_add(read as u64)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        output
            .send(OpenPgpWorkerOutput::Data(Zeroizing::new(
                buffer[..read].to_vec(),
            )))
            .map_err(|_| OpenPgpWriteError::Internal)?;
        buffer[..read].zeroize();
    }
    let verification = finish_inline_verification(
        &message,
        &config.verification_certificates,
        config.policy_time,
        encrypted,
    )?;
    metadata.original_size = original_size;
    Ok(OpenPgpWorkerFinal::Decrypt(Box::new(
        OpenPgpDecryptWorkerFinal {
            verification,
            metadata: Some(metadata),
            decryption_key_fingerprint,
            declared_charset,
        },
    )))
}

/// Decrypts and authenticates a bounded OpenPGP message. Plaintext remains in
/// zeroizing staging memory until the final MDC/AEAD tag has been consumed.
pub(crate) fn decrypt_request(
    mut request: OpenPgpDecryptRequest,
) -> Result<Vec<u8>, OpenPgpWriteError> {
    if request.content.is_empty()
        || request.content.len() > MAX_CONTROL_ENVELOPE_BYTES
        || (!request.allow_signed_only.unwrap_or(false) && request.private_keys.is_empty())
        || request.private_keys.len() > MAX_OPENPGP_KEYS
        || request.verification_public_keys.len() > MAX_OPENPGP_KEYS
    {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    if request
        .private_keys
        .iter()
        .chain(request.verification_public_keys.iter())
        .any(|document| document.len() > MAX_CONTROL_ENVELOPE_BYTES)
    {
        return Err(OpenPgpWriteError::ResourceLimit);
    }

    let secret_inputs = std::mem::take(&mut request.private_keys)
        .into_iter()
        .map(Zeroizing::new)
        .collect::<Vec<_>>();
    let secrets = parse_secret_key_candidates(&secret_inputs)?;
    let mut budget = OpenPgpReadBudget::default();
    let verification_certificates =
        parse_public_key_documents(&request.verification_public_keys, &mut budget)
            .map_err(map_read_error)?;
    let policy_time = reference_time(request.reference_time_epoch_seconds);

    let content = Zeroizing::new(std::mem::take(&mut request.content));
    let (message, armor_headers) =
        Message::from_reader(BufReader::new(Cursor::new(content.as_slice())))
            .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
    let declared_charset = declared_armor_charset(armor_headers.as_ref());
    let OpenedLiteralMessage {
        mut message,
        decryption_key_fingerprint,
    } = open_literal_message(
        message,
        &secrets,
        policy_time,
        request.allow_signed_only.unwrap_or(false),
        DecryptionOptions::new().enable_gnupg_aead(),
    )?;
    let encrypted = decryption_key_fingerprint.is_some();
    let mut metadata = literal_metadata(&message)?;

    let mut plaintext = Zeroizing::new(Vec::new());
    read_to_end_bounded(&mut message, &mut plaintext, MAX_CONTROL_ENVELOPE_BYTES).map_err(
        |error| match error {
            BoundedReadError::ResourceLimit => OpenPgpWriteError::ResourceLimit,
            BoundedReadError::Io => OpenPgpWriteError::AuthenticationFailed,
        },
    )?;
    let verification =
        finish_inline_verification(&message, &verification_certificates, policy_time, encrypted)?;
    metadata.original_size =
        u64::try_from(plaintext.len()).map_err(|_| OpenPgpWriteError::ResourceLimit)?;
    let result = OpenPgpDecryptResult {
        data: plaintext.to_vec(),
        verification,
        metadata: Some(metadata),
        encrypted,
        declared_charset,
        decryption_key_fingerprint: decryption_key_fingerprint
            .map(|fingerprint| format!("{fingerprint:X}")),
    }
    .encode_to_vec();
    if result.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    Ok(result)
}

/// Resolves a parsed message to its literal form, enforcing the signed-only
/// policy: a message that is not encrypted is accepted only when
/// `allow_signed_only` is set and it carries an inline signature. Returns the
/// authenticated literal message and the private component used to open it.
struct OpenedLiteralMessage<'a> {
    message: Message<'a>,
    decryption_key_fingerprint: Option<Fingerprint>,
}

fn open_literal_message<'a>(
    message: Message<'a>,
    secrets: &[SignedSecretKey],
    policy_time: u64,
    allow_signed_only: bool,
    decrypt_options: DecryptionOptions,
) -> Result<OpenedLiteralMessage<'a>, OpenPgpWriteError> {
    let (message, decryption_key_fingerprint) = if message.is_encrypted() {
        let recovered = find_message_session_key(&message, secrets, policy_time)?;
        let ring = TheRing {
            session_keys: vec![recovered.session_key],
            decrypt_options,
            ..TheRing::default()
        };
        let message = message
            .decrypt_the_ring(ring, true)
            .map_err(|_| OpenPgpWriteError::AuthenticationFailed)?
            .0;
        (message, Some(recovered.key_fingerprint))
    } else if allow_signed_only {
        (message, None)
    } else {
        return Err(OpenPgpWriteError::InvalidArgument);
    };
    let message = decompress_to_literal(message)?;
    if decryption_key_fingerprint.is_none() && !message.is_signed() {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    Ok(OpenedLiteralMessage {
        message,
        decryption_key_fingerprint,
    })
}

/// Evaluates inline signatures once the literal data has been fully read,
/// rejecting messages accepted under the signed-only policy whose signature
/// did not verify.
fn finish_inline_verification(
    message: &Message<'_>,
    certificates: &[SignedPublicKey],
    policy_time: u64,
    encrypted: bool,
) -> Result<Option<OpenPgpVerification>, OpenPgpWriteError> {
    let verification = evaluate_inline_verification(message, certificates, policy_time)?;
    if !encrypted && verification.is_none() {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    Ok(verification)
}

fn literal_metadata(message: &Message<'_>) -> Result<OpenPgpLiteralMetadata, OpenPgpWriteError> {
    let header = message
        .literal_data_header()
        .ok_or(OpenPgpWriteError::InvalidArgument)?;
    Ok(OpenPgpLiteralMetadata {
        file_name: header.file_name().to_vec(),
        format: u32::from(u8::from(header.mode())),
        modification_time_epoch_seconds: u64::from(header.created().as_secs()),
        original_size: 0,
    })
}

fn parse_secret_key_candidates(
    inputs: &[Zeroizing<Vec<u8>>],
) -> Result<Vec<SignedSecretKey>, OpenPgpWriteError> {
    if inputs.is_empty() {
        return Ok(Vec::new());
    }
    let mut secrets = Vec::with_capacity(inputs.len());
    let mut unsupported_version = None;
    for input in inputs {
        match parse_secret_key(input.as_slice()) {
            Ok(secret) => secrets.push(secret),
            Err(OpenPgpWriteError::UnsupportedKeyVersion(version)) => {
                unsupported_version.get_or_insert(version);
            }
            Err(error) => return Err(error),
        }
    }
    if secrets.is_empty() {
        return Err(unsupported_version.map_or(
            OpenPgpWriteError::MissingKey,
            OpenPgpWriteError::UnsupportedKeyVersion,
        ));
    }
    Ok(secrets)
}

struct RecoveredSessionKey {
    session_key: PlainSessionKey,
    key_fingerprint: Fingerprint,
}

fn find_message_session_key(
    message: &Message<'_>,
    secrets: &[SignedSecretKey],
    reference_time: u64,
) -> Result<RecoveredSessionKey, OpenPgpWriteError> {
    let Message::Encrypted { esk, .. } = message else {
        return Err(OpenPgpWriteError::InvalidArgument);
    };
    if esk.len() > MAX_OPENPGP_KEYS {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    let mut private_key_attempts = 0_usize;
    let mut budget = OpenPgpReadBudget::default();
    let public_keys = secrets
        .iter()
        .map(SignedSecretKey::to_public_key)
        .collect::<Vec<_>>();
    let candidates = all_components(&public_keys);
    let policies = public_keys
        .iter()
        .map(|public| inspect_certificate(public, &candidates, reference_time, &mut budget))
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_read_error)?;
    for encrypted_session_key in esk {
        let Esk::PublicKeyEncryptedSessionKey(pkesk) = encrypted_session_key else {
            continue;
        };
        let typ = match pkesk.version() {
            PkeskVersion::V3 => EskType::V3_4,
            PkeskVersion::V6 => EskType::V6,
            PkeskVersion::Other(_) => continue,
        };
        let values = pkesk
            .values()
            .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
        for (secret, policy) in secrets.iter().zip(&policies) {
            if !policy.primary.authenticated
                || policy.primary.revoked
                || component_is_expired(&policy.primary, reference_time)
            {
                continue;
            }
            let primary = SecretPacketRef::Primary(&secret.primary_key);
            if encryption_component_usable(&policy.primary, reference_time)
                && pkesk.match_identity(&primary)
                && session_key_algorithm_matches(primary, values)
            {
                increment_private_key_attempts(&mut private_key_attempts)?;
                if let Some(session_key) = decrypt_session_key(primary, values, typ) {
                    return Ok(RecoveredSessionKey {
                        session_key,
                        key_fingerprint: primary.fingerprint(),
                    });
                }
            }
            let public_offset = secret.public_subkeys.len();
            for (index, subkey) in secret.secret_subkeys.iter().enumerate() {
                let Some(component) = policy.subkeys.get(public_offset + index) else {
                    continue;
                };
                let packet = SecretPacketRef::Subkey(&subkey.key);
                if encryption_component_usable(component, reference_time)
                    && pkesk.match_identity(&packet)
                    && session_key_algorithm_matches(packet, values)
                {
                    increment_private_key_attempts(&mut private_key_attempts)?;
                    if let Some(session_key) = decrypt_session_key(packet, values, typ) {
                        return Ok(RecoveredSessionKey {
                            session_key,
                            key_fingerprint: packet.fingerprint(),
                        });
                    }
                }
            }
        }
    }
    Err(OpenPgpWriteError::MissingKey)
}

fn increment_private_key_attempts(attempts: &mut usize) -> Result<(), OpenPgpWriteError> {
    *attempts = attempts
        .checked_add(1)
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    (*attempts <= MAX_OPENPGP_PRIVATE_KEY_ATTEMPTS)
        .then_some(())
        .ok_or(OpenPgpWriteError::ResourceLimit)
}

fn session_key_algorithm_matches(packet: SecretPacketRef<'_>, values: &PkeskBytes) -> bool {
    matches!(
        (packet.algorithm(), values),
        (
            PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt,
            PkeskBytes::Rsa { .. }
        ) | (
            PublicKeyAlgorithm::Elgamal | PublicKeyAlgorithm::ElgamalEncrypt,
            PkeskBytes::Elgamal { .. }
        ) | (PublicKeyAlgorithm::ECDH, PkeskBytes::Ecdh { .. })
            | (PublicKeyAlgorithm::X25519, PkeskBytes::X25519 { .. })
            | (PublicKeyAlgorithm::X448, PkeskBytes::X448 { .. })
    )
}

fn decompress_to_literal<'a>(mut message: Message<'a>) -> Result<Message<'a>, OpenPgpWriteError> {
    for _ in 0..MAX_OPENPGP_NESTING {
        validate_signed_nesting(&message)?;
        if message.literal_data_header().is_some() {
            return Ok(message);
        }
        if !message.is_compressed() && !message.is_signed() {
            return Err(OpenPgpWriteError::InvalidArgument);
        }
        message = message
            .decompress()
            .map_err(|_| OpenPgpWriteError::AuthenticationFailed)?;
    }
    Err(OpenPgpWriteError::ResourceLimit)
}

fn validate_signed_nesting(message: &Message<'_>) -> Result<(), OpenPgpWriteError> {
    let mut current = message;
    let mut depth = 0_usize;
    let mut signatures = 0_usize;
    while let Message::Signed { reader, .. } = current {
        depth = depth
            .checked_add(1)
            .filter(|value| *value <= MAX_OPENPGP_NESTING)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        signatures = signatures
            .checked_add(reader.num_signatures())
            .filter(|value| *value <= MAX_OPENPGP_COMPONENTS)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        current = reader.get_ref();
    }
    Ok(())
}

fn decrypt_session_key(
    packet: SecretPacketRef<'_>,
    values: &PkeskBytes,
    typ: EskType,
) -> Option<PlainSessionKey> {
    let password = Password::empty();
    let result = if matches!(
        packet.algorithm(),
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt
    ) {
        AwsLcRsaSecretKey::new(packet)
            .ok()?
            .decrypt(&password, values, typ)
    } else {
        match packet {
            SecretPacketRef::Primary(key) => key.decrypt(&password, values, typ),
            SecretPacketRef::Subkey(key) => key.decrypt(&password, values, typ),
        }
    };
    result.ok().and_then(Result::ok)
}

fn evaluate_inline_verification(
    message: &Message<'_>,
    certificates: &[SignedPublicKey],
    reference_time: u64,
) -> Result<Option<OpenPgpVerification>, OpenPgpWriteError> {
    let Message::Signed { reader, .. } = message else {
        return Ok(None);
    };
    if reader.num_signatures() > MAX_OPENPGP_COMPONENTS {
        return Err(OpenPgpWriteError::ResourceLimit);
    }
    let components = all_components(certificates);
    let mut selected = None;
    for index in 0..reader.num_signatures() {
        let Some(signature) = reader.signature(index) else {
            continue;
        };
        let matching_component = components
            .iter()
            .find(|component| signature_matches_component(signature, component));
        if let Some(component) = matching_component {
            let valid = message.verify_nested_explicit(index, component).is_ok();
            selected = Some((signature, valid));
            break;
        }
        selected.get_or_insert((signature, false));
    }
    let Some((signature, valid)) = selected else {
        return Err(OpenPgpWriteError::InvalidArgument);
    };
    evaluate_preverified_signature(signature, certificates, reference_time, valid)
        .map(Some)
        .map_err(map_read_error)
}

fn signature_matches_component(
    signature: &pgp::packet::Signature,
    component: &PublicComponent,
) -> bool {
    signature
        .issuer_key_id()
        .iter()
        .any(|issuer| **issuer == component.legacy_key_id())
        || signature
            .issuer_fingerprint()
            .iter()
            .any(|issuer| issuer.as_bytes() == component.fingerprint().as_bytes())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum BoundedReadError {
    ResourceLimit,
    Io,
}

fn read_to_end_bounded(
    reader: &mut impl Read,
    output: &mut Vec<u8>,
    limit: usize,
) -> Result<(), BoundedReadError> {
    let mut buffer = Zeroizing::new([0_u8; 64 * 1024]);
    loop {
        let read = reader
            .read(&mut buffer[..])
            .map_err(|_| BoundedReadError::Io)?;
        if read == 0 {
            return Ok(());
        }
        let new_len = output
            .len()
            .checked_add(read)
            .filter(|value| *value <= limit)
            .ok_or(BoundedReadError::ResourceLimit)?;
        output.reserve(new_len - output.len());
        output.extend_from_slice(&buffer[..read]);
    }
}

#[derive(Default)]
struct SecretVec(Vec<u8>);

impl SecretVec {
    fn into_zeroizing(mut self) -> Zeroizing<Vec<u8>> {
        Zeroizing::new(std::mem::take(&mut self.0))
    }
}

impl Write for SecretVec {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        self.0.write(buffer)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.0.flush()
    }
}

impl Drop for SecretVec {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

fn map_read_error(error: crate::openpgp_read::OpenPgpReadError) -> OpenPgpWriteError {
    match error {
        crate::openpgp_read::OpenPgpReadError::InvalidArgument => {
            OpenPgpWriteError::InvalidArgument
        }
        crate::openpgp_read::OpenPgpReadError::ResourceLimit => OpenPgpWriteError::ResourceLimit,
        crate::openpgp_read::OpenPgpReadError::Internal => OpenPgpWriteError::Internal,
    }
}

fn import_error(reason: OpenPgpKeyImportErrorReason) -> Vec<u8> {
    OpenPgpKeyImportResult {
        result: Some(open_pgp_key_import_result::Result::Error(
            OpenPgpKeyImportError {
                reason: reason as i32,
            },
        )),
    }
    .encode_to_vec()
}

fn generate_modern_certificate(
    user_id: &str,
    created_at: Timestamp,
    expiration: Option<u32>,
) -> Result<SignedSecretKey, OpenPgpWriteError> {
    let rng = AwsLcRng;
    let (primary_public, primary_secret) = pgp::composed::KeyType::Ed25519Legacy
        .generate(rng)
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let (signing_public, signing_secret) = pgp::composed::KeyType::Ed25519Legacy
        .generate(rng)
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let (encryption_public, encryption_secret) =
        pgp::composed::KeyType::ECDH(ECCCurve::Curve25519Legacy)
            .generate(rng)
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;

    let primary = secret_primary_from_params(
        PublicKeyAlgorithm::EdDSALegacy,
        created_at,
        primary_public,
        primary_secret,
    )?;
    let signing = secret_subkey_from_params(
        PublicKeyAlgorithm::EdDSALegacy,
        created_at,
        signing_public,
        signing_secret,
    )?;
    let encryption = secret_subkey_from_params(
        PublicKeyAlgorithm::ECDH,
        created_at,
        encryption_public,
        encryption_secret,
    )?;
    compose_generated_certificate(
        primary, signing, encryption, user_id, created_at, expiration,
    )
}

fn generate_rsa_certificate(
    user_id: &str,
    created_at: Timestamp,
    expiration: Option<u32>,
    bits: u32,
) -> Result<SignedSecretKey, OpenPgpWriteError> {
    if !matches!(bits, 3_072 | 4_096) {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let primary_der = generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let signing_der = generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let encryption_der =
        generate_rsa_pkcs1_der(bits).map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    let primary = rsa_primary_from_der(&primary_der, created_at)?;
    let signing = rsa_subkey_from_der(&signing_der, created_at)?;
    let encryption = rsa_subkey_from_der(&encryption_der, created_at)?;
    compose_generated_certificate(
        primary, signing, encryption, user_id, created_at, expiration,
    )
}

fn secret_primary_from_params(
    algorithm: PublicKeyAlgorithm,
    created_at: Timestamp,
    public: PublicParams,
    secret: SecretParams,
) -> Result<SecretKey, OpenPgpWriteError> {
    let inner = PubKeyInner::new(KeyVersion::V4, algorithm, created_at, None, public)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let public =
        pgp::packet::PublicKey::from_inner(inner).map_err(|_| OpenPgpWriteError::Internal)?;
    SecretKey::new(public, secret).map_err(|_| OpenPgpWriteError::Internal)
}

fn secret_subkey_from_params(
    algorithm: PublicKeyAlgorithm,
    created_at: Timestamp,
    public: PublicParams,
    secret: SecretParams,
) -> Result<SecretSubkey, OpenPgpWriteError> {
    let inner = PubKeyInner::new(KeyVersion::V4, algorithm, created_at, None, public)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let public = PublicSubkey::from_inner(inner).map_err(|_| OpenPgpWriteError::Internal)?;
    SecretSubkey::new(public, secret).map_err(|_| OpenPgpWriteError::Internal)
}

fn rsa_primary_from_der(der: &[u8], created_at: Timestamp) -> Result<SecretKey, OpenPgpWriteError> {
    let body = rsa_secret_packet_body(der, created_at)?;
    let header = PacketHeader::new_fixed(
        Tag::SecretKey,
        u32::try_from(body.len()).map_err(|_| OpenPgpWriteError::ResourceLimit)?,
    );
    SecretKey::try_from_reader(header, Cursor::new(body.as_slice()))
        .map_err(|_| OpenPgpWriteError::Internal)
}

fn rsa_subkey_from_der(
    der: &[u8],
    created_at: Timestamp,
) -> Result<SecretSubkey, OpenPgpWriteError> {
    let body = rsa_secret_packet_body(der, created_at)?;
    let header = PacketHeader::new_fixed(
        Tag::SecretSubkey,
        u32::try_from(body.len()).map_err(|_| OpenPgpWriteError::ResourceLimit)?,
    );
    SecretSubkey::try_from_reader(header, Cursor::new(body.as_slice()))
        .map_err(|_| OpenPgpWriteError::Internal)
}

fn rsa_secret_packet_body(
    der: &[u8],
    created_at: Timestamp,
) -> Result<Zeroizing<Vec<u8>>, OpenPgpWriteError> {
    use pkcs1::der::Decode;

    let key = pkcs1::RsaPrivateKey::from_der(der).map_err(|_| OpenPgpWriteError::Internal)?;
    if key.other_prime_infos.is_some() {
        return Err(OpenPgpWriteError::InvalidArgument);
    }
    let modulus = key.modulus.as_bytes();
    let public_exponent = key.public_exponent.as_bytes();
    let private_exponent = key.private_exponent.as_bytes();
    // PKCS#1 stores coefficient = q^-1 mod p, while OpenPGP stores
    // u = p^-1 mod q. Prime order is not semantically significant, so emit
    // OpenPGP p = PKCS#1 q and OpenPGP q = PKCS#1 p. The AWS-LC-produced
    // PKCS#1 coefficient is then exactly the OpenPGP u value, avoiding any
    // additional private-key arithmetic outside the sensitive backend.
    let prime_p = key.prime2.as_bytes();
    let prime_q = key.prime1.as_bytes();
    let coefficient = key.coefficient.as_bytes();

    let mut body = Zeroizing::new(Vec::new());
    body.push(u8::from(KeyVersion::V4));
    body.extend_from_slice(&created_at.as_secs().to_be_bytes());
    body.push(u8::from(PublicKeyAlgorithm::RSA));
    write_mpi(&mut body, modulus)?;
    write_mpi(&mut body, public_exponent)?;
    body.push(0); // unprotected secret material
    let secret_start = body.len();
    write_mpi(&mut body, private_exponent)?;
    write_mpi(&mut body, prime_p)?;
    write_mpi(&mut body, prime_q)?;
    write_mpi(&mut body, coefficient)?;
    let checksum = body[secret_start..]
        .iter()
        .fold(0_u16, |sum, value| sum.wrapping_add(u16::from(*value)));
    body.extend_from_slice(&checksum.to_be_bytes());
    Ok(body)
}

fn compose_generated_certificate(
    primary: SecretKey,
    signing: SecretSubkey,
    encryption: SecretSubkey,
    user_id: &str,
    created_at: Timestamp,
    expiration: Option<u32>,
) -> Result<SignedSecretKey, OpenPgpWriteError> {
    let primary_ref = SecretPacketRef::Primary(&primary);
    let signing_ref = SecretPacketRef::Subkey(&signing);
    let primary_rsa = is_rsa_private_algorithm(primary.algorithm())
        .then(|| AwsLcRsaSecretKey::new(primary_ref))
        .transpose()?;
    let signing_rsa = is_rsa_private_algorithm(signing.algorithm())
        .then(|| AwsLcRsaSecretKey::new(signing_ref))
        .transpose()?;
    let primary_signer = SigningKeyRef(
        primary_rsa
            .as_ref()
            .map_or(&primary as &dyn SigningKey, |key| key),
    );
    let signing_signer = SigningKeyRef(
        signing_rsa
            .as_ref()
            .map_or(&signing as &dyn SigningKey, |key| key),
    );
    let password = Password::empty();

    let user = UserId::from_str(Default::default(), user_id)
        .map_err(|_| OpenPgpWriteError::InvalidArgument)?;
    let mut certification = SignatureConfig::v4(
        SignatureType::CertPositive,
        primary_signer.algorithm(),
        HashAlgorithm::Sha256,
    );
    let mut primary_flags = KeyFlags::default();
    primary_flags.set_certify(true);
    certification.hashed_subpackets =
        common_key_subpackets(&primary_signer, created_at, expiration, Some(primary_flags))?;
    certification
        .hashed_subpackets
        .push(Subpacket::regular(SubpacketData::IsPrimary(true)).map_err(pgp_internal)?);
    certification.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary_signer.legacy_key_id()))
            .map_err(pgp_internal)?,
    ];
    let certification = certification
        .sign_certification(
            &primary_signer,
            primary.public_key(),
            &password,
            user.tag(),
            &user,
        )
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;

    let mut back_signature = SignatureConfig::v4(
        SignatureType::KeyBinding,
        signing_signer.algorithm(),
        HashAlgorithm::Sha256,
    );
    back_signature.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(created_at))
            .map_err(pgp_internal)?,
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            signing_signer.fingerprint(),
        ))
        .map_err(pgp_internal)?,
    ];
    back_signature.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(signing_signer.legacy_key_id()))
            .map_err(pgp_internal)?,
    ];
    let back_signature = back_signature
        .sign_primary_key_binding(
            &signing_signer,
            signing.public_key(),
            &password,
            primary.public_key(),
        )
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;

    let signing_binding = subkey_binding_signature(
        primary_signer,
        primary.public_key(),
        signing.public_key(),
        &password,
        created_at,
        expiration,
        true,
        Some(back_signature),
    )?;
    let encryption_binding = subkey_binding_signature(
        primary_signer,
        primary.public_key(),
        encryption.public_key(),
        &password,
        created_at,
        expiration,
        false,
        None,
    )?;

    Ok(SignedSecretKey::new(
        primary,
        SignedKeyDetails::new(
            Vec::new(),
            Vec::new(),
            vec![user.into_signed(certification)],
            Vec::new(),
        ),
        Vec::new(),
        vec![
            SignedSecretSubKey::new(signing, vec![signing_binding]),
            SignedSecretSubKey::new(encryption, vec![encryption_binding]),
        ],
    ))
}

fn common_key_subpackets(
    signer: &dyn SigningKey,
    created_at: Timestamp,
    expiration: Option<u32>,
    flags: Option<KeyFlags>,
) -> Result<Vec<Subpacket>, OpenPgpWriteError> {
    let mut subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(created_at))
            .map_err(pgp_internal)?,
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .map_err(pgp_internal)?,
    ];
    if let Some(expiration) = expiration {
        subpackets.push(
            Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(
                expiration,
            )))
            .map_err(pgp_internal)?,
        );
    }
    if let Some(flags) = flags {
        subpackets.push(Subpacket::regular(SubpacketData::KeyFlags(flags)).map_err(pgp_internal)?);
    }
    // 0x01 = SEIPDv1/MDC, 0x02 = LibrePGP/GnuPG tag-20 OCB.
    subpackets.push(
        Subpacket::regular(SubpacketData::Features(Features::from(&[0x03][..])))
            .map_err(pgp_internal)?,
    );
    subpackets.push(
        Subpacket::regular(SubpacketData::PreferredSymmetricAlgorithms(
            vec![SymmetricKeyAlgorithm::AES256].into(),
        ))
        .map_err(pgp_internal)?,
    );
    subpackets.push(
        Subpacket::regular(SubpacketData::PreferredHashAlgorithms(
            vec![HashAlgorithm::Sha256].into(),
        ))
        .map_err(pgp_internal)?,
    );
    subpackets.push(
        Subpacket::regular(SubpacketData::PreferredCompressionAlgorithms(
            vec![CompressionAlgorithm::ZIP].into(),
        ))
        .map_err(pgp_internal)?,
    );
    subpackets.push(
        Subpacket::regular(SubpacketData::PreferredAeadAlgorithms(
            vec![(SymmetricKeyAlgorithm::AES256, AeadAlgorithm::Ocb)].into(),
        ))
        .map_err(pgp_internal)?,
    );
    Ok(subpackets)
}

#[allow(clippy::too_many_arguments)]
fn subkey_binding_signature<K>(
    primary_signer: SigningKeyRef<'_>,
    primary_public: &pgp::packet::PublicKey,
    subkey_public: &K,
    password: &Password,
    created_at: Timestamp,
    expiration: Option<u32>,
    signing: bool,
    embedded: Option<pgp::packet::Signature>,
) -> Result<pgp::packet::Signature, OpenPgpWriteError>
where
    K: KeyDetails + Serialize,
{
    let mut flags = KeyFlags::default();
    flags.set_sign(signing);
    flags.set_encrypt_comms(!signing);
    flags.set_encrypt_storage(!signing);
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyBinding,
        primary_signer.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets =
        common_key_subpackets(&primary_signer, created_at, expiration, Some(flags))?;
    if let Some(embedded) = embedded {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::EmbeddedSignature(Box::new(embedded)))
                .map_err(pgp_internal)?,
        );
    }
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(primary_signer.legacy_key_id()))
            .map_err(pgp_internal)?,
    ];
    config
        .sign_subkey_binding(&primary_signer, primary_public, password, subkey_public)
        .map_err(|_| OpenPgpWriteError::CryptoFailure)
}

fn encode_key_material(
    certificate: &SignedSecretKey,
) -> Result<OpenPgpKeyMaterial, OpenPgpWriteError> {
    let public = certificate.to_public_key();
    let private_key_armored = certificate
        .to_armored_bytes(ArmorOptions::default())
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let public_key_armored = public
        .to_armored_bytes(ArmorOptions::default())
        .map_err(|_| OpenPgpWriteError::Internal)?;
    Ok(OpenPgpKeyMaterial {
        private_key_armored,
        public_key_armored,
        fingerprint: fingerprint_hex(&public.primary_key),
    })
}

fn pgp_internal(_: pgp::errors::Error) -> OpenPgpWriteError {
    OpenPgpWriteError::Internal
}

fn parse_mpis(input: &[u8], count: usize) -> Option<Vec<Vec<u8>>> {
    let mut offset = 0_usize;
    let mut output = Vec::with_capacity(count);
    for _ in 0..count {
        let bit_length =
            u16::from_be_bytes([*input.get(offset)?, *input.get(offset.checked_add(1)?)?]);
        offset = offset.checked_add(2)?;
        let byte_length = usize::from(bit_length).div_ceil(8);
        let end = offset.checked_add(byte_length)?;
        let value = input.get(offset..end)?;
        if value.is_empty() || value.first() == Some(&0) {
            return None;
        }
        output.push(value.to_vec());
        offset = end;
    }
    (offset == input.len()).then_some(output)
}

fn write_mpi(output: &mut Vec<u8>, value: &[u8]) -> Result<(), OpenPgpWriteError> {
    let first_nonzero = value
        .iter()
        .position(|byte| *byte != 0)
        .ok_or(OpenPgpWriteError::InvalidArgument)?;
    let value = &value[first_nonzero..];
    let leading = value[0].leading_zeros() as usize;
    let bit_length = value
        .len()
        .checked_mul(8)
        .and_then(|bits| bits.checked_sub(leading))
        .and_then(|bits| u16::try_from(bits).ok())
        .ok_or(OpenPgpWriteError::ResourceLimit)?;
    output.extend_from_slice(&bit_length.to_be_bytes());
    output.extend_from_slice(value);
    Ok(())
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

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic, clippy::unwrap_used)]

    use super::*;
    use crate::protocol::{
        OpenPgpDecryptResult, OpenPgpEncryptResult, OpenPgpKeyGenerateRequest,
        OpenPgpKeyImportRequest, OpenPgpKeyImportResult, OpenPgpMetadataResolveRequest,
        OpenPgpMetadataResolveResult, OpenPgpSignRequest, OpenPgpVerificationStatus,
        OpenPgpVerifyKind, OpenPgpVerifyRequest, open_pgp_key_import_result,
    };
    use pgp::composed::{KeyType, MessageBuilder, SecretKeyParamsBuilder};
    use pgp::types::{EncryptedSecretParams, RevocationKey, RevocationKeyClass, StringToKey};

    const TEST_TIME: u64 = 1_700_000_000;
    static STREAM_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn for_odd_chunks(data: &[u8], sizes: &[usize], mut operation: impl FnMut(&[u8])) {
        let mut offset = 0_usize;
        for size in sizes {
            if offset == data.len() {
                return;
            }
            let end = offset.saturating_add(*size).min(data.len());
            operation(&data[offset..end]);
            offset = end;
        }
        for chunk in data[offset..].chunks(OPENPGP_PARTIAL_PACKET_BYTES) {
            operation(chunk);
        }
    }

    fn generated_modern_material() -> OpenPgpKeyMaterial {
        OpenPgpKeyMaterial::decode(
            generate_key_request(OpenPgpKeyGenerateRequest {
                kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
                user_id: "Alice Example <alice@example.test>".to_owned(),
                rsa_bits: 0,
                creation_time_epoch_seconds: TEST_TIME,
                expiration_seconds: None,
            })
            .expect("generate certificate")
            .as_slice(),
        )
        .expect("decode key material")
    }

    fn generated_rsa_material() -> OpenPgpKeyMaterial {
        OpenPgpKeyMaterial::decode(
            generate_key_request(OpenPgpKeyGenerateRequest {
                kind: OpenPgpKeyKind::Rsa as i32,
                user_id: "RSA Example <rsa@example.test>".to_owned(),
                rsa_bits: 3072,
                creation_time_epoch_seconds: TEST_TIME,
                expiration_seconds: None,
            })
            .expect("generate RSA certificate")
            .as_slice(),
        )
        .expect("decode RSA key material")
    }

    fn import_secret(secret: &SignedSecretKey) -> OpenPgpKeyImportResult {
        let key_data = secret
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor secret key");
        OpenPgpKeyImportResult::decode(
            import_key_request(OpenPgpKeyImportRequest {
                key_data,
                passphrase_utf8: None,
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("import request")
            .as_slice(),
        )
        .expect("decode import result")
    }

    fn imported_material(result: OpenPgpKeyImportResult) -> OpenPgpKeyMaterial {
        match result.result {
            Some(open_pgp_key_import_result::Result::Success(success)) => {
                success.key_material.expect("imported key material")
            }
            result => panic!("expected successful import, got {result:?}"),
        }
    }

    fn import_key_data(key_data: Vec<u8>) -> OpenPgpKeyImportResult {
        OpenPgpKeyImportResult::decode(
            import_key_request(OpenPgpKeyImportRequest {
                key_data,
                passphrase_utf8: None,
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("import request")
            .as_slice(),
        )
        .expect("decode import result")
    }

    fn resolved_metadata(
        material: &OpenPgpKeyMaterial,
    ) -> Option<crate::protocol::OpenPgpKeyMetadata> {
        OpenPgpMetadataResolveResult::decode(
            crate::openpgp_read::resolve_metadata(OpenPgpMetadataResolveRequest {
                private_key_data: Some(material.private_key_armored.clone()),
                public_key_data: Some(material.public_key_armored.clone()),
                normalized_fingerprint: material.fingerprint.clone(),
                candidate_revocation_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("resolve imported metadata")
            .as_slice(),
        )
        .expect("decode imported metadata")
        .metadata
    }

    fn signature_config(
        signature_type: SignatureType,
        signer: &dyn SigningKey,
        created_at: u64,
    ) -> SignatureConfig {
        let created_at = Timestamp::from_secs(created_at as u32);
        let mut config =
            SignatureConfig::v4(signature_type, signer.algorithm(), HashAlgorithm::Sha256);
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(created_at))
                .expect("signature creation subpacket"),
            Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
                .expect("issuer fingerprint subpacket"),
        ];
        config.unhashed_subpackets = vec![
            Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
                .expect("issuer key ID subpacket"),
        ];
        config
    }

    fn subkey_binding_with_signature_expiration(
        secret: &SignedSecretKey,
        subkey_index: usize,
        created_at: u64,
        expiration_seconds: u32,
        keep_embedded_signature: bool,
    ) -> pgp::packet::Signature {
        let primary = &secret.primary_key;
        let subkey = secret.secret_subkeys[subkey_index].key.public_key().clone();
        let template = secret.secret_subkeys[subkey_index]
            .signatures
            .iter()
            .find(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
            .expect("subkey binding template");
        let mut config = template.config().cloned().expect("binding config");
        config
            .hashed_subpackets
            .retain(|subpacket| match subpacket.data {
                SubpacketData::SignatureCreationTime(_)
                | SubpacketData::SignatureExpirationTime(_) => false,
                SubpacketData::EmbeddedSignature(_) => keep_embedded_signature,
                _ => true,
            });
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                created_at as u32,
            )))
            .expect("signature creation subpacket"),
        );
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                expiration_seconds,
            )))
            .expect("signature expiration subpacket"),
        );
        config
            .sign_subkey_binding(primary, primary.public_key(), &Password::empty(), &subkey)
            .expect("sign replacement subkey binding")
    }

    #[test]
    fn gnupg_ocb_associated_data_uses_new_packet_tag_octet() {
        let associated_data = gnupg_ocb_associated_data(0x0102_0304_0506_0708);
        assert_eq!(associated_data[0], 0xd4);
        assert_eq!(
            &associated_data[5..],
            &0x0102_0304_0506_0708_u64.to_be_bytes()
        );
    }

    #[test]
    fn generated_certificate_imports_and_signs() {
        let material = generated_modern_material();
        let imported = OpenPgpKeyImportResult::decode(
            import_key_request(OpenPgpKeyImportRequest {
                key_data: material.private_key_armored.clone(),
                passphrase_utf8: None,
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("import request")
            .as_slice(),
        )
        .expect("decode import result");
        assert!(matches!(
            imported.result,
            Some(open_pgp_key_import_result::Result::Success(_))
        ));

        let content = b"OpenPGP detached signature";
        let signature = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::Detached as i32,
            content: content.to_vec(),
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: material.fingerprint.clone(),
            armored: false,
            signature_time_epoch_seconds: Some(TEST_TIME + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("sign request");
        let (signature, _) =
            DetachedSignature::from_reader_single(Cursor::new(signature)).expect("parse signature");
        let (certificate, _) =
            SignedPublicKey::from_reader_single(Cursor::new(material.public_key_armored.clone()))
                .expect("parse public certificate");
        let signing_key = certificate
            .public_subkeys
            .iter()
            .find(|subkey| subkey.key.algorithm().can_sign())
            .expect("signing subkey");
        signature
            .verify(&signing_key.key, content)
            .expect("verify signature");
    }

    #[test]
    fn import_preserves_third_party_certification_and_direct_signature() {
        let material = generated_modern_material();
        let certifier_material = generated_modern_material();
        let (mut target, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse target secret key");
        let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(
            certifier_material.private_key_armored.as_slice(),
        ))
        .expect("parse certifier secret key");
        let signer = SigningKeyRef(&certifier.primary_key);
        let password = Password::empty();

        let certification = signature_config(SignatureType::CertPositive, &signer, TEST_TIME + 1)
            .sign_certification_third_party(
                &signer,
                &password,
                target.primary_key.public_key(),
                Tag::UserId,
                &target.details.users[0].id,
            )
            .expect("create third-party certification");
        certification
            .verify_third_party_certification(
                target.primary_key.public_key(),
                certifier.primary_key.public_key(),
                Tag::UserId,
                &target.details.users[0].id,
            )
            .expect("verify third-party certification");
        target.details.users[0]
            .signatures
            .push(certification.clone());

        let direct = signature_config(SignatureType::Key, &signer, TEST_TIME + 2)
            .sign_key(&signer, &password, target.primary_key.public_key())
            .expect("create third-party Direct Key signature");
        direct
            .verify_key_third_party(
                target.primary_key.public_key(),
                certifier.primary_key.public_key(),
            )
            .expect("verify third-party Direct Key signature");
        target.details.direct_signatures.push(direct.clone());

        assert!(target.verify_bindings().is_err());
        let imported = imported_material(import_secret(&target));
        let (imported_secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            imported.private_key_armored.as_slice(),
        ))
        .expect("parse imported secret key");
        let (imported_public, _) = SignedPublicKey::from_reader_single(Cursor::new(
            imported.public_key_armored.as_slice(),
        ))
        .expect("parse imported public key");

        assert!(
            imported_secret.details.users[0]
                .signatures
                .contains(&certification)
        );
        assert!(
            imported_public.details.users[0]
                .signatures
                .contains(&certification)
        );
        assert!(imported_secret.details.direct_signatures.contains(&direct));
        assert!(imported_public.details.direct_signatures.contains(&direct));
    }

    #[test]
    fn import_uses_valid_binding_when_newer_foreign_binding_is_present() {
        let material = generated_modern_material();
        let certifier_material = generated_modern_material();
        let (mut target, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse target secret key");
        let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(
            certifier_material.private_key_armored.as_slice(),
        ))
        .expect("parse certifier secret key");
        let signer = SigningKeyRef(&certifier.primary_key);
        let subkey = target.secret_subkeys[0].key.public_key().clone();
        let foreign_binding =
            signature_config(SignatureType::SubkeyBinding, &signer, TEST_TIME + 10)
                .sign_subkey_binding(
                    &signer,
                    target.primary_key.public_key(),
                    &Password::empty(),
                    &subkey,
                )
                .expect("create foreign subkey binding");
        assert!(
            foreign_binding
                .verify_subkey_binding(target.primary_key.public_key(), &subkey)
                .is_err()
        );
        target.secret_subkeys[0]
            .signatures
            .push(foreign_binding.clone());

        assert!(target.verify_bindings().is_err());
        let imported = imported_material(import_secret(&target));
        let (imported_secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            imported.private_key_armored.as_slice(),
        ))
        .expect("parse imported secret key");
        assert!(
            imported_secret.secret_subkeys[0]
                .signatures
                .contains(&foreign_binding)
        );
    }

    #[test]
    fn import_preserves_unresolved_designated_revoker_signature() {
        let material = generated_modern_material();
        let revoker_material = generated_modern_material();
        let (mut target, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse target secret key");
        let (revoker, _) = SignedSecretKey::from_reader_single(Cursor::new(
            revoker_material.private_key_armored.as_slice(),
        ))
        .expect("parse revoker secret key");
        let target_signer = SigningKeyRef(&target.primary_key);
        let revoker_signer = SigningKeyRef(&revoker.primary_key);
        let password = Password::empty();

        let mut declaration = signature_config(SignatureType::Key, &target_signer, TEST_TIME + 1);
        declaration.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
                RevocationKeyClass::Default,
                revoker.primary_key.algorithm(),
                revoker.primary_key.fingerprint().as_bytes(),
            )))
            .expect("revocation key subpacket"),
        );
        let declaration = declaration
            .sign_key(&target_signer, &password, target.primary_key.public_key())
            .expect("create revoker declaration");
        declaration
            .verify_key(target.primary_key.public_key())
            .expect("verify revoker declaration");
        target.details.direct_signatures.push(declaration.clone());

        let revocation =
            signature_config(SignatureType::KeyRevocation, &revoker_signer, TEST_TIME + 2)
                .sign_key(&revoker_signer, &password, target.primary_key.public_key())
                .expect("create designated revocation");
        revocation
            .verify_key_third_party(
                target.primary_key.public_key(),
                revoker.primary_key.public_key(),
            )
            .expect("verify designated revocation");
        target
            .details
            .revocation_signatures
            .push(revocation.clone());

        assert!(target.verify_bindings().is_err());
        let imported = imported_material(import_secret(&target));
        let (imported_secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            imported.private_key_armored.as_slice(),
        ))
        .expect("parse imported secret key");
        assert!(
            imported_secret
                .details
                .direct_signatures
                .contains(&declaration)
        );
        assert!(
            imported_secret
                .details
                .revocation_signatures
                .contains(&revocation)
        );
    }

    #[test]
    fn import_preserves_but_quarantines_foreign_only_identity_and_unbound_subkey() {
        let material = generated_modern_material();
        let certifier_material = generated_modern_material();
        let (mut foreign_only, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse target secret key");
        let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(
            certifier_material.private_key_armored.as_slice(),
        ))
        .expect("parse certifier secret key");
        let signer = SigningKeyRef(&certifier.primary_key);
        let certification = signature_config(SignatureType::CertPositive, &signer, TEST_TIME + 1)
            .sign_certification_third_party(
                &signer,
                &Password::empty(),
                foreign_only.primary_key.public_key(),
                Tag::UserId,
                &foreign_only.details.users[0].id,
            )
            .expect("create foreign-only certification");
        foreign_only.details.users[0].signatures = vec![certification];
        let foreign_input = foreign_only
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor foreign-only key");
        let foreign_imported = imported_material(import_secret(&foreign_only));
        let foreign_input_packets = RawPacketStream::parse(&foreign_input, MAX_OPENPGP_PACKETS)
            .expect("scan foreign-only input");
        let foreign_output_packets =
            RawPacketStream::parse(&foreign_imported.private_key_armored, MAX_OPENPGP_PACKETS)
                .expect("scan foreign-only output");
        assert_eq!(
            foreign_input_packets.bytes(),
            foreign_output_packets.bytes()
        );
        let (foreign_output, _) =
            SignedSecretKey::from_reader_single(Cursor::new(&foreign_imported.private_key_armored))
                .expect("parse foreign-only output");
        let mut budget = OpenPgpReadBudget::default();
        let foreign_public = foreign_output.to_public_key();
        let foreign_candidates = all_components(std::slice::from_ref(&foreign_public));
        let foreign_policy =
            inspect_certificate(&foreign_public, &foreign_candidates, TEST_TIME, &mut budget)
                .expect("inspect foreign-only output");
        assert!(!foreign_policy.primary.authenticated);

        let (mut unbound, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse target secret key again");
        unbound.secret_subkeys[0].signatures = unbound.secret_subkeys[1].signatures.clone();
        let unbound_input = unbound
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor unbound key");
        let unbound_imported = imported_material(import_secret(&unbound));
        let unbound_input_packets = RawPacketStream::parse(&unbound_input, MAX_OPENPGP_PACKETS)
            .expect("scan unbound input");
        let unbound_output_packets =
            RawPacketStream::parse(&unbound_imported.private_key_armored, MAX_OPENPGP_PACKETS)
                .expect("scan unbound output");
        assert_eq!(
            unbound_input_packets.bytes(),
            unbound_output_packets.bytes()
        );
        let (unbound_output, _) =
            SignedSecretKey::from_reader_single(Cursor::new(&unbound_imported.private_key_armored))
                .expect("parse unbound output");
        let mut budget = OpenPgpReadBudget::default();
        let unbound_public = unbound_output.to_public_key();
        let unbound_candidates = all_components(std::slice::from_ref(&unbound_public));
        let unbound_policy =
            inspect_certificate(&unbound_public, &unbound_candidates, TEST_TIME, &mut budget)
                .expect("inspect unbound output");
        assert!(!unbound_policy.subkeys[0].authenticated);
    }

    #[test]
    fn import_preserves_signatureless_v4_and_v6_primaries_without_authorizing_use() {
        let v4 = generated_rsa_material();
        let v6 = SecretKeyParamsBuilder::default()
            .version(KeyVersion::V6)
            .key_type(KeyType::Ed25519)
            .can_certify(true)
            .can_sign(true)
            .passphrase(None)
            .build()
            .expect("build v6 key parameters")
            .generate(AwsLcRng)
            .expect("generate v6 key")
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor v6 key");

        for input in [v4.private_key_armored.clone(), v6] {
            let packets =
                RawPacketStream::parse(&input, MAX_OPENPGP_PACKETS).expect("scan generated key");
            let range = packets
                .first_secret_certificate()
                .expect("find generated secret key");
            let primary = packets
                .packets()
                .get(range.start)
                .expect("primary secret packet");
            let primary_only = packets.raw(primary).to_vec();
            let primary_only_armored = armor_key_packets(&primary_only, BlockType::PrivateKey)
                .expect("armor signatureless key");
            let imported = imported_material(import_key_data(primary_only_armored));
            let imported_private =
                RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
                    .expect("scan imported signatureless key");
            assert_eq!(imported_private.bytes(), primary_only);
            let imported_public =
                RawPacketStream::parse(&imported.public_key_armored, MAX_OPENPGP_PACKETS)
                    .expect("scan imported signatureless public key");
            assert_eq!(imported_public.packets().len(), 1);
            assert_eq!(imported_public.packets()[0].tag(), 6);
            assert_eq!(resolved_metadata(&imported), None);
        }
    }

    #[test]
    fn import_preserves_unsigned_secret_subkeys_without_advertising_them() {
        let material = generated_modern_material();
        let packets = RawPacketStream::parse(&material.private_key_armored, MAX_OPENPGP_PACKETS)
            .expect("scan generated key");
        let range = packets
            .first_secret_certificate()
            .expect("find generated secret key");
        let mut unsigned = Vec::new();
        let mut after_subkey = false;
        for packet in &packets.packets()[range] {
            match packet.tag() {
                7 => {
                    after_subkey = true;
                    unsigned.extend_from_slice(packets.raw(packet));
                }
                2 if after_subkey => {}
                _ => {
                    after_subkey = false;
                    unsigned.extend_from_slice(packets.raw(packet));
                }
            }
        }
        let unsigned_count = RawPacketStream::parse(&unsigned, MAX_OPENPGP_PACKETS)
            .expect("scan unsigned key")
            .packets()
            .iter()
            .filter(|packet| packet.tag() == 7)
            .count();
        assert!(unsigned_count > 0);

        let armored =
            armor_key_packets(&unsigned, BlockType::PrivateKey).expect("armor unsigned-subkey key");
        let imported = imported_material(import_key_data(armored));
        let imported_private =
            RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
                .expect("scan imported private key");
        assert_eq!(imported_private.bytes(), unsigned);
        let imported_public =
            RawPacketStream::parse(&imported.public_key_armored, MAX_OPENPGP_PACKETS)
                .expect("scan imported public key");
        assert_eq!(
            imported_public
                .packets()
                .iter()
                .filter(|packet| packet.tag() == 14)
                .count(),
            unsigned_count,
        );
        let metadata = resolved_metadata(&imported).expect("authenticated primary metadata");
        assert_eq!(metadata.keys.len(), 1);
        assert_eq!(metadata.keys[0].fingerprint, imported.fingerprint);
    }

    #[test]
    fn import_preserves_unknown_noncritical_packets_but_rejects_unknown_critical_packets() {
        let material = generated_modern_material();
        let packets = RawPacketStream::parse(&material.private_key_armored, MAX_OPENPGP_PACKETS)
            .expect("scan generated key");
        let range = packets
            .first_secret_certificate()
            .expect("find generated key");
        let mut with_noncritical = Vec::new();
        for (position, packet) in packets.packets()[range.clone()].iter().enumerate() {
            with_noncritical.extend_from_slice(packets.raw(packet));
            if position == 0 {
                with_noncritical.extend_from_slice(&[0xe8, 0x03, 0x01, 0x02, 0x03]);
            }
        }
        let imported = imported_material(import_key_data(
            armor_key_packets(&with_noncritical, BlockType::PrivateKey)
                .expect("armor key with noncritical packet"),
        ));
        let imported_private =
            RawPacketStream::parse(&imported.private_key_armored, MAX_OPENPGP_PACKETS)
                .expect("scan imported key with noncritical packet");
        assert_eq!(imported_private.bytes(), with_noncritical);
        assert!(
            imported_private
                .packets()
                .iter()
                .any(|packet| packet.tag() == 40)
        );
        assert!(resolved_metadata(&imported).is_some());

        let mut with_critical = Vec::new();
        for (position, packet) in packets.packets()[range].iter().enumerate() {
            with_critical.extend_from_slice(packets.raw(packet));
            if position == 0 {
                with_critical.extend_from_slice(&[0xd6, 0x00]);
            }
        }
        let rejected = import_key_data(
            armor_key_packets(&with_critical, BlockType::PrivateKey)
                .expect("armor key with critical packet"),
        );
        assert!(matches!(
            rejected.result,
            Some(open_pgp_key_import_result::Result::Error(error))
                if error.reason == OpenPgpKeyImportErrorReason::MalformedKey as i32
        ));
    }

    #[test]
    fn import_accepts_expired_transferable_secret_key() {
        let material = OpenPgpKeyMaterial::decode(
            generate_key_request(OpenPgpKeyGenerateRequest {
                kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
                user_id: "Expired Example <expired@example.test>".to_owned(),
                rsa_bits: 0,
                creation_time_epoch_seconds: TEST_TIME,
                expiration_seconds: Some(1),
            })
            .expect("generate expired certificate")
            .as_slice(),
        )
        .expect("decode expired material");
        let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse expired secret key");
        assert!(matches!(
            import_secret(&secret).result,
            Some(open_pgp_key_import_result::Result::Success(_))
        ));
    }

    #[test]
    fn import_accepts_missing_backsig_but_signing_rejects_subkey() {
        let material = generated_modern_material();
        let (mut secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated secret key");
        let primary = &secret.primary_key;
        let signing_subkey = secret.secret_subkeys[0].key.public_key().clone();
        let binding = subkey_binding_signature(
            SigningKeyRef(primary),
            primary.public_key(),
            &signing_subkey,
            &Password::empty(),
            Timestamp::from_secs(TEST_TIME as u32),
            None,
            true,
            None,
        )
        .expect("create signing binding without back-signature");
        secret.secret_subkeys[0].signatures = vec![binding];

        assert!(matches!(
            import_secret(&secret).result,
            Some(open_pgp_key_import_result::Result::Success(_))
        ));
        let private_key = secret
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor key without back-signature");
        assert_eq!(
            sign_request(OpenPgpSignRequest {
                kind: OpenPgpSignKind::Detached as i32,
                content: b"must not sign".to_vec(),
                private_key,
                preferred_fingerprint: material.fingerprint.clone(),
                armored: false,
                signature_time_epoch_seconds: Some(TEST_TIME + 1),
                reference_time_epoch_seconds: Some(TEST_TIME + 1),
            })
            .expect_err("missing back-signature must prevent signing"),
            OpenPgpWriteError::MissingKey,
        );
    }

    #[test]
    fn message_decryption_quarantines_unbound_secret_subkeys() {
        let material = generated_modern_material();
        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: b"bound recipient only".to_vec(),
                public_keys: vec![material.public_key_armored.clone()],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "quarantine.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME),
                reference_time_epoch_seconds: Some(TEST_TIME),
                enable_compression: None,
            })
            .expect("encrypt to bound subkey")
            .as_slice(),
        )
        .expect("decode encrypted message");
        let (mut unbound, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated key");
        unbound.secret_subkeys[1].signatures = unbound.secret_subkeys[0].signatures.clone();
        assert_eq!(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data,
                private_keys: vec![
                    unbound
                        .to_armored_bytes(ArmorOptions::default())
                        .expect("armor unbound key"),
                ],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME),
                allow_signed_only: None,
            }),
            Err(OpenPgpWriteError::MissingKey),
        );
    }

    #[test]
    fn message_decryption_rejects_current_sign_only_subkeys() {
        let material = generated_modern_material();
        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: b"sign-only recipient must be rejected".to_vec(),
                public_keys: vec![material.public_key_armored.clone()],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "sign-only.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME),
                reference_time_epoch_seconds: Some(TEST_TIME),
                enable_compression: None,
            })
            .expect("encrypt to bound encryption subkey")
            .as_slice(),
        )
        .expect("decode encrypted message");

        let (mut sign_only, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated key");
        let primary = &sign_only.primary_key;
        let target = sign_only.secret_subkeys[1].key.public_key().clone();
        let binding = subkey_binding_signature(
            SigningKeyRef(primary),
            primary.public_key(),
            &target,
            &Password::empty(),
            Timestamp::from_secs(TEST_TIME as u32),
            None,
            true,
            None,
        )
        .expect("create current sign-only binding");
        sign_only.secret_subkeys[1].signatures = vec![binding];

        assert_eq!(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data,
                private_keys: vec![
                    sign_only
                        .to_armored_bytes(ArmorOptions::default())
                        .expect("armor sign-only key"),
                ],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME),
                allow_signed_only: None,
            }),
            Err(OpenPgpWriteError::MissingKey),
        );
    }

    #[test]
    fn expired_newer_binding_does_not_shadow_live_cross_certified_binding() {
        let material = generated_modern_material();
        let (mut secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated secret key");
        let newer = subkey_binding_with_signature_expiration(&secret, 0, TEST_TIME + 10, 1, false);
        secret.secret_subkeys[0].signatures.push(newer);

        let public = secret.to_public_key();
        let candidates = all_components(std::slice::from_ref(&public));
        let component_index = secret.public_subkeys.len();
        let inspect_at = |reference_time| {
            let mut budget = OpenPgpReadBudget::default();
            inspect_certificate(&public, &candidates, reference_time, &mut budget)
                .expect("inspect certificate")
        };

        let current = inspect_at(TEST_TIME + 10);
        let current = &current.subkeys[component_index];
        assert_eq!(
            current
                .effective_signature
                .and_then(pgp::packet::Signature::created)
                .map(Timestamp::as_secs),
            Some((TEST_TIME + 10) as u32),
        );
        assert!(!current.signing_cross_certified);

        let after_expiration = inspect_at(TEST_TIME + 12);
        let after_expiration = &after_expiration.subkeys[component_index];
        assert_eq!(
            after_expiration
                .effective_signature
                .and_then(pgp::packet::Signature::created)
                .map(Timestamp::as_secs),
            Some(TEST_TIME as u32),
        );
        assert!(after_expiration.signing_cross_certified);
        assert!(signing_component_usable(
            after_expiration,
            TEST_TIME + 12,
            true,
        ));
    }

    #[test]
    fn import_accepts_signature_expired_subkey_binding() {
        let material = generated_modern_material();
        let (mut secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated secret key");
        let expired = subkey_binding_with_signature_expiration(&secret, 1, TEST_TIME - 10, 1, true);
        secret.secret_subkeys[1].signatures = vec![expired];

        assert!(matches!(
            import_secret(&secret).result,
            Some(open_pgp_key_import_result::Result::Success(_))
        ));
    }

    #[test]
    fn generated_certificate_stream_signature_verifies_through_read_policy() {
        let material = generated_modern_material();
        let content = b"streamed OpenPGP detached signature";
        let mut session = DetachedSigningSession::open(OpenPgpDetachedSignStreamOpenRequest {
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: material.fingerprint.clone(),
            armored: false,
            signature_time_epoch_seconds: Some(TEST_TIME + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("open detached signing session");
        session.update(&content[..11]).expect("first signing chunk");
        session
            .update(&content[11..])
            .expect("second signing chunk");
        let signature = session.finish().expect("finish detached signature");
        let verification = OpenPgpVerification::decode(
            crate::openpgp_read::verify_request(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::Detached as i32,
                content: content.to_vec(),
                signature,
                public_keys: vec![material.public_key_armored.clone()],
                reference_time_epoch_seconds: Some(TEST_TIME + 2),
            })
            .expect("verify streamed detached signature")
            .as_slice(),
        )
        .expect("decode verification");
        assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32);
    }

    #[test]
    fn rsa_generation_signing_and_decryption_use_the_sensitive_adapter() {
        let material = generated_rsa_material();
        let content = b"AWS-LC RSA OpenPGP adapter";
        let signature = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::Detached as i32,
            content: content.to_vec(),
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            armored: false,
            signature_time_epoch_seconds: Some(TEST_TIME + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("RSA detached signature");
        let (signature, _) = DetachedSignature::from_reader_single(Cursor::new(signature))
            .expect("parse RSA signature");
        let (certificate, _) =
            SignedPublicKey::from_reader_single(Cursor::new(material.public_key_armored.clone()))
                .expect("parse RSA certificate");
        let signing_key = certificate
            .public_subkeys
            .iter()
            .find(|subkey| subkey.key.algorithm().can_sign())
            .expect("RSA signing subkey");
        signature
            .verify(&signing_key.key, content)
            .expect("verify RSA signature");

        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: content.to_vec(),
                public_keys: vec![material.public_key_armored.clone()],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "rsa.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME + 1),
                reference_time_epoch_seconds: Some(TEST_TIME + 1),
                enable_compression: None,
            })
            .expect("RSA recipient encryption")
            .as_slice(),
        )
        .expect("decode RSA encryption");
        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data,
                private_keys: vec![material.private_key_armored.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME + 1),
                allow_signed_only: None,
            })
            .expect("AWS-LC RSA PKESK decryption")
            .as_slice(),
        )
        .expect("decode RSA decryption");
        assert_eq!(decrypted.data, content);
    }

    #[test]
    fn import_reports_passphrase_and_public_only_outcomes() {
        let material = generated_modern_material();
        let (mut protected, _) =
            SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.clone()))
                .expect("parse generated secret key");
        let password = Password::from("correct horse battery staple");
        protected
            .primary_key
            .set_password(AwsLcRng, &password)
            .expect("protect primary key");
        for subkey in &mut protected.secret_subkeys {
            subkey
                .key
                .set_password(AwsLcRng, &password)
                .expect("protect secret subkey");
        }
        let protected = protected
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor protected key");

        let import = |key_data: Vec<u8>, passphrase_utf8: Option<Vec<u8>>| {
            OpenPgpKeyImportResult::decode(
                import_key_request(OpenPgpKeyImportRequest {
                    key_data,
                    passphrase_utf8,
                    reference_time_epoch_seconds: Some(TEST_TIME),
                })
                .expect("import request")
                .as_slice(),
            )
            .expect("decode import result")
        };
        assert!(matches!(
            import(protected.clone(), None).result,
            Some(open_pgp_key_import_result::Result::NeedsPassphrase(_))
        ));
        assert!(matches!(
            import(protected.clone(), Some(b"wrong".to_vec())).result,
            Some(open_pgp_key_import_result::Result::Error(error))
                if error.reason == OpenPgpKeyImportErrorReason::InvalidPassphrase as i32
        ));
        assert!(matches!(
            import(protected, Some(b"correct horse battery staple".to_vec())).result,
            Some(open_pgp_key_import_result::Result::Success(_))
        ));
        assert!(matches!(
            import(material.public_key_armored.clone(), None).result,
            Some(open_pgp_key_import_result::Result::Error(error))
                if error.reason == OpenPgpKeyImportErrorReason::UnsupportedFormat as i32
        ));
    }

    fn malleable_cfb_params<K>(
        plain: &PlainSecretParams,
        public: &K,
        passphrase: &[u8],
        seed: u8,
    ) -> EncryptedSecretParams
    where
        K: KeyDetails + Serialize,
    {
        let sym_alg = SymmetricKeyAlgorithm::AES256;
        let s2k = StringToKey::IteratedAndSalted {
            hash_alg: HashAlgorithm::Sha256,
            salt: [seed; 8],
            count: 0x60,
        };
        let iv = vec![seed.wrapping_add(1); sym_alg.block_size()];
        let derived = s2k
            .derive_key(passphrase, sym_alg.key_size())
            .expect("derive test S2K");
        let mut data = Zeroizing::new(Vec::new());
        plain
            .to_writer(&mut *data, public.version())
            .expect("serialize plain secret with simple checksum");
        sym_alg
            .encrypt_with_iv_regular(derived.as_ref(), &iv, &mut data)
            .expect("encrypt test secret");
        EncryptedSecretParams::new(
            data.to_vec().into(),
            S2kParams::MalleableCfb {
                sym_alg,
                s2k,
                iv: iv.into(),
            },
        )
    }

    #[test]
    fn import_accepts_bc_legacy_malleable_cfb_and_removes_password() {
        let material = generated_modern_material();
        let (mut protected, _) =
            SignedSecretKey::from_reader_single(Cursor::new(material.private_key_armored.clone()))
                .expect("parse generated secret key");
        let passphrase = b"correct horse battery staple";

        let primary_public = protected.primary_key.public_key().clone();
        let primary_plain = match protected.primary_key.secret_params() {
            SecretParams::Plain(plain) => plain.clone(),
            SecretParams::Encrypted(_) => panic!("generated primary is unprotected"),
        };
        let primary_encrypted =
            malleable_cfb_params(&primary_plain, &primary_public, passphrase, 1);
        protected.primary_key =
            SecretKey::new(primary_public, SecretParams::Encrypted(primary_encrypted))
                .expect("protect primary");

        for (index, subkey) in protected.secret_subkeys.iter_mut().enumerate() {
            let public = subkey.key.public_key().clone();
            let plain = match subkey.key.secret_params() {
                SecretParams::Plain(plain) => plain.clone(),
                SecretParams::Encrypted(_) => panic!("generated subkey is unprotected"),
            };
            let encrypted = malleable_cfb_params(&plain, &public, passphrase, index as u8 + 2);
            subkey.key = SecretSubkey::new(public, SecretParams::Encrypted(encrypted))
                .expect("protect subkey");
        }
        let protected = protected
            .to_armored_bytes(ArmorOptions::default())
            .expect("armor usage-255 key");
        let raw =
            RawPacketStream::parse(&protected, MAX_OPENPGP_PACKETS).expect("scan usage-255 key");
        let range = raw.first_secret_certificate().expect("find usage-255 key");
        for span in raw.packets()[range]
            .iter()
            .filter(|span| matches!(span.tag(), 5 | 7))
        {
            let secret = parse_import_secret_packet(&raw, span).expect("parse secret packet");
            let body = raw.body(span);
            assert_eq!(body.get(secret.public_len()), Some(&255));
        }

        let import = |password: &[u8]| {
            OpenPgpKeyImportResult::decode(
                import_key_request(OpenPgpKeyImportRequest {
                    key_data: protected.clone(),
                    passphrase_utf8: Some(password.to_vec()),
                    reference_time_epoch_seconds: Some(TEST_TIME),
                })
                .expect("typed import result")
                .as_slice(),
            )
            .expect("decode import result")
        };
        assert!(matches!(
            import(b"wrong").result,
            Some(open_pgp_key_import_result::Result::Error(error))
                if error.reason == OpenPgpKeyImportErrorReason::InvalidPassphrase as i32
        ));
        let success = match import(passphrase).result {
            Some(open_pgp_key_import_result::Result::Success(success)) => success,
            result => panic!("expected successful compatibility import, got {result:?}"),
        };
        let imported = success.key_material.expect("imported key material");
        let (passwordless, _) =
            SignedSecretKey::from_reader_single(Cursor::new(imported.private_key_armored.clone()))
                .expect("parse passwordless import");
        assert!(!passwordless.primary_key.secret_params().is_encrypted());
        assert!(
            passwordless
                .secret_subkeys
                .iter()
                .all(|subkey| !subkey.key.secret_params().is_encrypted())
        );
    }

    #[test]
    fn clear_sign_canonicalizes_marker_lines_and_trailing_whitespace() {
        let material = generated_modern_material();
        let content = b"- leading dash\n - space-indented dash\n\t- tab-indented dash\n-----BEGIN PGP SIGNATURE-----\n-----BEGIN PGP SIGNED MESSAGE-----\ntrailing whitespace here   ";
        let signed = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::ClearText as i32,
            content: content.to_vec(),
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            armored: true,
            signature_time_epoch_seconds: Some(TEST_TIME + 1),
            reference_time_epoch_seconds: Some(TEST_TIME + 1),
        })
        .expect("clear sign marker lines");
        assert!(
            signed
                .windows(b"- -----BEGIN PGP SIGNATURE-----".len())
                .any(|window| window == b"- -----BEGIN PGP SIGNATURE-----")
        );
        assert!(
            signed
                .windows(b"\n - space-indented dash\n\t- tab-indented dash\n".len())
                .any(|window| window == b"\n - space-indented dash\n\t- tab-indented dash\n")
        );
        let verification = OpenPgpVerification::decode(
            crate::openpgp_read::verify_request(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content: signed.to_vec(),
                signature: Vec::new(),
                public_keys: vec![material.public_key_armored.clone()],
                reference_time_epoch_seconds: Some(TEST_TIME + 1),
            })
            .expect("verify clear signature")
            .as_slice(),
        )
        .expect("decode verification");
        assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32);
    }

    #[test]
    fn expired_primary_blocks_signing_and_recipient_selection() {
        let material = OpenPgpKeyMaterial::decode(
            generate_key_request(OpenPgpKeyGenerateRequest {
                kind: OpenPgpKeyKind::LegacyEd25519X25519 as i32,
                user_id: "Expired Example <expired@example.test>".to_owned(),
                rsa_bits: 0,
                creation_time_epoch_seconds: TEST_TIME,
                expiration_seconds: Some(1),
            })
            .expect("generate expiring certificate")
            .as_slice(),
        )
        .expect("decode expiring certificate");
        assert_eq!(
            sign_request(OpenPgpSignRequest {
                kind: OpenPgpSignKind::Detached as i32,
                content: b"expired".to_vec(),
                private_key: material.private_key_armored.clone(),
                preferred_fingerprint: String::new(),
                armored: false,
                signature_time_epoch_seconds: Some(TEST_TIME + 2),
                reference_time_epoch_seconds: Some(TEST_TIME + 2),
            })
            .expect_err("expired primary cannot sign"),
            OpenPgpWriteError::MissingKey
        );
        assert_eq!(
            encrypt_request(OpenPgpEncryptRequest {
                content: b"expired".to_vec(),
                public_keys: vec![material.public_key_armored.clone()],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "expired.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME + 2),
                reference_time_epoch_seconds: Some(TEST_TIME + 2),
                enable_compression: None,
            })
            .expect_err("expired primary cannot receive"),
            OpenPgpWriteError::MissingKey
        );
    }

    #[test]
    fn legacy_recipients_are_strict_but_legacy_decrypt_candidates_are_skipped() {
        let material = generated_modern_material();
        let legacy_public = include_bytes!("../tests/fixtures/openpgp/v3-public.asc").to_vec();
        let legacy_secret_bytes =
            include_bytes!("../tests/fixtures/openpgp/v3-secret.asc").to_vec();
        let legacy_import = OpenPgpKeyImportResult::decode(
            import_key_request(OpenPgpKeyImportRequest {
                key_data: legacy_secret_bytes.clone(),
                passphrase_utf8: None,
                reference_time_epoch_seconds: Some(TEST_TIME),
            })
            .expect("legacy import returns typed result")
            .as_slice(),
        )
        .expect("decode legacy import result");
        assert!(matches!(
            legacy_import.result,
            Some(open_pgp_key_import_result::Result::Error(error))
                if error.reason == OpenPgpKeyImportErrorReason::UnsupportedFormat as i32
        ));
        let request = |public_keys| OpenPgpEncryptRequest {
            content: b"legacy policy".to_vec(),
            public_keys,
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "legacy.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME),
            reference_time_epoch_seconds: Some(TEST_TIME),
            enable_compression: None,
        };
        assert_eq!(
            encrypt_request(request(vec![legacy_public.clone()])),
            Err(OpenPgpWriteError::UnsupportedKeyVersion(3))
        );
        assert_eq!(
            encrypt_request(request(vec![
                material.public_key_armored.clone(),
                legacy_public,
            ])),
            Err(OpenPgpWriteError::UnsupportedKeyVersion(3))
        );

        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(request(vec![material.public_key_armored.clone()]))
                .expect("modern recipient encryption")
                .as_slice(),
        )
        .expect("decode modern encryption");
        let modern_secret = Zeroizing::new(material.private_key_armored.clone());
        let candidates = vec![Zeroizing::new(legacy_secret_bytes.clone()), modern_secret];
        assert_eq!(
            parse_secret_key_candidates(&candidates)
                .expect("mixed candidates")
                .len(),
            1
        );
        let legacy_only = vec![Zeroizing::new(
            include_bytes!("../tests/fixtures/openpgp/v3-secret.asc").to_vec(),
        )];
        assert_eq!(
            parse_secret_key_candidates(&legacy_only),
            Err(OpenPgpWriteError::UnsupportedKeyVersion(3))
        );
        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data,
                private_keys: vec![legacy_secret_bytes, material.private_key_armored.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME),
                allow_signed_only: None,
            })
            .expect("mixed-candidate decryption")
            .as_slice(),
        )
        .expect("decode mixed-candidate decryption");
        assert_eq!(decrypted.data, b"legacy policy");
    }

    #[test]
    fn legacy_v2_secret_packet_is_unsupported_but_truncation_is_malformed() {
        let mut legacy_v2 = RawPacketStream::parse(
            include_bytes!("../tests/fixtures/openpgp/v3-secret.asc"),
            MAX_OPENPGP_PACKETS,
        )
        .expect("decode checked-in v3 secret fixture")
        .bytes()
        .to_vec();
        assert_eq!(legacy_v2.first().copied(), Some(0x95));
        assert_eq!(legacy_v2.get(3).copied(), Some(3));
        legacy_v2[3] = 2;

        let import = |key_data: Vec<u8>| {
            OpenPgpKeyImportResult::decode(
                import_key_request(OpenPgpKeyImportRequest {
                    key_data,
                    passphrase_utf8: None,
                    reference_time_epoch_seconds: Some(TEST_TIME),
                })
                .expect("import returns a typed result")
                .as_slice(),
            )
            .expect("decode import result")
        };
        let legacy_result = import(legacy_v2.to_vec());
        assert!(matches!(
            legacy_result.result,
            Some(open_pgp_key_import_result::Result::Error(error))
                if error.reason == OpenPgpKeyImportErrorReason::UnsupportedFormat as i32
        ));

        let truncated_result = import(legacy_v2[..3].to_vec());
        assert!(matches!(
            truncated_result.result,
            Some(open_pgp_key_import_result::Result::Error(error))
                if error.reason == OpenPgpKeyImportErrorReason::MalformedKey as i32
        ));
    }

    #[test]
    fn gnupg_ocb_encrypt_decrypt_and_authentication_failure() {
        let material = generated_modern_material();
        let plaintext = b"preference-gated GnuPG OCB payload";
        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: plaintext.to_vec(),
                public_keys: vec![material.public_key_armored.clone()],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "_CONSOLE".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME + 2),
                reference_time_epoch_seconds: Some(TEST_TIME + 2),
                enable_compression: None,
            })
            .expect("encrypt request")
            .as_slice(),
        )
        .expect("decode encryption result");
        assert_eq!(
            encrypted.protection_mode,
            OpenPgpProtectionMode::GnupgOcb as i32
        );

        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data.clone(),
                private_keys: vec![material.private_key_armored.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME + 2),
                allow_signed_only: None,
            })
            .expect("decrypt request")
            .as_slice(),
        )
        .expect("decode decryption result");
        assert_eq!(decrypted.data, plaintext);
        assert!(decrypted.verification.is_none());

        let mut tampered = encrypted.data;
        *tampered.last_mut().expect("encrypted body") ^= 1;
        let error = decrypt_request(OpenPgpDecryptRequest {
            content: tampered,
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 2),
            allow_signed_only: None,
        })
        .expect_err("tampered OCB must fail");
        assert_eq!(error, OpenPgpWriteError::AuthenticationFailed);
    }

    #[test]
    fn seipd_v1_mdc_roundtrip() {
        let material = generated_modern_material();
        let (certificate, _) =
            SignedPublicKey::from_reader_single(Cursor::new(material.public_key_armored.clone()))
                .expect("parse public certificate");
        let recipient = certificate
            .public_subkeys
            .iter()
            .find(|subkey| subkey.key.algorithm().can_encrypt())
            .map(|subkey| PublicComponent::Subkey(subkey.key.clone()))
            .expect("encryption subkey");
        let plaintext = b"AES-256 SEIPDv1 MDC payload";
        let composed = build_composed_message(
            plaintext,
            b"_CONSOLE",
            Timestamp::from_secs(TEST_TIME as u32),
            None,
            true,
        )
        .expect("compose message");
        let encrypted = encrypt_composed_message(
            composed.as_slice(),
            &[recipient],
            OpenPgpProtectionMode::SeipdV1Mdc,
        )
        .expect("encrypt MDC message");
        let decrypted = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted,
                private_keys: vec![material.private_key_armored.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME),
                allow_signed_only: None,
            })
            .expect("decrypt MDC message")
            .as_slice(),
        )
        .expect("decode MDC decryption result");
        assert_eq!(decrypted.data, plaintext);
    }

    #[test]
    fn streaming_armor_matches_independent_crc24_and_base64_kat() {
        let (sender, receiver) = mpsc::sync_channel(STREAM_CHANNEL_DEPTH);
        let mut armor =
            OpenPgpArmorWriter::new(OpenPgpChannelWriter::new(sender)).expect("open armor writer");
        armor
            .write_all(b"123456789")
            .expect("write RFC CRC-24 check value");
        armor.finish().expect("finish armor");
        let mut encoded = Vec::new();
        while let Ok(message) = receiver.try_recv() {
            match message {
                OpenPgpWorkerOutput::Data(bytes) => encoded.extend_from_slice(&bytes),
                OpenPgpWorkerOutput::Consumed | OpenPgpWorkerOutput::Finished(_) => {
                    panic!("armor writer emitted a worker control message")
                }
            }
        }
        assert_eq!(
            encoded,
            b"-----BEGIN PGP MESSAGE-----\n\nMTIzNDU2Nzg5\n=Ic8C\n-----END PGP MESSAGE-----\n"
        );
    }

    #[test]
    fn streaming_clear_sign_matches_one_shot_across_utf8_and_line_boundaries() {
        let material = generated_modern_material();
        let mut content = Vec::new();
        for index in 0..8_192 {
            content.extend_from_slice(
                format!("- unicode λ line {index}\t \r\nplain line {index}\n").as_bytes(),
            );
        }
        content.extend_from_slice(b"final trailing whitespace\t ");
        let expected = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::ClearText as i32,
            content: content.clone(),
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            armored: true,
            signature_time_epoch_seconds: Some(TEST_TIME + 5),
            reference_time_epoch_seconds: Some(TEST_TIME + 5),
        })
        .expect("one-shot clear signature");
        let mut session = ClearSigningSession::open(OpenPgpClearSignStreamOpenRequest {
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            signature_time_epoch_seconds: Some(TEST_TIME + 5),
            reference_time_epoch_seconds: Some(TEST_TIME + 5),
        })
        .expect("open clear-sign stream");
        let mut actual = Vec::new();
        for_odd_chunks(&content, &[1, 2, 7, 31], |chunk| {
            actual.extend_from_slice(&session.update(chunk).expect("clear-sign update"));
        });
        actual.extend_from_slice(&session.finish().expect("finish clear-sign stream"));

        assert_eq!(actual, expected);
        assert!(actual.windows(4).any(|window| window == b"- - "));
    }

    #[test]
    fn clear_sign_pending_whitespace_limit_is_inclusive_and_atomic() {
        let material = generated_modern_material();
        let request = || OpenPgpClearSignStreamOpenRequest {
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            signature_time_epoch_seconds: Some(TEST_TIME + 5),
            reference_time_epoch_seconds: Some(TEST_TIME + 5),
        };
        let whitespace = (0..MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES)
            .map(|index| if index % 2 == 0 { b' ' } else { b'\t' })
            .collect::<Vec<_>>();

        let mut session = ClearSigningSession::open(request()).expect("open clear-sign stream");
        let mut signed = session
            .update(&whitespace[..whitespace.len() - 1])
            .expect("accept whitespace below the limit");
        let pending_before = session.pending_whitespace.to_vec();
        let utf8_tail_before = session.utf8_tail.to_vec();
        let started_before = session.started;
        let line_start_before = session.line_start;
        let canonical_needs_break_before = session.canonical_needs_break;
        let previous_input_was_cr_before = session.previous_input_was_cr;
        let output_ended_with_line_break_before = session.output_ended_with_line_break;

        assert_eq!(
            session.update(&[whitespace[whitespace.len() - 1], b' ']),
            Err(OpenPgpWriteError::ResourceLimit),
        );
        assert_eq!(session.pending_whitespace.as_slice(), pending_before);
        assert_eq!(session.utf8_tail.as_slice(), utf8_tail_before);
        assert_eq!(session.started, started_before);
        assert_eq!(session.line_start, line_start_before);
        assert_eq!(session.canonical_needs_break, canonical_needs_break_before);
        assert_eq!(session.previous_input_was_cr, previous_input_was_cr_before);
        assert_eq!(
            session.output_ended_with_line_break,
            output_ended_with_line_break_before
        );

        signed.extend_from_slice(
            &session
                .update(&[whitespace[whitespace.len() - 1], b'\n'])
                .expect("recover with an exactly-at-limit run"),
        );
        signed.extend_from_slice(
            &session
                .finish()
                .expect("finish recovered clear-sign stream"),
        );
        let verification = OpenPgpVerification::decode(
            crate::openpgp_read::verify_request(OpenPgpVerifyRequest {
                kind: OpenPgpVerifyKind::ClearText as i32,
                content: signed,
                signature: Vec::new(),
                public_keys: vec![material.public_key_armored.clone()],
                reference_time_epoch_seconds: Some(TEST_TIME + 5),
            })
            .expect("verify recovered clear signature")
            .as_slice(),
        )
        .expect("decode recovered verification");
        assert_eq!(verification.status, OpenPgpVerificationStatus::Valid as i32);

        let mixed_content = b"mixed \t \t  whitespace";
        let expected = sign_request(OpenPgpSignRequest {
            kind: OpenPgpSignKind::ClearText as i32,
            content: mixed_content.to_vec(),
            private_key: material.private_key_armored.clone(),
            preferred_fingerprint: String::new(),
            armored: true,
            signature_time_epoch_seconds: Some(TEST_TIME + 5),
            reference_time_epoch_seconds: Some(TEST_TIME + 5),
        })
        .expect("one-shot mixed-whitespace clear signature");
        let mut mixed =
            ClearSigningSession::open(request()).expect("open mixed-whitespace clear-sign stream");
        let mut actual = mixed.update(b"mixed \t ").expect("buffer mixed whitespace");
        actual.extend_from_slice(
            &mixed
                .update(b"\t  whitespace")
                .expect("flush mixed whitespace"),
        );
        actual.extend_from_slice(&mixed.finish().expect("finish mixed-whitespace stream"));
        assert_eq!(actual, expected);

        let mut fresh = ClearSigningSession::open(request()).expect("open fresh clear-sign stream");
        let too_long = vec![b' '; MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES + 1];
        assert_eq!(
            fresh.update(&too_long),
            Err(OpenPgpWriteError::ResourceLimit),
        );
        assert!(!fresh.started);
        assert!(fresh.pending_whitespace.is_empty());
        assert!(fresh.utf8_tail.is_empty());

        let mut reset = ClearSigningSession::open(request()).expect("open reset clear-sign stream");
        let _ = reset
            .update(&whitespace)
            .expect("accept an exactly-at-limit run");
        assert_eq!(
            reset.pending_whitespace.len(),
            MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES
        );
        let _ = reset.update(b"\n").expect("line break resets the run");
        assert!(reset.pending_whitespace.is_empty());
        let _ = reset
            .update(&whitespace)
            .expect("accept another run after a line break");
        assert_eq!(
            reset.pending_whitespace.len(),
            MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES
        );

        let mut content_reset =
            ClearSigningSession::open(request()).expect("open content-reset clear-sign stream");
        let _ = content_reset
            .update(&whitespace)
            .expect("accept a run before content");
        let _ = content_reset
            .update(b"x")
            .expect("non-whitespace resets the run");
        assert!(content_reset.pending_whitespace.is_empty());
    }

    #[test]
    fn allow_signed_only_rejects_unsigned_literal_messages_without_streaming_plaintext() {
        let _stream_guard = STREAM_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let plaintext = b"unsigned OpenPGP literal payload";

        for enable_compression in [false, true] {
            let unsigned = build_composed_message(
                plaintext,
                b"unsigned.txt",
                Timestamp::from_secs((TEST_TIME + 6) as u32),
                None,
                enable_compression,
            )
            .expect("compose unsigned literal message");

            assert_eq!(
                decrypt_request(OpenPgpDecryptRequest {
                    content: unsigned.to_vec(),
                    private_keys: Vec::new(),
                    verification_public_keys: Vec::new(),
                    reference_time_epoch_seconds: Some(TEST_TIME + 6),
                    allow_signed_only: Some(true),
                }),
                Err(OpenPgpWriteError::InvalidArgument),
            );

            let mut session = OpenPgpDecryptionSession::open(OpenPgpDecryptStreamOpenRequest {
                private_keys: Vec::new(),
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME + 6),
                allow_signed_only: Some(true),
            })
            .expect("open signed-only decryption stream");
            let mut provisional = Vec::new();
            let mut update_failure = None;
            for chunk in unsigned.chunks(7) {
                match session.update(chunk) {
                    Ok(data) => provisional.extend_from_slice(&data),
                    Err(error) => {
                        update_failure = Some(error);
                        break;
                    }
                }
            }
            let error = if let Some(error) = update_failure {
                error
            } else {
                session
                    .finish()
                    .expect_err("unsigned literal stream must fail")
            };

            assert_eq!(error, OpenPgpWriteError::InvalidArgument);
            assert!(provisional.is_empty());
        }
    }

    #[test]
    fn signed_only_messages_decode_with_verification_and_literal_metadata() {
        let material = generated_modern_material();
        let plaintext = b"signed-only OpenPGP payload";
        let (secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            material.private_key_armored.as_slice(),
        ))
        .expect("parse generated secret key");
        let signing_packet = select_signing_packet(&secret, &material.fingerprint, TEST_TIME + 6)
            .expect("select signing packet");

        for enable_compression in [false, true] {
            let signed = build_composed_message(
                plaintext,
                b"signed-only.txt",
                Timestamp::from_secs((TEST_TIME + 6) as u32),
                Some(signing_packet),
                enable_compression,
            )
            .expect("compose signed-only message");
            let result = OpenPgpDecryptResult::decode(
                decrypt_request(OpenPgpDecryptRequest {
                    content: signed.to_vec(),
                    private_keys: Vec::new(),
                    verification_public_keys: vec![material.public_key_armored.clone()],
                    reference_time_epoch_seconds: Some(TEST_TIME + 6),
                    allow_signed_only: Some(true),
                })
                .expect("decode signed-only message")
                .as_slice(),
            )
            .expect("decode signed-only result");

            assert_eq!(result.data, plaintext);
            assert!(!result.encrypted);
            assert!(result.decryption_key_fingerprint.is_none());
            assert_eq!(
                result.verification.as_ref().expect("verification").status,
                OpenPgpVerificationStatus::Valid as i32,
            );
            let metadata = result.metadata.as_ref().expect("literal metadata");
            assert_eq!(metadata.file_name, b"signed-only.txt");
            assert_eq!(metadata.modification_time_epoch_seconds, TEST_TIME + 6);
            assert_eq!(metadata.original_size, plaintext.len() as u64);

            let missing_key = OpenPgpDecryptResult::decode(
                decrypt_request(OpenPgpDecryptRequest {
                    content: signed.to_vec(),
                    private_keys: Vec::new(),
                    verification_public_keys: Vec::new(),
                    reference_time_epoch_seconds: Some(TEST_TIME + 6),
                    allow_signed_only: Some(true),
                })
                .expect("decode signed-only message without verification key")
                .as_slice(),
            )
            .expect("decode missing-key result");
            assert_eq!(
                missing_key
                    .verification
                    .as_ref()
                    .expect("missing-key verification")
                    .status,
                OpenPgpVerificationStatus::MissingPublicKey as i32,
            );
            assert!(missing_key.decryption_key_fingerprint.is_none());
        }
    }

    #[test]
    fn decryption_results_identify_the_successful_private_component() {
        let _stream_guard = STREAM_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let unrelated = generated_modern_material();
        let recipient = generated_modern_material();
        let (recipient_secret, _) = SignedSecretKey::from_reader_single(Cursor::new(
            recipient.private_key_armored.as_slice(),
        ))
        .expect("parse recipient secret key");
        let expected_fingerprint = fingerprint_hex(&recipient_secret.secret_subkeys[1].key);
        let plaintext = b"attribute the actual recipient";
        let encrypted = OpenPgpEncryptResult::decode(
            encrypt_request(OpenPgpEncryptRequest {
                content: plaintext.to_vec(),
                public_keys: vec![recipient.public_key_armored.clone()],
                signing_private_key: None,
                preferred_signing_fingerprint: String::new(),
                file_name: "attribution.bin".to_owned(),
                armored: false,
                literal_time_epoch_seconds: Some(TEST_TIME),
                reference_time_epoch_seconds: Some(TEST_TIME),
                enable_compression: None,
            })
            .expect("encrypt for recipient")
            .as_slice(),
        )
        .expect("decode encrypted message");

        let one_shot = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: encrypted.data.clone(),
                private_keys: vec![
                    unrelated.private_key_armored.clone(),
                    recipient.private_key_armored.clone(),
                ],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME),
                allow_signed_only: None,
            })
            .expect("decrypt with recipient after unrelated candidate")
            .as_slice(),
        )
        .expect("decode one-shot decryption result");
        assert_eq!(one_shot.data, plaintext);
        assert_eq!(
            one_shot.decryption_key_fingerprint.as_deref(),
            Some(expected_fingerprint.as_str()),
        );

        let mut rng = AwsLcRng;
        let mut anonymous_builder =
            MessageBuilder::from_bytes("anonymous.bin", plaintext.as_slice())
                .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
        anonymous_builder
            .encrypt_to_key_anonymous(&mut rng, &recipient_secret.secret_subkeys[1].public_key())
            .expect("encrypt for hidden recipient");
        let anonymous_encrypted = anonymous_builder
            .to_vec(rng)
            .expect("serialize hidden-recipient message");
        let hidden_recipient = OpenPgpDecryptResult::decode(
            decrypt_request(OpenPgpDecryptRequest {
                content: anonymous_encrypted,
                private_keys: vec![
                    unrelated.private_key_armored.clone(),
                    recipient.private_key_armored.clone(),
                ],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(TEST_TIME),
                allow_signed_only: None,
            })
            .expect("decrypt hidden-recipient message")
            .as_slice(),
        )
        .expect("decode hidden-recipient result");
        assert_eq!(hidden_recipient.data, plaintext);
        assert_eq!(
            hidden_recipient.decryption_key_fingerprint.as_deref(),
            Some(expected_fingerprint.as_str()),
        );

        let mut streaming = OpenPgpDecryptionSession::open(OpenPgpDecryptStreamOpenRequest {
            private_keys: vec![
                recipient.private_key_armored.clone(),
                unrelated.private_key_armored.clone(),
            ],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME),
            allow_signed_only: None,
        })
        .expect("open streaming decryption");
        let mut streamed_plaintext = Vec::new();
        for chunk in encrypted.data.chunks(17) {
            streamed_plaintext
                .extend_from_slice(&streaming.update(chunk).expect("stream decryption update"));
        }
        let final_result = OpenPgpDecryptFinal::decode(
            streaming
                .finish()
                .expect("finish streaming decryption")
                .as_slice(),
        )
        .expect("decode streaming decryption result");
        streamed_plaintext.extend_from_slice(&final_result.data);
        assert_eq!(streamed_plaintext, plaintext);
        assert_eq!(
            final_result.decryption_key_fingerprint.as_deref(),
            Some(expected_fingerprint.as_str()),
        );
    }

    #[test]
    fn streaming_encryption_without_compression_preserves_metadata() {
        let _stream_guard = STREAM_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let material = generated_modern_material();
        let plaintext = vec![0x5a_u8; 256 * 1024];
        let mut encryption = OpenPgpEncryptionSession::open(OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "uncompressed.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 7),
            reference_time_epoch_seconds: Some(TEST_TIME + 7),
            enable_compression: Some(false),
        })
        .expect("open uncompressed encryption stream");
        let mut encrypted = Vec::new();
        for chunk in plaintext.chunks(7_919) {
            encrypted.extend_from_slice(&encryption.update(chunk).expect("encrypt update"));
        }
        let final_output =
            OpenPgpEncryptFinal::decode(encryption.finish().expect("finish encryption").as_slice())
                .expect("decode encryption final");
        encrypted.extend_from_slice(&final_output.data);

        let mut decryption = OpenPgpDecryptionSession::open(OpenPgpDecryptStreamOpenRequest {
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 7),
            allow_signed_only: Some(false),
        })
        .expect("open decryption stream");
        let mut decrypted = Vec::new();
        for chunk in encrypted.chunks(8_111) {
            decrypted.extend_from_slice(&decryption.update(chunk).expect("decrypt update"));
        }
        let final_output =
            OpenPgpDecryptFinal::decode(decryption.finish().expect("finish decryption").as_slice())
                .expect("decode decryption final");
        decrypted.extend_from_slice(&final_output.data);

        assert_eq!(decrypted, plaintext);
        assert!(final_output.encrypted);
        let metadata = final_output.metadata.as_ref().expect("literal metadata");
        assert_eq!(metadata.file_name, b"uncompressed.bin");
        assert_eq!(metadata.modification_time_epoch_seconds, TEST_TIME + 7);
        assert_eq!(metadata.original_size, plaintext.len() as u64);
    }

    #[test]
    fn streaming_seipd_v1_mdc_roundtrip_and_truncated_finalization() {
        let _stream_guard = STREAM_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let public_key = include_bytes!("../tests/fixtures/openpgp/mdc-public.asc").to_vec();
        let private_key = include_bytes!("../tests/fixtures/openpgp/mdc-secret.asc").to_vec();
        let mut state = 0xa341_316c_u32;
        let plaintext = (0..256 * 1024)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                state as u8
            })
            .collect::<Vec<_>>();
        let mut encryption = OpenPgpEncryptionSession::open(OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![public_key],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "mdc-stream.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME),
            reference_time_epoch_seconds: Some(1_800_000_000),
            enable_compression: None,
        })
        .expect("open MDC encryption stream");
        let mut encrypted = Vec::new();
        for_odd_chunks(&plaintext, &[1, 7, 31], |chunk| {
            encrypted.extend_from_slice(&encryption.update(chunk).expect("MDC encrypt update"));
        });
        let encrypted_final = OpenPgpEncryptFinal::decode(
            encryption
                .finish()
                .expect("finish MDC encryption")
                .as_slice(),
        )
        .expect("decode MDC encryption final");
        assert_eq!(
            encrypted_final.protection_mode,
            OpenPgpProtectionMode::SeipdV1Mdc as i32
        );
        encrypted.extend_from_slice(&encrypted_final.data);

        let decrypt = |ciphertext: &[u8]| {
            let mut session = OpenPgpDecryptionSession::open(OpenPgpDecryptStreamOpenRequest {
                private_keys: vec![private_key.clone()],
                verification_public_keys: Vec::new(),
                reference_time_epoch_seconds: Some(1_800_000_000),
                allow_signed_only: None,
            })
            .expect("open MDC decryption stream");
            let mut provisional = Zeroizing::new(Vec::new());
            for_odd_chunks(ciphertext, &[31, 7, 1], |chunk| {
                provisional
                    .extend_from_slice(&session.update(chunk).expect("provisional MDC plaintext"));
            });
            (session, provisional)
        };
        let (session, mut decrypted) = decrypt(&encrypted);
        let final_output = OpenPgpDecryptFinal::decode(
            session
                .finish()
                .expect("authenticate MDC stream")
                .as_slice(),
        )
        .expect("decode MDC final");
        decrypted.extend_from_slice(&final_output.data);
        assert_eq!(decrypted.as_slice(), plaintext.as_slice());

        let (truncated_session, provisional) = decrypt(&encrypted[..encrypted.len() - 1]);
        assert!(!provisional.is_empty());
        assert_eq!(
            truncated_session
                .finish()
                .expect_err("truncated MDC must fail finalization"),
            OpenPgpWriteError::AuthenticationFailed
        );
    }

    #[test]
    fn streaming_ocb_armor_roundtrip_preserves_large_compressible_plaintext() {
        let _stream_guard = STREAM_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let material = generated_modern_material();
        let mut plaintext = Zeroizing::new(vec![0_u8; 2 * 1024 * 1024]);
        for (index, byte) in plaintext.iter_mut().enumerate().step_by(4096) {
            *byte = (index / 4096) as u8;
        }

        let mut encryption = OpenPgpEncryptionSession::open(OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: Some(material.private_key_armored.clone()),
            preferred_signing_fingerprint: String::new(),
            file_name: "large.bin".to_owned(),
            armored: true,
            literal_time_epoch_seconds: Some(TEST_TIME + 3),
            reference_time_epoch_seconds: Some(TEST_TIME + 3),
            enable_compression: None,
        })
        .expect("open encryption stream");
        let mut encrypted = Vec::new();
        for_odd_chunks(
            plaintext.as_slice(),
            &[1, 7, 31, OPENPGP_PARTIAL_PACKET_BYTES],
            |chunk| {
                encrypted.extend_from_slice(&encryption.update(chunk).expect("encrypt update"));
            },
        );
        let final_output =
            OpenPgpEncryptFinal::decode(encryption.finish().expect("finish encryption").as_slice())
                .expect("decode encryption final");
        assert_eq!(
            final_output.protection_mode,
            OpenPgpProtectionMode::GnupgOcb as i32
        );
        encrypted.extend_from_slice(&final_output.data);
        assert!(encrypted.starts_with(b"-----BEGIN PGP MESSAGE-----"));
        let armor_header_end = if encrypted.starts_with(b"-----BEGIN PGP MESSAGE-----\r\n") {
            b"-----BEGIN PGP MESSAGE-----\r\n".len()
        } else {
            b"-----BEGIN PGP MESSAGE-----\n".len()
        };
        encrypted.splice(
            armor_header_end..armor_header_end,
            b"Charset: ISO-8859-1\n".iter().copied(),
        );

        let mut decryption = OpenPgpDecryptionSession::open(OpenPgpDecryptStreamOpenRequest {
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: vec![material.public_key_armored.clone()],
            reference_time_epoch_seconds: Some(TEST_TIME + 3),
            allow_signed_only: None,
        })
        .expect("open decryption stream");
        let mut decrypted = Zeroizing::new(Vec::new());
        for_odd_chunks(
            &encrypted,
            &[64, 31, 7, 1, OPENPGP_PARTIAL_PACKET_BYTES],
            |chunk| {
                decrypted.extend_from_slice(&decryption.update(chunk).expect("decrypt update"));
            },
        );
        let final_output =
            OpenPgpDecryptFinal::decode(decryption.finish().expect("finish decryption").as_slice())
                .expect("decode decryption final");
        decrypted.extend_from_slice(&final_output.data);
        assert_eq!(decrypted.as_slice(), plaintext.as_slice());
        assert!(final_output.verification.is_some());
        assert_eq!(final_output.declared_charset.as_deref(), Some("ISO-8859-1"));
    }

    #[test]
    fn openpgp_stream_worker_limit_is_fail_closed_and_released_on_drop() {
        let _stream_guard = STREAM_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let material = generated_modern_material();
        let request = || OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "limit.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME),
            reference_time_epoch_seconds: Some(TEST_TIME),
            enable_compression: None,
        };
        let sessions = (0..MAX_OPENPGP_STREAM_WORKERS)
            .map(|_| OpenPgpEncryptionSession::open(request()).expect("worker slot"))
            .collect::<Vec<_>>();
        assert!(matches!(
            OpenPgpEncryptionSession::open(request()),
            Err(OpenPgpWriteError::ResourceLimit)
        ));
        drop(sessions);
        let mut replacement =
            OpenPgpEncryptionSession::open(request()).expect("released worker slot");
        let _ = replacement
            .update(b"cancel this partially consumed stream")
            .expect("partial update before cancellation");
        drop(replacement);
        let final_replacement =
            OpenPgpEncryptionSession::open(request()).expect("cancelled worker slot released");
        drop(final_replacement);
    }

    #[test]
    fn worker_error_after_consumed_ack_keeps_its_stable_code() {
        let _stream_guard = STREAM_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut worker = OpenPgpWorkerPipe::spawn("keyguard-openpgp-race-test", |mut input, _| {
            let mut consumed = [0_u8; 3];
            input
                .read_exact(&mut consumed)
                .map_err(|_| OpenPgpWriteError::Internal)?;
            Err(OpenPgpWriteError::AuthenticationFailed)
        })
        .expect("open test worker");
        match worker.update(b"abc") {
            Err(error) => assert_eq!(error, OpenPgpWriteError::AuthenticationFailed),
            Ok(_) => assert!(matches!(
                worker.finish(),
                Err(OpenPgpWriteError::AuthenticationFailed)
            )),
        }
    }

    #[test]
    fn streaming_decrypt_releases_provisional_bytes_but_rejects_truncated_final_tag() {
        let _stream_guard = STREAM_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let material = generated_modern_material();
        let mut state = 0x9e37_79b9_u32;
        let plaintext = (0..512 * 1024)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                state as u8
            })
            .collect::<Vec<_>>();
        let mut encryption = OpenPgpEncryptionSession::open(OpenPgpEncryptStreamOpenRequest {
            public_keys: vec![material.public_key_armored.clone()],
            signing_private_key: None,
            preferred_signing_fingerprint: String::new(),
            file_name: "tamper.bin".to_owned(),
            armored: false,
            literal_time_epoch_seconds: Some(TEST_TIME + 4),
            reference_time_epoch_seconds: Some(TEST_TIME + 4),
            enable_compression: None,
        })
        .expect("open tamper encryption stream");
        let mut encrypted = Vec::new();
        for chunk in plaintext.chunks(OPENPGP_PARTIAL_PACKET_BYTES) {
            encrypted.extend_from_slice(&encryption.update(chunk).expect("tamper encrypt update"));
        }
        let encrypted_final = OpenPgpEncryptFinal::decode(
            encryption
                .finish()
                .expect("finish tamper encryption")
                .as_slice(),
        )
        .expect("decode tamper encryption final");
        encrypted.extend_from_slice(&encrypted_final.data);
        let truncated = &encrypted[..encrypted.len() - 1];
        let mut decryption = OpenPgpDecryptionSession::open(OpenPgpDecryptStreamOpenRequest {
            private_keys: vec![material.private_key_armored.clone()],
            verification_public_keys: Vec::new(),
            reference_time_epoch_seconds: Some(TEST_TIME + 4),
            allow_signed_only: None,
        })
        .expect("open decryption stream");
        let mut provisional_bytes = 0_usize;
        for_odd_chunks(truncated, &[1, 7, 31], |chunk| {
            provisional_bytes += decryption
                .update(chunk)
                .expect("provisional decrypt update")
                .len();
        });
        assert!(provisional_bytes > 0);
        assert_eq!(
            decryption.finish().expect_err("missing OCB tag must fail"),
            OpenPgpWriteError::AuthenticationFailed
        );
    }
}
