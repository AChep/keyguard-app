//! Generation-tagged streaming-session registry.

use std::sync::{Arc, Mutex, OnceLock};

use aes::{Aes128, Aes192, Aes256};
use aws_lc_rs::constant_time;
use cipher::{
    Block, BlockDecrypt, BlockEncrypt, KeyInit,
    block_padding::{Pkcs7, RawPadding},
};
use keyguard_crypto_sensitive::{
    DigestAlgorithm, DigestContext, HmacContext, SensitiveBackendError,
};
use prost::Message;
use thiserror::Error;
use twofish::Twofish;
use zeroize::{Zeroize, Zeroizing};

use crate::padding::pkcs7_unpadded_block_length;
use crate::primitives::HMAC_SHA256_BYTES;
use crate::protocol::{
    AesCbcPkcs7HmacSha256EncryptResult, CipherDirection, HashAlgorithm,
    OpenPgpDetachedVerifyStreamOpenRequest,
};

const AES_BLOCK_BYTES: usize = 16;
const MAX_SESSION_SLOTS: usize = 1024;
const INDEX_MASK: u64 = u32::MAX as u64;

static SESSIONS: OnceLock<Mutex<SessionRegistry>> = OnceLock::new();

/// Non-sensitive streaming failure classification.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum SessionError {
    /// The handle is stale, unknown, or already consumed.
    #[error("invalid session")]
    InvalidSession,
    /// An operation parameter is invalid.
    #[error("invalid argument")]
    InvalidArgument,
    /// The process-wide concurrent-session bound was reached.
    #[error("session resource limit exceeded")]
    ResourceLimit,
    /// A cryptographic backend rejected the operation.
    #[error("cryptographic operation failed")]
    CryptoFailure,
    /// A V2/V3 OpenPGP key was supplied to a write operation.
    #[error("unsupported OpenPGP key version")]
    UnsupportedKeyVersion,
    /// No policy-valid OpenPGP key exists for the requested operation.
    #[error("no usable OpenPGP key")]
    NoUsableKey,
    /// Final PKCS#7 authentication failed.
    #[error("authentication failed")]
    AuthenticationFailed,
    /// Registry synchronization failed.
    #[error("session registry failed")]
    Internal,
    /// A session worker panic was contained.
    #[error("session worker panicked")]
    Panic,
}

enum Session {
    Hmac(Box<HmacContext>),
    Digest(Box<DigestContext>),
    CbcPkcs7(Box<CbcPkcs7Session>),
    AesCbcHmacSha256Encrypt(Box<AesCbcHmacSha256EncryptSession>),
    AesCbcHmacSha256Decrypt(Box<AesCbcHmacSha256DecryptSession>),
    OpenPgpDetachedVerify(Box<crate::openpgp_read::DetachedVerificationSession>),
    OpenPgpDetachedSign(Box<crate::openpgp_write::DetachedSigningSession>),
    OpenPgpEncrypt(Box<crate::openpgp_write::OpenPgpEncryptionSession>),
    OpenPgpDecrypt(Box<crate::openpgp_write::OpenPgpDecryptionSession>),
}

enum CbcCipher {
    Aes128(Aes128),
    Aes192(Aes192),
    Aes256(Aes256),
    Twofish(Twofish),
}

struct CbcPkcs7Session {
    direction: CipherDirection,
    cipher: CbcCipher,
    chain: Zeroizing<[u8; AES_BLOCK_BYTES]>,
    pending: Zeroizing<Vec<u8>>,
}

struct AesCbcHmacSha256EncryptSession {
    cipher: CbcPkcs7Session,
    hmac: HmacContext,
}

struct AesCbcHmacSha256DecryptSession {
    cipher: CbcPkcs7Session,
    hmac: HmacContext,
    expected_mac: Zeroizing<Vec<u8>>,
}

struct Slot {
    generation: u32,
    session: Option<SessionCell>,
    is_free: bool,
}

type SessionCell = Arc<Mutex<Option<Session>>>;

#[derive(Default)]
struct SessionRegistry {
    slots: Vec<Slot>,
    free_indices: Vec<usize>,
}

pub(crate) fn open_hmac_sha256(key: Vec<u8>) -> Result<u64, SessionError> {
    open_hmac(HashAlgorithm::Sha256, key)
}

