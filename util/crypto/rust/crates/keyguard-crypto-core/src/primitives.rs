//! One-shot primitive implementations.

use aes::{Aes128, Aes192, Aes256};
use argon2::{Algorithm, Version};
use chacha20::ChaCha20;
use salsa20::Salsa20;
use std::{mem::size_of, num::NonZeroU32};

use aws_lc_rs::{
    aead::{Aad, CHACHA20_POLY1305, LessSafeKey, Nonce, UnboundKey},
    constant_time,
    encoding::{AsDer, PublicKeyX509Der},
    rsa::{
        OAEP_SHA1_MGF1SHA1, OAEP_SHA256_MGF1SHA256, OaepAlgorithm, OaepPrivateDecryptingKey,
        OaepPublicEncryptingKey, PrivateDecryptingKey, PublicEncryptingKey,
    },
};
use aws_lc_rs::{pbkdf2 as aws_pbkdf2, rand};
use cbc::{Decryptor, Encryptor};
use cipher::{
    Block, BlockDecryptMut, BlockEncrypt, BlockEncryptMut, InnerIvInit, KeyInit, KeyIvInit,
    StreamCipher, StreamCipherSeek,
    block_padding::{NoPadding, Pkcs7},
    consts::U16,
};
use keyguard_crypto_sensitive::{
    DigestAlgorithm, DigestContext, HmacContext, SensitiveBackendError,
};
use pkcs8::{ObjectIdentifier, PrivateKeyInfo, SubjectPublicKeyInfoRef, der::Decode};
use thiserror::Error;
use twofish::Twofish;
use zeroize::{Zeroize, Zeroizing};

use crate::padding::pkcs7_unpadded_block_length;
use crate::protocol::{
    Argon2Mode, CipherDirection, HashAlgorithm, RsaOaepHash, StreamCipherAlgorithm,
};

pub(crate) const AES_BLOCK_BYTES: usize = 16;
pub(crate) const HMAC_SHA256_BYTES: usize = 32;
pub(crate) const MAX_AES_TRANSFORM_ROUNDS: u32 = 100_000_000;
const MAX_AES_TRANSFORM_BLOCK_OPERATIONS: u64 = 200_000_000;
const HKDF_SHA256_MAX_BYTES: usize = 255 * 32;
const MAX_OUTPUT_BYTES: usize = crate::MAX_CONTROL_ENVELOPE_BYTES - 1024;
// Kotlin's preserved public contract accepts every positive Int iteration
// count. Reject only protobuf values that cannot originate from that contract.
pub(crate) const MAX_PBKDF2_ITERATIONS: u32 = i32::MAX as u32;
const MAX_ARGON2_MEMORY_KIB: u32 = 1024 * 1024;
const MAX_ARGON2_ITERATIONS: u32 = 10_000;
const MAX_ARGON2_PARALLELISM: u32 = 64;
const MAX_RANDOM_INT_BATCH: u32 = 1024;
const RANDOM_INT_CANDIDATES_PER_FILL: usize = MAX_RANDOM_INT_BATCH as usize;
const SSH_AGENT_TCP_KEY_BYTES: usize = 32;
const SSH_AGENT_TCP_NONCE_BYTES: usize = 12;
const SSH_AGENT_TCP_HEADER_BYTES: usize = 18;
const SSH_AGENT_TCP_TAG_BYTES: usize = 16;
pub(crate) const MAX_SSH_AGENT_TCP_PAYLOAD_BYTES: usize = 1024 * 1024;
const RSA_ENCRYPTION_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.1.1.1");

/// Non-sensitive primitive failure classification.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub(crate) enum PrimitiveError {
    /// One or more parameters violate the operation contract.
    #[error("invalid argument")]
    InvalidArgument,
    /// An explicit resource bound was exceeded.
    #[error("resource limit exceeded")]
    ResourceLimit,
    /// The cryptographic backend failed without a more specific classification.
    #[error("cryptographic operation failed")]
    CryptoFailure,
    /// An internal composition or invariant failed.
    #[error("internal operation failed")]
    Internal,
    /// CBC padding validation failed.
    #[error("authentication failed")]
    AuthenticationFailed,
    /// A supplied OpenPGP key packet uses unsupported legacy version 2 or 3.
    #[error("unsupported OpenPGP key version")]
    UnsupportedKeyVersion,
    /// No policy-valid OpenPGP key exists for the requested operation.
    #[error("no usable OpenPGP key")]
    NoUsableKey,
    /// A background operation panic was contained.
    #[error("operation panicked")]
    Panic,
}

pub(crate) fn hkdf_sha256(
    seed: Vec<u8>,
    salt: Option<Vec<u8>>,
    info: Option<Vec<u8>>,
    length: u32,
) -> Result<Vec<u8>, PrimitiveError> {
    let seed = Zeroizing::new(seed);
    let salt = salt.map(Zeroizing::new);
    let info = Zeroizing::new(info.unwrap_or_default());
    let length = bounded_output_length(length)?;
    if length > HKDF_SHA256_MAX_BYTES {
        return Err(PrimitiveError::InvalidArgument);
    }

    let prk = match salt.as_ref() {
        Some(salt) => Zeroizing::new(sensitive_hmac_bytes(DigestAlgorithm::Sha256, salt, &seed)?),
        // Preserve Keyguard's null-salt skip-extract contract: use the seed
        // directly as the HMAC key, including an empty seed.
        None => seed.clone(),
    };

    let mut output = Zeroizing::new(Vec::with_capacity(length));
    let mut previous = Zeroizing::new(Vec::new());
    for counter in 1..=length.div_ceil(32) {
        let mut mac =
            HmacContext::new(DigestAlgorithm::Sha256, &prk).map_err(sensitive_backend_error)?;
        mac.update(&previous).map_err(sensitive_backend_error)?;
        mac.update(&info).map_err(sensitive_backend_error)?;
        let counter = u8::try_from(counter).map_err(|_| PrimitiveError::InvalidArgument)?;
        mac.update(&[counter]).map_err(sensitive_backend_error)?;
        let mut block = Zeroizing::new([0_u8; 32]);
        mac.finalize_into(&mut *block)
            .map_err(sensitive_backend_error)?;
        previous.clear();
        previous.extend_from_slice(&*block);
        let remaining = length - output.len();
        output.extend_from_slice(&block[..remaining.min(block.len())]);
    }
    Ok(output.to_vec())
}