pub(crate) fn open_hmac(algorithm: HashAlgorithm, key: Vec<u8>) -> Result<u64, SessionError> {
    let key = Zeroizing::new(key);
    let algorithm = sensitive_algorithm(algorithm)?;
    let context = HmacContext::new(algorithm, &key).map_err(sensitive_backend_error)?;
    insert(Session::Hmac(Box::new(context)))
}

pub(crate) fn open_digest(algorithm: HashAlgorithm) -> Result<u64, SessionError> {
    let algorithm = sensitive_algorithm(algorithm)?;
    let context = DigestContext::new(algorithm).map_err(sensitive_backend_error)?;
    insert(Session::Digest(Box::new(context)))
}

pub(crate) fn open_aes_cbc_pkcs7(
    direction: CipherDirection,
    key: Vec<u8>,
    iv: Vec<u8>,
) -> Result<u64, SessionError> {
    let session = CbcPkcs7Session::new_aes(direction, key, iv)?;
    insert(Session::CbcPkcs7(Box::new(session)))
}

pub(crate) fn open_aes_cbc_pkcs7_hmac_sha256_encrypt(
    encryption_key: Vec<u8>,
    mac_key: Vec<u8>,
    iv: Vec<u8>,
) -> Result<u64, SessionError> {
    let session = AesCbcHmacSha256EncryptSession::new(encryption_key, mac_key, iv)?;
    insert(Session::AesCbcHmacSha256Encrypt(Box::new(session)))
}

pub(crate) fn open_aes_cbc_pkcs7_hmac_sha256_decrypt(
    encryption_key: Vec<u8>,
    mac_key: Vec<u8>,
    iv: Vec<u8>,
    expected_mac: Vec<u8>,
) -> Result<u64, SessionError> {
    let session = AesCbcHmacSha256DecryptSession::new(encryption_key, mac_key, iv, expected_mac)?;
    insert(Session::AesCbcHmacSha256Decrypt(Box::new(session)))
}

pub(crate) fn open_twofish_cbc_pkcs7(
    direction: CipherDirection,
    key: Vec<u8>,
    iv: Vec<u8>,
) -> Result<u64, SessionError> {
    let session = CbcPkcs7Session::new_twofish(direction, key, iv)?;
    insert(Session::CbcPkcs7(Box::new(session)))
}

pub(crate) fn open_openpgp_detached_verify(
    request: OpenPgpDetachedVerifyStreamOpenRequest,
) -> Result<u64, SessionError> {
    let session = crate::openpgp_read::DetachedVerificationSession::open(request)
        .map_err(openpgp_read_error)?;
    insert(Session::OpenPgpDetachedVerify(Box::new(session)))
}

pub(crate) fn open_openpgp_detached_sign(
    request: crate::protocol::OpenPgpDetachedSignStreamOpenRequest,
) -> Result<u64, SessionError> {
    let session =
        crate::openpgp_write::DetachedSigningSession::open(request).map_err(openpgp_write_error)?;
    insert(Session::OpenPgpDetachedSign(Box::new(session)))
}

pub(crate) fn open_openpgp_encrypt(
    request: crate::protocol::OpenPgpEncryptStreamOpenRequest,
) -> Result<u64, SessionError> {
    let session = crate::openpgp_write::OpenPgpEncryptionSession::open(request)
        .map_err(openpgp_write_error)?;
    insert(Session::OpenPgpEncrypt(Box::new(session)))
}

pub(crate) fn open_openpgp_decrypt(
    request: crate::protocol::OpenPgpDecryptStreamOpenRequest,
) -> Result<u64, SessionError> {
    let session = crate::openpgp_write::OpenPgpDecryptionSession::open(request)
        .map_err(openpgp_write_error)?;
    insert(Session::OpenPgpDecrypt(Box::new(session)))
}

pub(crate) fn update(handle: u64, data: &[u8]) -> Result<Vec<u8>, SessionError> {
    let session = {
        let registry = registry().lock().map_err(|_| SessionError::Internal)?;
        registry.active_session_cell(handle)?
    };
    let mut session = session.lock().map_err(|_| SessionError::Internal)?;
    session
        .as_mut()
        .ok_or(SessionError::InvalidSession)?
        .update(data)
}