pub(crate) fn pbkdf2_sha256(
    seed: Vec<u8>,
    salt: Vec<u8>,
    iterations: u32,
    length: u32,
) -> Result<Vec<u8>, PrimitiveError> {
    let seed = Zeroizing::new(seed);
    let salt = Zeroizing::new(salt);
    if iterations == 0 {
        return Err(PrimitiveError::InvalidArgument);
    }
    if iterations > MAX_PBKDF2_ITERATIONS {
        return Err(PrimitiveError::ResourceLimit);
    }
    let length = bounded_output_length(length)?;
    if length == 0 {
        return Ok(Vec::new());
    }

    let mut output = vec![0_u8; length];
    let iterations = NonZeroU32::new(iterations).ok_or(PrimitiveError::InvalidArgument)?;
    aws_pbkdf2::derive(
        aws_pbkdf2::PBKDF2_HMAC_SHA256,
        iterations,
        &salt,
        &seed,
        &mut output,
    );
    Ok(output)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn argon2(
    mode: Argon2Mode,
    version: Version,
    seed: Vec<u8>,
    salt: Vec<u8>,
    secret: Option<Vec<u8>>,
    associated_data: Option<Vec<u8>>,
    iterations: u32,
    memory_kib: u32,
    parallelism: u32,
    length: u32,
) -> Result<Vec<u8>, PrimitiveError> {
    let seed = Zeroizing::new(seed);
    let salt = Zeroizing::new(salt);
    let secret = Zeroizing::new(secret.unwrap_or_default());
    let associated_data = Zeroizing::new(associated_data.unwrap_or_default());
    if iterations == 0 || parallelism == 0 {
        return Err(PrimitiveError::InvalidArgument);
    }
    if iterations > MAX_ARGON2_ITERATIONS
        || memory_kib > MAX_ARGON2_MEMORY_KIB
        || parallelism > MAX_ARGON2_PARALLELISM
    {
        return Err(PrimitiveError::ResourceLimit);
    }
    let length = bounded_output_length(length)?;
    // Preserve Keyguard's existing minimum Argon2 output length.
    if length < 4 {
        return Err(PrimitiveError::InvalidArgument);
    }
    let algorithm = match mode {
        Argon2Mode::D => Algorithm::Argon2d,
        Argon2Mode::I => Algorithm::Argon2i,
        Argon2Mode::Id => Algorithm::Argon2id,
        Argon2Mode::Unspecified => return Err(PrimitiveError::InvalidArgument),
    };
    let mut output = vec![0_u8; length];
    // All inputs use the reviewed local implementation so every BLAKE2
    // context follows the guaranteed-erasure backend. The implementation's
    // equivalence test locks standard inputs to RustCrypto Argon2 0.5.3.
    let result = crate::argon2_compat::hash_password_into(
        algorithm,
        version,
        &seed,
        &salt,
        &secret,
        &associated_data,
        memory_kib,
        iterations,
        parallelism,
        &mut output,
    );
    if let Err(error) = result {
        output.zeroize();
        return Err(error);
    }
    Ok(output)
}

pub(crate) fn stream_cipher_xor_at_offset(
    algorithm: StreamCipherAlgorithm,
    key: Vec<u8>,
    nonce: Vec<u8>,
    offset: u64,
    data: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let key = Zeroizing::new(key);
    let nonce = Zeroizing::new(nonce);
    let mut data = Zeroizing::new(data);
    match algorithm {
        StreamCipherAlgorithm::Salsa20 => {
            let mut cipher = Salsa20::new_from_slices(&key, &nonce)
                .map_err(|_| PrimitiveError::InvalidArgument)?;
            cipher
                .try_seek(offset)
                .map_err(|_| PrimitiveError::InvalidArgument)?;
            cipher
                .try_apply_keystream(&mut data)
                .map_err(|_| PrimitiveError::InvalidArgument)?;
        }
        StreamCipherAlgorithm::Chacha20 => {
            let mut cipher = ChaCha20::new_from_slices(&key, &nonce)
                .map_err(|_| PrimitiveError::InvalidArgument)?;
            cipher
                .try_seek(offset)
                .map_err(|_| PrimitiveError::InvalidArgument)?;
            cipher
                .try_apply_keystream(&mut data)
                .map_err(|_| PrimitiveError::InvalidArgument)?;
        }
        StreamCipherAlgorithm::Unspecified => return Err(PrimitiveError::InvalidArgument),
    }
    Ok(data.to_vec())
}

pub(crate) fn twofish_cbc_pkcs7(
    direction: CipherDirection,
    key: Vec<u8>,
    iv: Vec<u8>,
    data: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let key = Zeroizing::new(key);
    let iv = Zeroizing::new(iv);
    let data = Zeroizing::new(data);
    if direction == CipherDirection::Unspecified || iv.len() != AES_BLOCK_BYTES {
        return Err(PrimitiveError::InvalidArgument);
    }
    let cipher = Twofish::new_from_slice(&key).map_err(|_| PrimitiveError::InvalidArgument)?;
    match direction {
        CipherDirection::Encrypt => {
            let output_length = data
                .len()
                .checked_div(AES_BLOCK_BYTES)
                .and_then(|blocks| blocks.checked_add(1))
                .and_then(|blocks| blocks.checked_mul(AES_BLOCK_BYTES))
                .ok_or(PrimitiveError::ResourceLimit)?;
            let mut output = Zeroizing::new(vec![0_u8; output_length]);
            let encryptor = Encryptor::<Twofish>::inner_iv_slice_init(cipher, &iv)
                .map_err(|_| PrimitiveError::InvalidArgument)?;
            let encrypted = encryptor
                .encrypt_padded_b2b_mut::<Pkcs7>(&data, &mut output)
                .map_err(|_| PrimitiveError::CryptoFailure)?;
            Ok(encrypted.to_vec())
        }
        CipherDirection::Decrypt => {
            if data.is_empty() || !data.len().is_multiple_of(AES_BLOCK_BYTES) {
                return Err(PrimitiveError::InvalidArgument);
            }
            let mut output = Zeroizing::new(vec![0_u8; data.len()]);
            let decryptor = Decryptor::<Twofish>::inner_iv_slice_init(cipher, &iv)
                .map_err(|_| PrimitiveError::InvalidArgument)?;
            decryptor
                .decrypt_padded_b2b_mut::<NoPadding>(&data, &mut output)
                .map_err(|_| PrimitiveError::AuthenticationFailed)?;
            let final_block = &output[output.len() - AES_BLOCK_BYTES..];
            let final_length = pkcs7_unpadded_block_length(final_block)
                .ok_or(PrimitiveError::AuthenticationFailed)?;
            let plaintext_length = output.len() - AES_BLOCK_BYTES + final_length;
            Ok(output[..plaintext_length].to_vec())
        }
        CipherDirection::Unspecified => Err(PrimitiveError::InvalidArgument),
    }
}

pub(crate) fn ssh_agent_tcp_chacha20_poly1305(
    direction: CipherDirection,
    key: Vec<u8>,
    nonce: Vec<u8>,
    header: Vec<u8>,
    payload: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let key = Zeroizing::new(key);
    let nonce = Zeroizing::new(nonce);
    let header = Zeroizing::new(header);
    let mut payload = Zeroizing::new(payload);
    if key.len() != SSH_AGENT_TCP_KEY_BYTES
        || nonce.len() != SSH_AGENT_TCP_NONCE_BYTES
        || header.len() != SSH_AGENT_TCP_HEADER_BYTES
    {
        return Err(PrimitiveError::InvalidArgument);
    }
    match direction {
        CipherDirection::Encrypt if payload.len() > MAX_SSH_AGENT_TCP_PAYLOAD_BYTES => {
            return Err(PrimitiveError::ResourceLimit);
        }
        CipherDirection::Decrypt
            if !(SSH_AGENT_TCP_TAG_BYTES
                ..=MAX_SSH_AGENT_TCP_PAYLOAD_BYTES + SSH_AGENT_TCP_TAG_BYTES)
                .contains(&payload.len()) =>
        {
            return Err(
                if payload.len() > MAX_SSH_AGENT_TCP_PAYLOAD_BYTES + SSH_AGENT_TCP_TAG_BYTES {
                    PrimitiveError::ResourceLimit
                } else {
                    PrimitiveError::InvalidArgument
                },
            );
        }
        CipherDirection::Unspecified => return Err(PrimitiveError::InvalidArgument),
        CipherDirection::Encrypt | CipherDirection::Decrypt => {}
    }

    chacha20_poly1305_in_place(direction, &key, &nonce, &header, &mut payload)?;
    Ok(payload.to_vec())
}

pub(crate) fn chacha20_poly1305_in_place(
    direction: CipherDirection,
    key: &[u8],
    nonce: &[u8],
    associated_data: &[u8],
    payload: &mut Vec<u8>,
) -> Result<(), PrimitiveError> {
    let unbound_key =
        UnboundKey::new(&CHACHA20_POLY1305, key).map_err(|_| PrimitiveError::InvalidArgument)?;
    let cipher = LessSafeKey::new(unbound_key);
    let nonce =
        Nonce::try_assume_unique_for_key(nonce).map_err(|_| PrimitiveError::InvalidArgument)?;
    match direction {
        CipherDirection::Encrypt => cipher
            .seal_in_place_append_tag(nonce, Aad::from(associated_data), payload)
            .map_err(|_| PrimitiveError::CryptoFailure)?,
        CipherDirection::Decrypt => {
            let plaintext = cipher
                .open_in_place(nonce, Aad::from(associated_data), payload)
                .map_err(|_| PrimitiveError::AuthenticationFailed)?;
            let plaintext_length = plaintext.len();
            payload.truncate(plaintext_length);
        }
        CipherDirection::Unspecified => return Err(PrimitiveError::InvalidArgument),
    }
    Ok(())
}

pub(crate) fn rsa_oaep_decrypt(
    hash: RsaOaepHash,
    private_key_pkcs8: Vec<u8>,
    ciphertext: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let private_key_pkcs8 = Zeroizing::new(private_key_pkcs8);
    let ciphertext = Zeroizing::new(ciphertext);
    validate_rsa_pkcs8(&private_key_pkcs8)?;
    let private_key = PrivateDecryptingKey::from_pkcs8(&private_key_pkcs8)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let private_key =
        OaepPrivateDecryptingKey::new(private_key).map_err(|_| PrimitiveError::CryptoFailure)?;
    if ciphertext.len() > private_key.key_size_bytes() {
        return Err(PrimitiveError::AuthenticationFailed);
    }
    let algorithm: &'static OaepAlgorithm = match hash {
        RsaOaepHash::Sha1 => &OAEP_SHA1_MGF1SHA1,
        RsaOaepHash::Sha256 => &OAEP_SHA256_MGF1SHA256,
        RsaOaepHash::Unspecified => return Err(PrimitiveError::InvalidArgument),
    };
    // Bouncy Castle accepts a positive RSA integer shorter than the modulus.
    // Preserve that serialized-input behavior by restoring omitted leading
    // zero octets before calling AWS-LC's fixed-width interface.
    let mut fixed_width_ciphertext = Zeroizing::new(vec![0_u8; private_key.key_size_bytes()]);
    let ciphertext_offset = fixed_width_ciphertext.len() - ciphertext.len();
    fixed_width_ciphertext[ciphertext_offset..].copy_from_slice(&ciphertext);
    let mut plaintext = Zeroizing::new(vec![0_u8; private_key.min_output_size()]);
    let plaintext_length = private_key
        .decrypt(algorithm, &fixed_width_ciphertext, &mut plaintext, None)
        .map_err(|_| PrimitiveError::AuthenticationFailed)?
        .len();
    Ok(plaintext[..plaintext_length].to_vec())
}

pub(crate) fn rsa_oaep_encrypt(
    hash: RsaOaepHash,
    public_key_spki: Vec<u8>,
    plaintext: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let public_key_spki = Zeroizing::new(public_key_spki);
    let plaintext = Zeroizing::new(plaintext);
    validate_rsa_spki(&public_key_spki)?;
    let public_key = PublicEncryptingKey::from_der(&public_key_spki)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let public_key =
        OaepPublicEncryptingKey::new(public_key).map_err(|_| PrimitiveError::CryptoFailure)?;
    let algorithm = rsa_oaep_algorithm(hash)?;
    if plaintext.len() > public_key.max_plaintext_size(algorithm) {
        return Err(PrimitiveError::InvalidArgument);
    }
    let mut ciphertext = Zeroizing::new(vec![0_u8; public_key.ciphertext_size()]);
    let ciphertext_length = public_key
        .encrypt(algorithm, &plaintext, &mut ciphertext, None)
        .map_err(|_| PrimitiveError::CryptoFailure)?
        .len();
    Ok(ciphertext[..ciphertext_length].to_vec())
}

pub(crate) fn rsa_pkcs8_to_spki(private_key_pkcs8: Vec<u8>) -> Result<Vec<u8>, PrimitiveError> {
    let private_key_pkcs8 = Zeroizing::new(private_key_pkcs8);
    validate_rsa_pkcs8(&private_key_pkcs8)?;
    let private_key = PrivateDecryptingKey::from_pkcs8(&private_key_pkcs8)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let public_key = private_key.public_key();
    let spki: PublicKeyX509Der<'static> = public_key
        .as_der()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    Ok(spki.as_ref().to_vec())
}

fn validate_rsa_pkcs8(input: &[u8]) -> Result<(), PrimitiveError> {
    let private_key =
        PrivateKeyInfo::from_der(input).map_err(|_| PrimitiveError::InvalidArgument)?;
    private_key
        .algorithm
        .assert_algorithm_oid(RSA_ENCRYPTION_OID)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    Ok(())
}

fn validate_rsa_spki(input: &[u8]) -> Result<(), PrimitiveError> {
    let public_key =
        SubjectPublicKeyInfoRef::from_der(input).map_err(|_| PrimitiveError::InvalidArgument)?;
    public_key
        .algorithm
        .assert_algorithm_oid(RSA_ENCRYPTION_OID)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    Ok(())
}

fn rsa_oaep_algorithm(hash: RsaOaepHash) -> Result<&'static OaepAlgorithm, PrimitiveError> {
    match hash {
        RsaOaepHash::Sha1 => Ok(&OAEP_SHA1_MGF1SHA1),
        RsaOaepHash::Sha256 => Ok(&OAEP_SHA256_MGF1SHA256),
        RsaOaepHash::Unspecified => Err(PrimitiveError::InvalidArgument),
    }
}

pub(crate) fn random_bytes(length: u32) -> Result<Vec<u8>, PrimitiveError> {
    let length = bounded_output_length(length)?;
    let mut output = vec![0_u8; length];
    if rand::fill(&mut output).is_err() {
        output.zeroize();
        return Err(PrimitiveError::CryptoFailure);
    }
    Ok(output)
}