pub(crate) fn finish(handle: u64) -> Result<Vec<u8>, SessionError> {
    let session = {
        let mut registry = registry().lock().map_err(|_| SessionError::Internal)?;
        registry.take_active_session_and_release(handle)?
    };
    let session = session
        .lock()
        .map_err(|_| SessionError::Internal)?
        .take()
        .ok_or(SessionError::InvalidSession)?;
    session.finish()
}

pub(crate) fn close(handle: u64) -> Result<(), SessionError> {
    let session = {
        let mut registry = registry().lock().map_err(|_| SessionError::Internal)?;
        registry.close(handle)?
    };
    if let Some(session) = session {
        session.lock().map_err(|_| SessionError::Internal)?.take();
    }
    Ok(())
}

fn insert(session: Session) -> Result<u64, SessionError> {
    let mut registry = registry().lock().map_err(|_| SessionError::Internal)?;
    registry.insert(session)
}

fn registry() -> &'static Mutex<SessionRegistry> {
    SESSIONS.get_or_init(|| Mutex::new(SessionRegistry::default()))
}

impl Session {
    fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, SessionError> {
        match self {
            Self::Hmac(session) => {
                session.update(data).map_err(sensitive_backend_error)?;
                Ok(Vec::new())
            }
            Self::Digest(session) => {
                session.update(data).map_err(sensitive_backend_error)?;
                Ok(Vec::new())
            }
            Self::CbcPkcs7(session) => session.update(data),
            Self::AesCbcHmacSha256Encrypt(session) => session.update(data),
            Self::AesCbcHmacSha256Decrypt(session) => session.update(data),
            Self::OpenPgpDetachedVerify(session) => {
                session.update(data).map_err(openpgp_read_error)?;
                Ok(Vec::new())
            }
            Self::OpenPgpDetachedSign(session) => {
                session.update(data).map_err(openpgp_write_error)?;
                Ok(Vec::new())
            }
            Self::OpenPgpEncrypt(session) => session.update(data).map_err(openpgp_write_error),
            Self::OpenPgpDecrypt(session) => session.update(data).map_err(openpgp_write_error),
        }
    }

    fn finish(self) -> Result<Vec<u8>, SessionError> {
        let output = match self {
            Self::Hmac(mut session) => {
                let mut output = Zeroizing::new(vec![0_u8; session.output_size()]);
                session
                    .finalize_into(&mut output)
                    .map_err(sensitive_backend_error)?;
                output.to_vec()
            }
            Self::Digest(mut session) => {
                let mut output = Zeroizing::new(vec![0_u8; session.output_size()]);
                session
                    .finalize_into(&mut output)
                    .map_err(sensitive_backend_error)?;
                output.to_vec()
            }
            Self::CbcPkcs7(mut session) => return session.finish(),
            Self::AesCbcHmacSha256Encrypt(session) => return (*session).finish(),
            Self::AesCbcHmacSha256Decrypt(session) => return (*session).finish(),
            Self::OpenPgpDetachedVerify(session) => {
                return (*session).finish().map_err(openpgp_read_error);
            }
            Self::OpenPgpDetachedSign(session) => {
                return (*session).finish().map_err(openpgp_write_error);
            }
            Self::OpenPgpEncrypt(session) => {
                return (*session).finish().map_err(openpgp_write_error);
            }
            Self::OpenPgpDecrypt(session) => {
                return (*session).finish().map_err(openpgp_write_error);
            }
        };
        Ok(output)
    }
}

fn openpgp_read_error(error: crate::openpgp_read::OpenPgpReadError) -> SessionError {
    match error {
        crate::openpgp_read::OpenPgpReadError::InvalidArgument => SessionError::InvalidArgument,
        crate::openpgp_read::OpenPgpReadError::ResourceLimit => SessionError::ResourceLimit,
        crate::openpgp_read::OpenPgpReadError::Internal => SessionError::Internal,
    }
}