pub(crate) fn random_int(bounded: bool, exclusive_upper_bound: u32) -> Result<i32, PrimitiveError> {
    if !bounded {
        return Ok(i32::from_ne_bytes(random_u32()?.to_ne_bytes()));
    }
    if exclusive_upper_bound == 0 || exclusive_upper_bound > i32::MAX as u32 {
        return Err(PrimitiveError::InvalidArgument);
    }

    // Rejection sampling preserves java.security.SecureRandom#nextInt(bound)
    // semantics without modulo bias.
    let bound = u64::from(exclusive_upper_bound);
    let range = u64::from(u32::MAX) + 1;
    let limit = range - range % bound;
    loop {
        let value = u64::from(random_u32()?);
        if value < limit {
            return i32::try_from(value % bound).map_err(|_| PrimitiveError::CryptoFailure);
        }
    }
}

pub(crate) fn random_ints(
    bounded: bool,
    exclusive_upper_bound: u32,
    count: u32,
) -> Result<Vec<u8>, PrimitiveError> {
    if count > MAX_RANDOM_INT_BATCH {
        return Err(PrimitiveError::ResourceLimit);
    }
    if bounded && (exclusive_upper_bound == 0 || exclusive_upper_bound > i32::MAX as u32) {
        return Err(PrimitiveError::InvalidArgument);
    }

    let count = usize::try_from(count).map_err(|_| PrimitiveError::ResourceLimit)?;
    let output_length = count
        .checked_mul(size_of::<i32>())
        .ok_or(PrimitiveError::ResourceLimit)?;
    let mut output = Vec::with_capacity(output_length);
    if !bounded {
        output.resize(output_length, 0);
        if rand::fill(&mut output).is_err() {
            output.zeroize();
            return Err(PrimitiveError::CryptoFailure);
        }
        return Ok(output);
    }

    // Rejection sampling preserves SecureRandom.nextInt(bound) semantics
    // without modulo bias. Values are serialized as little-endian i32s.
    let bound = u64::from(exclusive_upper_bound);
    let range = u64::from(u32::MAX) + 1;
    let limit = range - range % bound;
    while output.len() < output_length {
        let remaining_values = (output_length - output.len()) / size_of::<i32>();
        let candidate_count = remaining_values.min(RANDOM_INT_CANDIDATES_PER_FILL);
        let mut candidates = Zeroizing::new(vec![0_u8; candidate_count * size_of::<u32>()]);
        if rand::fill(candidates.as_mut_slice()).is_err() {
            output.zeroize();
            return Err(PrimitiveError::CryptoFailure);
        }
        for candidate in candidates.chunks_exact(size_of::<u32>()) {
            let value = u64::from(u32::from_le_bytes([
                candidate[0],
                candidate[1],
                candidate[2],
                candidate[3],
            ]));
            if value < limit {
                let bounded_value =
                    u32::try_from(value % bound).map_err(|_| PrimitiveError::CryptoFailure)?;
                output.extend_from_slice(&bounded_value.to_le_bytes());
                if output.len() == output_length {
                    break;
                }
            }
        }
    }
    Ok(output)
}

pub(crate) fn hmac(
    algorithm: HashAlgorithm,
    key: Vec<u8>,
    data: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let key = Zeroizing::new(key);
    let data = Zeroizing::new(data);
    let algorithm = sensitive_algorithm(algorithm)?;
    sensitive_hmac_bytes(algorithm, &key, &data)
}

pub(crate) fn digest(algorithm: HashAlgorithm, data: Vec<u8>) -> Result<Vec<u8>, PrimitiveError> {
    let data = Zeroizing::new(data);
    let algorithm = sensitive_algorithm(algorithm)?;
    sensitive_digest_bytes(algorithm, &data)
}

pub(crate) fn aes_ecb_no_padding_encrypt(
    key: Vec<u8>,
    data: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let key = Zeroizing::new(key);
    let mut data = Zeroizing::new(data);
    if !data.len().is_multiple_of(AES_BLOCK_BYTES) {
        return Err(PrimitiveError::InvalidArgument);
    }
    match key.len() {
        16 => aes_ecb_encrypt::<Aes128>(&key, &mut data)?,
        24 => aes_ecb_encrypt::<Aes192>(&key, &mut data)?,
        32 => aes_ecb_encrypt::<Aes256>(&key, &mut data)?,
        _ => return Err(PrimitiveError::InvalidArgument),
    }
    Ok(data.to_vec())
}