fn openpgp_write_error(error: crate::openpgp_write::OpenPgpWriteError) -> SessionError {
    match error {
        crate::openpgp_write::OpenPgpWriteError::InvalidArgument => SessionError::InvalidArgument,
        crate::openpgp_write::OpenPgpWriteError::MissingKey => SessionError::NoUsableKey,
        crate::openpgp_write::OpenPgpWriteError::UnsupportedKeyVersion(_) => {
            SessionError::UnsupportedKeyVersion
        }
        crate::openpgp_write::OpenPgpWriteError::AuthenticationFailed => {
            SessionError::AuthenticationFailed
        }
        crate::openpgp_write::OpenPgpWriteError::ResourceLimit => SessionError::ResourceLimit,
        crate::openpgp_write::OpenPgpWriteError::CryptoFailure => SessionError::CryptoFailure,
        crate::openpgp_write::OpenPgpWriteError::Internal => SessionError::Internal,
        crate::openpgp_write::OpenPgpWriteError::Panic => SessionError::Panic,
    }
}

impl CbcCipher {
    fn new_aes(key: &[u8]) -> Result<Self, SessionError> {
        match key.len() {
            16 => Aes128::new_from_slice(key)
                .map(Self::Aes128)
                .map_err(|_| SessionError::InvalidArgument),
            24 => Aes192::new_from_slice(key)
                .map(Self::Aes192)
                .map_err(|_| SessionError::InvalidArgument),
            32 => Aes256::new_from_slice(key)
                .map(Self::Aes256)
                .map_err(|_| SessionError::InvalidArgument),
            _ => Err(SessionError::InvalidArgument),
        }
    }

    fn new_twofish(key: &[u8]) -> Result<Self, SessionError> {
        Twofish::new_from_slice(key)
            .map(Self::Twofish)
            .map_err(|_| SessionError::InvalidArgument)
    }

    fn encrypt_block(&self, block: &mut [u8; AES_BLOCK_BYTES]) {
        match self {
            Self::Aes128(cipher) => cipher.encrypt_block(Block::<Aes128>::from_mut_slice(block)),
            Self::Aes192(cipher) => cipher.encrypt_block(Block::<Aes192>::from_mut_slice(block)),
            Self::Aes256(cipher) => cipher.encrypt_block(Block::<Aes256>::from_mut_slice(block)),
            Self::Twofish(cipher) => cipher.encrypt_block(Block::<Twofish>::from_mut_slice(block)),
        }
    }

    fn decrypt_block(&self, block: &mut [u8; AES_BLOCK_BYTES]) {
        match self {
            Self::Aes128(cipher) => cipher.decrypt_block(Block::<Aes128>::from_mut_slice(block)),
            Self::Aes192(cipher) => cipher.decrypt_block(Block::<Aes192>::from_mut_slice(block)),
            Self::Aes256(cipher) => cipher.decrypt_block(Block::<Aes256>::from_mut_slice(block)),
            Self::Twofish(cipher) => cipher.decrypt_block(Block::<Twofish>::from_mut_slice(block)),
        }
    }
}

fn sensitive_backend_error(_: SensitiveBackendError) -> SessionError {
    SessionError::CryptoFailure
}

fn sensitive_algorithm(algorithm: HashAlgorithm) -> Result<DigestAlgorithm, SessionError> {
    match algorithm {
        HashAlgorithm::Sha1 => Ok(DigestAlgorithm::Sha1),
        HashAlgorithm::Sha256 => Ok(DigestAlgorithm::Sha256),
        HashAlgorithm::Sha512 => Ok(DigestAlgorithm::Sha512),
        HashAlgorithm::Md5 => Ok(DigestAlgorithm::Md5),
        HashAlgorithm::Unspecified => Err(SessionError::InvalidArgument),
    }
}

impl CbcPkcs7Session {
    fn new_aes(
        direction: CipherDirection,
        key: Vec<u8>,
        iv: Vec<u8>,
    ) -> Result<Self, SessionError> {
        Self::new(direction, CbcCipher::new_aes, key, iv)
    }

    fn new_twofish(
        direction: CipherDirection,
        key: Vec<u8>,
        iv: Vec<u8>,
    ) -> Result<Self, SessionError> {
        Self::new(direction, CbcCipher::new_twofish, key, iv)
    }

    fn new(
        direction: CipherDirection,
        create_cipher: impl FnOnce(&[u8]) -> Result<CbcCipher, SessionError>,
        key: Vec<u8>,
        iv: Vec<u8>,
    ) -> Result<Self, SessionError> {
        let key = Zeroizing::new(key);
        let iv = Zeroizing::new(iv);
        if direction == CipherDirection::Unspecified || iv.len() != AES_BLOCK_BYTES {
            return Err(SessionError::InvalidArgument);
        }
        let cipher = create_cipher(&key)?;
        let mut chain = Zeroizing::new([0_u8; AES_BLOCK_BYTES]);
        chain.copy_from_slice(&iv);
        Ok(Self {
            direction,
            cipher,
            chain,
            pending: Zeroizing::new(Vec::with_capacity(AES_BLOCK_BYTES * 2 - 1)),
        })
    }

    fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, SessionError> {
        let mut combined = Zeroizing::new(Vec::with_capacity(self.pending.len() + data.len()));
        combined.extend_from_slice(&self.pending);
        self.pending.zeroize();
        combined.extend_from_slice(data);

        let process_blocks = match self.direction {
            CipherDirection::Encrypt => combined.len() / AES_BLOCK_BYTES,
            CipherDirection::Decrypt => {
                combined.len().saturating_sub(AES_BLOCK_BYTES) / AES_BLOCK_BYTES
            }
            CipherDirection::Unspecified => return Err(SessionError::InvalidArgument),
        };
        let process_bytes = process_blocks * AES_BLOCK_BYTES;
        let mut output = Zeroizing::new(Vec::with_capacity(process_bytes));
        for chunk in combined[..process_bytes].as_chunks::<AES_BLOCK_BYTES>().0 {
            self.transform_block(chunk, &mut output);
        }
        self.pending.extend_from_slice(&combined[process_bytes..]);
        Ok(output.to_vec())
    }

    fn finish(&mut self) -> Result<Vec<u8>, SessionError> {
        match self.direction {
            CipherDirection::Encrypt => {
                let mut block = Zeroizing::new([0_u8; AES_BLOCK_BYTES]);
                let position = self.pending.len();
                block[..position].copy_from_slice(&self.pending);
                Pkcs7::raw_pad(block.as_mut_slice(), position);
                let mut output = Zeroizing::new(Vec::with_capacity(AES_BLOCK_BYTES));
                self.transform_block(block.as_slice(), &mut output);
                Ok(output.to_vec())
            }
            CipherDirection::Decrypt => {
                if self.pending.len() != AES_BLOCK_BYTES {
                    return Err(SessionError::InvalidArgument);
                }
                let mut block = Zeroizing::new([0_u8; AES_BLOCK_BYTES]);
                block.copy_from_slice(&self.pending);
                self.cipher.decrypt_block(&mut block);
                for (byte, chain) in block.iter_mut().zip(self.chain.iter()) {
                    *byte ^= chain;
                }
                let plaintext_length = pkcs7_unpadded_block_length(block.as_slice())
                    .ok_or(SessionError::AuthenticationFailed)?;
                Ok(block[..plaintext_length].to_vec())
            }
            CipherDirection::Unspecified => Err(SessionError::InvalidArgument),
        }
    }

    fn transform_block(&mut self, input: &[u8], output: &mut Vec<u8>) {
        let mut block = Zeroizing::new([0_u8; AES_BLOCK_BYTES]);
        block.copy_from_slice(input);
        match self.direction {
            CipherDirection::Encrypt => {
                for (byte, chain) in block.iter_mut().zip(self.chain.iter()) {
                    *byte ^= chain;
                }
                self.cipher.encrypt_block(&mut block);
                self.chain.copy_from_slice(block.as_slice());
            }
            CipherDirection::Decrypt => {
                let ciphertext = *block;
                self.cipher.decrypt_block(&mut block);
                for (byte, chain) in block.iter_mut().zip(self.chain.iter()) {
                    *byte ^= chain;
                }
                self.chain.copy_from_slice(&ciphertext);
            }
            CipherDirection::Unspecified => unreachable!("validated at session construction"),
        }
        output.extend_from_slice(block.as_slice());
    }
}

impl AesCbcHmacSha256EncryptSession {
    fn new(encryption_key: Vec<u8>, mac_key: Vec<u8>, iv: Vec<u8>) -> Result<Self, SessionError> {
        let mut encryption_key = Zeroizing::new(encryption_key);
        let mac_key = Zeroizing::new(mac_key);
        let mut iv = Zeroizing::new(iv);
        validate_authenticated_aes_parameters(&encryption_key, &iv)?;
        let hmac = hmac_sha256_with_iv(&mac_key, &iv)?;
        let cipher = CbcPkcs7Session::new_aes(
            CipherDirection::Encrypt,
            std::mem::take(&mut *encryption_key),
            std::mem::take(&mut *iv),
        )?;
        Ok(Self { cipher, hmac })
    }