pub(crate) fn aes_ecb_no_padding_transform(
    key: Vec<u8>,
    data: Vec<u8>,
    rounds: u32,
) -> Result<Vec<u8>, PrimitiveError> {
    let key = Zeroizing::new(key);
    let mut data = Zeroizing::new(data);
    // Preserve the original KDBX `repeat(0)` behavior: no AES operation is
    // attempted, so neither the seed key nor block shape is inspected.
    if rounds == 0 {
        return Ok(data.to_vec());
    }
    if rounds > MAX_AES_TRANSFORM_ROUNDS {
        return Err(PrimitiveError::ResourceLimit);
    }
    if !data.len().is_multiple_of(AES_BLOCK_BYTES) {
        return Err(PrimitiveError::InvalidArgument);
    }
    let block_count = data.len() / AES_BLOCK_BYTES;
    let block_operations = u64::from(rounds)
        .checked_mul(u64::try_from(block_count).map_err(|_| PrimitiveError::ResourceLimit)?)
        .ok_or(PrimitiveError::ResourceLimit)?;
    if block_operations > MAX_AES_TRANSFORM_BLOCK_OPERATIONS {
        return Err(PrimitiveError::ResourceLimit);
    }
    if data.is_empty() {
        return match key.len() {
            16 | 24 | 32 => Ok(Vec::new()),
            _ => Err(PrimitiveError::InvalidArgument),
        };
    }
    match key.len() {
        16 => aes_ecb_encrypt_rounds::<Aes128>(&key, &mut data, rounds)?,
        24 => aes_ecb_encrypt_rounds::<Aes192>(&key, &mut data, rounds)?,
        32 => aes_ecb_encrypt_rounds::<Aes256>(&key, &mut data, rounds)?,
        _ => return Err(PrimitiveError::InvalidArgument),
    }
    Ok(data.to_vec())
}

pub(crate) fn aes_cbc_pkcs7(
    direction: CipherDirection,
    key: Vec<u8>,
    iv: Vec<u8>,
    data: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let key = Zeroizing::new(key);
    let iv = Zeroizing::new(iv);
    let mut data = Zeroizing::new(data);
    if iv.len() != AES_BLOCK_BYTES {
        return Err(PrimitiveError::InvalidArgument);
    }
    match direction {
        CipherDirection::Encrypt => match key.len() {
            16 => cbc_encrypt::<Aes128>(&key, &iv, &mut data),
            24 => cbc_encrypt::<Aes192>(&key, &iv, &mut data),
            32 => cbc_encrypt::<Aes256>(&key, &iv, &mut data),
            _ => Err(PrimitiveError::InvalidArgument),
        },
        CipherDirection::Decrypt => match key.len() {
            16 => cbc_decrypt::<Aes128>(&key, &iv, &mut data),
            24 => cbc_decrypt::<Aes192>(&key, &iv, &mut data),
            32 => cbc_decrypt::<Aes256>(&key, &iv, &mut data),
            _ => Err(PrimitiveError::InvalidArgument),
        },
        CipherDirection::Unspecified => Err(PrimitiveError::InvalidArgument),
    }
}

pub(crate) fn aes_cbc_pkcs7_hmac_sha256_encrypt(
    encryption_key: Vec<u8>,
    mac_key: Vec<u8>,
    iv: Vec<u8>,
    plaintext: Vec<u8>,
) -> Result<(Vec<u8>, Vec<u8>), PrimitiveError> {
    let encryption_key = Zeroizing::new(encryption_key);
    let mac_key = Zeroizing::new(mac_key);
    let iv = Zeroizing::new(iv);
    let plaintext = Zeroizing::new(plaintext);
    let padded_length = aes_cbc_pkcs7_hmac_sha256_ciphertext_length(plaintext.len())?;
    let mut ciphertext = vec![0_u8; padded_length];
    let mut mac = vec![0_u8; HMAC_SHA256_BYTES];
    if let Err(error) = aes_cbc_pkcs7_hmac_sha256_encrypt_into(
        &encryption_key,
        &mac_key,
        &iv,
        &plaintext,
        &mut ciphertext,
        &mut mac,
    ) {
        ciphertext.zeroize();
        mac.zeroize();
        return Err(error);
    }
    Ok((ciphertext, mac))
}

pub(crate) fn aes_cbc_pkcs7_hmac_sha256_decrypt(
    encryption_key: Vec<u8>,
    mac_key: Vec<u8>,
    iv: Vec<u8>,
    ciphertext: Vec<u8>,
    expected_mac: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let encryption_key = Zeroizing::new(encryption_key);
    let mac_key = Zeroizing::new(mac_key);
    let iv = Zeroizing::new(iv);
    let ciphertext = Zeroizing::new(ciphertext);
    let expected_mac = Zeroizing::new(expected_mac);
    let mut plaintext = Zeroizing::new(vec![0_u8; ciphertext.len()]);
    let plaintext_length = aes_cbc_pkcs7_hmac_sha256_decrypt_into(
        &encryption_key,
        &mac_key,
        &iv,
        &ciphertext,
        &expected_mac,
        &mut plaintext,
    )?;
    Ok(plaintext[..plaintext_length].to_vec())
}

pub(crate) fn aes_cbc_pkcs7_hmac_sha256_ciphertext_length(
    plaintext_length: usize,
) -> Result<usize, PrimitiveError> {
    let padded_length = plaintext_length
        .checked_add(AES_BLOCK_BYTES)
        .map(|length| length / AES_BLOCK_BYTES * AES_BLOCK_BYTES)
        .ok_or(PrimitiveError::ResourceLimit)?;
    if padded_length > isize::MAX as usize {
        return Err(PrimitiveError::ResourceLimit);
    }
    Ok(padded_length)
}

pub(crate) fn aes_cbc_pkcs7_hmac_sha256_encrypt_into(
    encryption_key: &[u8],
    mac_key: &[u8],
    iv: &[u8],
    plaintext: &[u8],
    ciphertext_out: &mut [u8],
    mac_out: &mut [u8],
) -> Result<usize, PrimitiveError> {
    let padded_length = aes_cbc_pkcs7_hmac_sha256_ciphertext_length(plaintext.len())?;
    if !matches!(encryption_key.len(), 16 | 24 | 32)
        || iv.len() != AES_BLOCK_BYTES
        || ciphertext_out.len() != padded_length
        || mac_out.len() != HMAC_SHA256_BYTES
    {
        return Err(PrimitiveError::InvalidArgument);
    }

    ciphertext_out[..plaintext.len()].copy_from_slice(plaintext);
    ciphertext_out[plaintext.len()..].fill(0);
    match encryption_key.len() {
        16 => cbc_encrypt_into::<Aes128>(encryption_key, iv, plaintext.len(), ciphertext_out),
        24 => cbc_encrypt_into::<Aes192>(encryption_key, iv, plaintext.len(), ciphertext_out),
        32 => cbc_encrypt_into::<Aes256>(encryption_key, iv, plaintext.len(), ciphertext_out),
        _ => return Err(PrimitiveError::InvalidArgument),
    }?;
    hmac_sha256_iv_ciphertext_into(mac_key, iv, ciphertext_out, mac_out)?;
    Ok(padded_length)
}

pub(crate) fn aes_cbc_pkcs7_hmac_sha256_decrypt_into(
    encryption_key: &[u8],
    mac_key: &[u8],
    iv: &[u8],
    ciphertext: &[u8],
    expected_mac: &[u8],
    plaintext_out: &mut [u8],
) -> Result<usize, PrimitiveError> {
    if ciphertext.len() > isize::MAX as usize {
        return Err(PrimitiveError::ResourceLimit);
    }
    if plaintext_out.len() != ciphertext.len() {
        return Err(PrimitiveError::InvalidArgument);
    }

    let mut actual_mac = Zeroizing::new([0_u8; HMAC_SHA256_BYTES]);
    hmac_sha256_iv_ciphertext_into(mac_key, iv, ciphertext, actual_mac.as_mut_slice())?;
    constant_time::verify_slices_are_equal(actual_mac.as_slice(), expected_mac)
        .map_err(|_| PrimitiveError::AuthenticationFailed)?;

    if !matches!(encryption_key.len(), 16 | 24 | 32)
        || iv.len() != AES_BLOCK_BYTES
        || ciphertext.is_empty()
        || !ciphertext.len().is_multiple_of(AES_BLOCK_BYTES)
    {
        return Err(PrimitiveError::InvalidArgument);
    }
    plaintext_out.copy_from_slice(ciphertext);
    let plaintext_length = match encryption_key.len() {
        16 => cbc_decrypt_into::<Aes128>(encryption_key, iv, plaintext_out),
        24 => cbc_decrypt_into::<Aes192>(encryption_key, iv, plaintext_out),
        32 => cbc_decrypt_into::<Aes256>(encryption_key, iv, plaintext_out),
        _ => return Err(PrimitiveError::InvalidArgument),
    }?;
    plaintext_out[plaintext_length..].zeroize();
    Ok(plaintext_length)
}

fn hmac_sha256_iv_ciphertext_into(
    mac_key: &[u8],
    iv: &[u8],
    ciphertext: &[u8],
    output: &mut [u8],
) -> Result<(), PrimitiveError> {
    if output.len() != HMAC_SHA256_BYTES {
        return Err(PrimitiveError::InvalidArgument);
    }
    let mut mac =
        HmacContext::new(DigestAlgorithm::Sha256, mac_key).map_err(sensitive_backend_error)?;
    mac.update(iv).map_err(sensitive_backend_error)?;
    mac.update(ciphertext).map_err(sensitive_backend_error)?;
    mac.finalize_into(output).map_err(sensitive_backend_error)
}

fn bounded_output_length(length: u32) -> Result<usize, PrimitiveError> {
    let length = usize::try_from(length).map_err(|_| PrimitiveError::ResourceLimit)?;
    if length > MAX_OUTPUT_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }
    Ok(length)
}

fn random_u32() -> Result<u32, PrimitiveError> {
    let mut bytes = Zeroizing::new([0_u8; 4]);
    rand::fill(bytes.as_mut_slice()).map_err(|_| PrimitiveError::CryptoFailure)?;
    Ok(u32::from_ne_bytes(*bytes))
}

fn sensitive_digest_bytes(
    algorithm: DigestAlgorithm,
    data: &[u8],
) -> Result<Vec<u8>, PrimitiveError> {
    let mut output = Zeroizing::new(vec![0_u8; algorithm.output_size()]);
    let mut digest = DigestContext::new(algorithm).map_err(sensitive_backend_error)?;
    digest.update(data).map_err(sensitive_backend_error)?;
    digest
        .finalize_into(&mut output)
        .map_err(sensitive_backend_error)?;
    Ok(output.to_vec())
}

fn sensitive_hmac_bytes(
    algorithm: DigestAlgorithm,
    key: &[u8],
    data: &[u8],
) -> Result<Vec<u8>, PrimitiveError> {
    let mut output = Zeroizing::new(vec![0_u8; algorithm.output_size()]);
    let mut mac = HmacContext::new(algorithm, key).map_err(sensitive_backend_error)?;
    mac.update(data).map_err(sensitive_backend_error)?;
    mac.finalize_into(&mut output)
        .map_err(sensitive_backend_error)?;
    Ok(output.to_vec())
}