    fn update(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, SessionError> {
        let ciphertext = self.cipher.update(plaintext)?;
        self.hmac
            .update(&ciphertext)
            .map_err(sensitive_backend_error)?;
        Ok(ciphertext)
    }

    fn finish(mut self) -> Result<Vec<u8>, SessionError> {
        let final_ciphertext = Zeroizing::new(self.cipher.finish()?);
        self.hmac
            .update(&final_ciphertext)
            .map_err(sensitive_backend_error)?;
        let mut mac = Zeroizing::new(vec![0_u8; HMAC_SHA256_BYTES]);
        self.hmac
            .finalize_into(&mut mac)
            .map_err(sensitive_backend_error)?;
        Ok(AesCbcPkcs7HmacSha256EncryptResult {
            ciphertext: final_ciphertext.to_vec(),
            mac: mac.to_vec(),
        }
        .encode_to_vec())
    }
}

impl AesCbcHmacSha256DecryptSession {
    fn new(
        encryption_key: Vec<u8>,
        mac_key: Vec<u8>,
        iv: Vec<u8>,
        expected_mac: Vec<u8>,
    ) -> Result<Self, SessionError> {
        let mut encryption_key = Zeroizing::new(encryption_key);
        let mac_key = Zeroizing::new(mac_key);
        let mut iv = Zeroizing::new(iv);
        let expected_mac = Zeroizing::new(expected_mac);
        validate_authenticated_aes_parameters(&encryption_key, &iv)?;
        let hmac = hmac_sha256_with_iv(&mac_key, &iv)?;
        let cipher = CbcPkcs7Session::new_aes(
            CipherDirection::Decrypt,
            std::mem::take(&mut *encryption_key),
            std::mem::take(&mut *iv),
        )?;
        Ok(Self {
            cipher,
            hmac,
            expected_mac,
        })
    }

    fn update(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>, SessionError> {
        self.hmac
            .update(ciphertext)
            .map_err(sensitive_backend_error)?;
        self.cipher.update(ciphertext)
    }

    fn finish(mut self) -> Result<Vec<u8>, SessionError> {
        let mut actual_mac = Zeroizing::new([0_u8; HMAC_SHA256_BYTES]);
        self.hmac
            .finalize_into(actual_mac.as_mut_slice())
            .map_err(sensitive_backend_error)?;
        constant_time::verify_slices_are_equal(actual_mac.as_slice(), &self.expected_mac)
            .map_err(|_| SessionError::AuthenticationFailed)?;
        self.cipher.finish()
    }
}

fn validate_authenticated_aes_parameters(
    encryption_key: &[u8],
    iv: &[u8],
) -> Result<(), SessionError> {
    if !matches!(encryption_key.len(), 16 | 24 | 32) || iv.len() != AES_BLOCK_BYTES {
        return Err(SessionError::InvalidArgument);
    }
    Ok(())
}

fn hmac_sha256_with_iv(mac_key: &[u8], iv: &[u8]) -> Result<HmacContext, SessionError> {
    let mut hmac =
        HmacContext::new(DigestAlgorithm::Sha256, mac_key).map_err(sensitive_backend_error)?;
    hmac.update(iv).map_err(sensitive_backend_error)?;
    Ok(hmac)
}

impl SessionRegistry {
    fn insert(&mut self, session: Session) -> Result<u64, SessionError> {
        let session = Arc::new(Mutex::new(Some(session)));
        while let Some(index) = self.free_indices.pop() {
            let slot = self.slots.get_mut(index).ok_or(SessionError::Internal)?;
            let Some(generation) = slot.generation.checked_add(1) else {
                // Generation zero is never issued. Retire an exhausted slot
                // permanently instead of wrapping and making an ancient
                // handle valid again (ABA).
                continue;
            };
            slot.generation = generation;
            slot.session = Some(session);
            slot.is_free = false;
            return encode_handle(index, slot.generation);
        }
        if self.slots.len() >= MAX_SESSION_SLOTS {
            return Err(SessionError::ResourceLimit);
        }

        let index = self.slots.len();
        let generation = 1;
        self.slots.push(Slot {
            generation,
            session: Some(session),
            is_free: false,
        });
        encode_handle(index, generation)
    }

    fn active_session_cell(&self, handle: u64) -> Result<SessionCell, SessionError> {
        self.slot(handle)?
            .session
            .as_ref()
            .cloned()
            .ok_or(SessionError::InvalidSession)
    }

    fn take_active_session_and_release(
        &mut self,
        handle: u64,
    ) -> Result<SessionCell, SessionError> {
        let (index, generation) = decode_handle(handle)?;
        let slot = self
            .slots
            .get_mut(index)
            .ok_or(SessionError::InvalidSession)?;
        if slot.generation != generation || slot.is_free {
            return Err(SessionError::InvalidSession);
        }
        let session = slot.session.take().ok_or(SessionError::InvalidSession)?;
        slot.is_free = true;
        if slot.generation != u32::MAX {
            self.free_indices.push(index);
        }
        Ok(session)
    }

    fn close(&mut self, handle: u64) -> Result<Option<SessionCell>, SessionError> {
        let (index, generation) = decode_handle(handle)?;
        let slot = self
            .slots
            .get_mut(index)
            .ok_or(SessionError::InvalidSession)?;
        if generation < slot.generation {
            // The slot has already been closed/finished and safely reused.
            // A close for an older generation remains an idempotent no-op and
            // must never affect the newer active session.
            return Ok(None);
        }
        if slot.generation != generation {
            return Err(SessionError::InvalidSession);
        }

        // Dropping an active session releases backend key state. A consumed
        // session has `None`, making repeated close calls idempotent until the
        // generation-tagged slot is allocated to a new session.
        let session = slot.session.take();
        if !slot.is_free {
            slot.is_free = true;
            if slot.generation != u32::MAX {
                self.free_indices.push(index);
            }
        }
        Ok(session)
    }