fn sensitive_algorithm(algorithm: HashAlgorithm) -> Result<DigestAlgorithm, PrimitiveError> {
    match algorithm {
        HashAlgorithm::Sha1 => Ok(DigestAlgorithm::Sha1),
        HashAlgorithm::Sha256 => Ok(DigestAlgorithm::Sha256),
        HashAlgorithm::Sha512 => Ok(DigestAlgorithm::Sha512),
        HashAlgorithm::Md5 => Ok(DigestAlgorithm::Md5),
        HashAlgorithm::Unspecified => Err(PrimitiveError::InvalidArgument),
    }
}

fn sensitive_backend_error(_: SensitiveBackendError) -> PrimitiveError {
    PrimitiveError::CryptoFailure
}

fn aes_ecb_encrypt<C>(key: &[u8], data: &mut [u8]) -> Result<(), PrimitiveError>
where
    C: BlockEncrypt + KeyInit + cipher::BlockSizeUser<BlockSize = U16>,
{
    aes_ecb_encrypt_rounds::<C>(key, data, 1)
}

fn aes_ecb_encrypt_rounds<C>(key: &[u8], data: &mut [u8], rounds: u32) -> Result<(), PrimitiveError>
where
    C: BlockEncrypt + KeyInit + cipher::BlockSizeUser<BlockSize = U16>,
{
    let cipher = C::new_from_slice(key).map_err(|_| PrimitiveError::InvalidArgument)?;
    for _ in 0..rounds {
        for chunk in data.chunks_exact_mut(AES_BLOCK_BYTES) {
            cipher.encrypt_block(Block::<C>::from_mut_slice(chunk));
        }
    }
    Ok(())
}

fn cbc_encrypt_into<C>(
    key: &[u8],
    iv: &[u8],
    message_length: usize,
    output: &mut [u8],
) -> Result<usize, PrimitiveError>
where
    C: BlockEncrypt + KeyInit + cipher::BlockCipher + cipher::BlockSizeUser<BlockSize = U16>,
{
    let cipher =
        Encryptor::<C>::new_from_slices(key, iv).map_err(|_| PrimitiveError::InvalidArgument)?;
    cipher
        .encrypt_padded_mut::<Pkcs7>(output, message_length)
        .map(|ciphertext| ciphertext.len())
        .map_err(|_| PrimitiveError::CryptoFailure)
}

fn cbc_decrypt_into<C>(key: &[u8], iv: &[u8], output: &mut [u8]) -> Result<usize, PrimitiveError>
where
    C: cipher::BlockDecrypt
        + KeyInit
        + cipher::BlockCipher
        + cipher::BlockSizeUser<BlockSize = U16>,
{
    let cipher =
        Decryptor::<C>::new_from_slices(key, iv).map_err(|_| PrimitiveError::InvalidArgument)?;
    cipher
        .decrypt_padded_mut::<NoPadding>(output)
        .map_err(|_| PrimitiveError::AuthenticationFailed)?;
    let final_block = &output[output.len() - AES_BLOCK_BYTES..];
    let final_length =
        pkcs7_unpadded_block_length(final_block).ok_or(PrimitiveError::AuthenticationFailed)?;
    Ok(output.len() - AES_BLOCK_BYTES + final_length)
}

fn cbc_encrypt<C>(key: &[u8], iv: &[u8], data: &mut Vec<u8>) -> Result<Vec<u8>, PrimitiveError>
where
    C: BlockEncrypt + KeyInit + cipher::BlockCipher + cipher::BlockSizeUser<BlockSize = U16>,
{
    let cipher =
        Encryptor::<C>::new_from_slices(key, iv).map_err(|_| PrimitiveError::InvalidArgument)?;
    let message_length = data.len();
    let padded_length = message_length
        .checked_add(AES_BLOCK_BYTES)
        .map(|length| length / AES_BLOCK_BYTES * AES_BLOCK_BYTES)
        .ok_or(PrimitiveError::ResourceLimit)?;
    data.resize(padded_length, 0);
    cipher
        .encrypt_padded_mut::<Pkcs7>(data, message_length)
        .map(<[u8]>::to_vec)
        .map_err(|_| PrimitiveError::CryptoFailure)
}

fn cbc_decrypt<C>(key: &[u8], iv: &[u8], data: &mut [u8]) -> Result<Vec<u8>, PrimitiveError>
where
    C: cipher::BlockDecrypt
        + KeyInit
        + cipher::BlockCipher
        + cipher::BlockSizeUser<BlockSize = U16>,
{
    if data.is_empty() || !data.len().is_multiple_of(AES_BLOCK_BYTES) {
        return Err(PrimitiveError::InvalidArgument);
    }
    let cipher =
        Decryptor::<C>::new_from_slices(key, iv).map_err(|_| PrimitiveError::InvalidArgument)?;
    cipher
        .decrypt_padded_mut::<NoPadding>(data)
        .map_err(|_| PrimitiveError::AuthenticationFailed)?;
    let final_block = &data[data.len() - AES_BLOCK_BYTES..];
    let final_length =
        pkcs7_unpadded_block_length(final_block).ok_or(PrimitiveError::AuthenticationFailed)?;
    let plaintext_length = data.len() - AES_BLOCK_BYTES + final_length;
    Ok(data[..plaintext_length].to_vec())
}