    fn slot(&self, handle: u64) -> Result<&Slot, SessionError> {
        let (index, generation) = decode_handle(handle)?;
        let slot = self.slots.get(index).ok_or(SessionError::InvalidSession)?;
        if slot.generation != generation || slot.is_free {
            return Err(SessionError::InvalidSession);
        }
        Ok(slot)
    }
}

fn encode_handle(index: usize, generation: u32) -> Result<u64, SessionError> {
    let one_based_index = u32::try_from(index)
        .map_err(|_| SessionError::ResourceLimit)?
        .checked_add(1)
        .ok_or(SessionError::ResourceLimit)?;
    Ok((u64::from(generation) << 32) | u64::from(one_based_index))
}

fn decode_handle(handle: u64) -> Result<(usize, u32), SessionError> {
    let generation = u32::try_from(handle >> 32).map_err(|_| SessionError::InvalidSession)?;
    let one_based_index =
        u32::try_from(handle & INDEX_MASK).map_err(|_| SessionError::InvalidSession)?;
    if generation == 0 || one_based_index == 0 {
        return Err(SessionError::InvalidSession);
    }
    let index = usize::try_from(one_based_index - 1).map_err(|_| SessionError::InvalidSession)?;
    Ok((index, generation))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn openpgp_missing_key_retains_its_classification() {
        assert_eq!(
            openpgp_write_error(crate::openpgp_write::OpenPgpWriteError::MissingKey),
            SessionError::NoUsableKey
        );
    }

    #[test]
    fn aes_sessions_keep_only_a_block_tail_between_updates() {
        let key = vec![0x11; 32];
        let iv = vec![0x22; AES_BLOCK_BYTES];

        let mut encrypt =
            CbcPkcs7Session::new_aes(CipherDirection::Encrypt, key.clone(), iv.clone())
                .expect("encrypt session must open");
        let output = encrypt
            .update(&vec![0x33; crate::MAX_STREAM_CHUNK_BYTES])
            .expect("encrypt update must succeed");
        assert_eq!(output.len(), crate::MAX_STREAM_CHUNK_BYTES);
        assert!(encrypt.pending.len() < AES_BLOCK_BYTES);

        let mut decrypt = CbcPkcs7Session::new_aes(CipherDirection::Decrypt, key, iv)
            .expect("decrypt session must open");
        let output = decrypt
            .update(&vec![0x44; crate::MAX_STREAM_CHUNK_BYTES])
            .expect("decrypt update must succeed");
        assert_eq!(
            output.len(),
            crate::MAX_STREAM_CHUNK_BYTES - AES_BLOCK_BYTES
        );
        assert!((AES_BLOCK_BYTES..AES_BLOCK_BYTES * 2).contains(&decrypt.pending.len()));
    }

    #[test]
    fn distinct_handles_use_distinct_session_locks() {
        let first = open_digest(HashAlgorithm::Sha256).expect("first session must open");
        let second = open_digest(HashAlgorithm::Sha512).expect("second session must open");
        let (first_cell, second_cell) = {
            let registry = registry().lock().expect("registry lock must succeed");
            (
                registry
                    .active_session_cell(first)
                    .expect("first session must be active"),
                registry
                    .active_session_cell(second)
                    .expect("second session must be active"),
            )
        };

        let first_guard = first_cell.lock().expect("first session lock must succeed");
        assert!(second_cell.try_lock().is_ok());
        drop(first_guard);
        close(first).expect("first session must close");
        close(second).expect("second session must close");
    }

    #[test]
    fn cancelling_every_digest_and_hmac_backend_consumes_the_session() {
        for algorithm in [
            HashAlgorithm::Sha1,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha512,
            HashAlgorithm::Md5,
        ] {
            let digest = open_digest(algorithm).expect("digest session must open");
            update(digest, b"partial secret message").expect("digest update must succeed");
            close(digest).expect("digest close must succeed");
            assert_eq!(
                update(digest, b"reuse").err(),
                Some(SessionError::InvalidSession)
            );
            close(digest).expect("repeated digest close must be idempotent");

            let hmac =
                open_hmac(algorithm, b"secret key".to_vec()).expect("HMAC session must open");
            update(hmac, b"partial secret message").expect("HMAC update must succeed");
            close(hmac).expect("HMAC close must succeed");
            assert_eq!(
                update(hmac, b"reuse").err(),
                Some(SessionError::InvalidSession)
            );
            close(hmac).expect("repeated HMAC close must be idempotent");
        }
    }

    #[test]
    fn exhausted_generation_retires_slot_without_aba_reuse() {
        let session = || {
            Session::Digest(Box::new(
                DigestContext::new(DigestAlgorithm::Md5).expect("test MD5 context must initialize"),
            ))
        };
        let mut registry = SessionRegistry::default();
        let _ = registry
            .insert(session())
            .expect("initial session must insert");
        registry.slots[0].generation = u32::MAX - 1;
        let near_max = encode_handle(0, u32::MAX - 1).expect("near-max handle must encode");
        let _ = registry
            .take_active_session_and_release(near_max)
            .expect("near-max session must release");

        let max_handle = registry
            .insert(session())
            .expect("maximum generation must be issued once");
        assert_eq!(decode_handle(max_handle), Ok((0, u32::MAX)));
        let _ = registry
            .take_active_session_and_release(max_handle)
            .expect("maximum generation must release");
        assert!(registry.free_indices.is_empty());

        let replacement = registry
            .insert(session())
            .expect("replacement must use a fresh slot");
        assert_eq!(decode_handle(replacement), Ok((1, 1)));
        let ancient = encode_handle(0, 1).expect("ancient handle must encode");
        assert_eq!(
            registry.active_session_cell(ancient).err(),
            Some(SessionError::InvalidSession)
        );
    }

    #[test]
    fn registry_enforces_concurrent_session_limit_and_reuses_released_capacity() {
        let session = || {
            Session::Digest(Box::new(
                DigestContext::new(DigestAlgorithm::Md5).expect("test MD5 context must initialize"),
            ))
        };
        let mut registry = SessionRegistry::default();
        let handles = (0..MAX_SESSION_SLOTS)
            .map(|_| {
                registry
                    .insert(session())
                    .expect("session must fit below limit")
            })
            .collect::<Vec<_>>();

        assert_eq!(
            registry.insert(session()).err(),
            Some(SessionError::ResourceLimit)
        );

        let released = handles[0];
        let _ = registry
            .take_active_session_and_release(released)
            .expect("active session must release");
        let replacement = registry
            .insert(session())
            .expect("released capacity must be reusable");
        assert_ne!(replacement, released);
        assert_eq!(decode_handle(replacement), Ok((0, 2)));
    }
}
