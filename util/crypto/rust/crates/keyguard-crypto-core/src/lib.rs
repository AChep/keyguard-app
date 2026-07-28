//! Safe cryptographic core and versioned protobuf dispatcher for Keyguard.
//!
//! The crate contains no foreign-language boundary and forbids unsafe Rust.

#![forbid(unsafe_code)]

mod argon2_compat;
pub mod fast;
mod legacy_pem;
mod openpgp_agent;
mod openpgp_mutation;
mod openpgp_packets;
mod openpgp_read;
mod openpgp_write;
mod padding;
mod pkcs8_pbes2;
mod primitives;
pub mod protocol;
mod sessions;
mod ssh_import;
mod ssh_keys;

use prost::Message;
use std::sync::Once;
use zeroize::Zeroize;

use primitives::PrimitiveError;
use protocol::{
    Argon2Mode, CipherDirection, HashAlgorithm, NativeErrorCode, NativeRequest, NativeResponse,
    NativeStatus, NativeStreamOpenRequest, RsaOaepHash, SshKeyType, StreamCipherAlgorithm,
    native_request, native_response, native_stream_open_request,
};
use sessions::SessionError;

/// Native function ABI version.
pub const ABI_VERSION: u32 = 1;
/// Protobuf request/response protocol version.
pub const PROTOCOL_VERSION: u32 = 1;
/// Maximum encoded request or response envelope size (16 MiB).
pub const MAX_CONTROL_ENVELOPE_BYTES: usize = 16 * 1024 * 1024;
/// Maximum raw streaming update size (64 KiB).
pub const MAX_STREAM_CHUNK_BYTES: usize = 64 * 1024;

/// HKDF-SHA256 capability bit.
pub const CAPABILITY_HKDF_SHA256: u64 = 1 << 0;
/// PBKDF2-HMAC-SHA256 capability bit.
pub const CAPABILITY_PBKDF2_SHA256: u64 = 1 << 1;
/// Argon2 v1.3 capability bit.
pub const CAPABILITY_ARGON2: u64 = 1 << 2;
/// Secure-random capability bit.
pub const CAPABILITY_RANDOM: u64 = 1 << 3;
/// HMAC capability bit.
pub const CAPABILITY_HMAC: u64 = 1 << 4;
/// Message-digest capability bit.
pub const CAPABILITY_DIGEST: u64 = 1 << 5;
/// AES-ECB/CBC capability bit.
pub const CAPABILITY_AES: u64 = 1 << 6;
/// Generation-tagged streaming-session capability bit.
pub const CAPABILITY_STREAMING: u64 = 1 << 7;
/// KDBX Argon2 v1.0/v1.3, secret, and associated-data capability bit.
pub const CAPABILITY_KDBX_ARGON2: u64 = 1 << 8;
/// Stateless Salsa20/ChaCha20 offset transform capability bit.
pub const CAPABILITY_STREAM_CIPHER_XOR_AT_OFFSET: u64 = 1 << 9;
/// Twofish-CBC-PKCS#7 one-shot and streaming capability bit.
pub const CAPABILITY_TWOFISH_CBC_PKCS7: u64 = 1 << 10;
/// RSA-OAEP SHA-1/SHA-256 encryption and decryption capability bit.
pub const CAPABILITY_RSA_OAEP: u64 = 1 << 11;
/// PKCS#8 RSA parsing and SubjectPublicKeyInfo emission capability bit.
pub const CAPABILITY_RSA_KEY_FORMATS: u64 = 1 << 12;
/// SSH-agent TCP ChaCha20-Poly1305 framing capability bit.
pub const CAPABILITY_SSH_AGENT_TCP_CHACHA20_POLY1305: u64 = 1 << 13;
/// RSA/Ed25519 SSH key generation, parsing, formatting, and fingerprint capability bit.
pub const CAPABILITY_SSH_KEYS: u64 = 1 << 14;
/// RSA/Ed25519 SSH-agent signing capability bit.
pub const CAPABILITY_SSH_AGENT_SIGNING: u64 = 1 << 15;
/// OpenSSH, PEM, and PuTTY SSH private-key import capability bit.
pub const CAPABILITY_SSH_PRIVATE_KEY_IMPORT: u64 = 1 << 16;
/// OpenPGP armor, packet, certificate-policy, metadata, and verification read path.
pub const CAPABILITY_OPENPGP_READ: u64 = 1 << 17;
/// OpenPGP v4 key generation/import, signing, encryption, and authenticated decryption.
pub const CAPABILITY_OPENPGP_WRITE: u64 = 1 << 18;
/// OpenPGP expiration mutation and gpg-agent private-operation capability.
pub const CAPABILITY_OPENPGP_MUTATION_AGENT: u64 = 1 << 19;
/// Fused AES-CBC-PKCS#7 encryption/decryption with HMAC-SHA256 authentication.
pub const CAPABILITY_AES_CBC_HMAC_SHA256: u64 = 1 << 20;
/// Fixed-shape caller-owned-output ABI for the fused AES-CBC/HMAC operation.
pub const CAPABILITY_AES_CBC_HMAC_SHA256_FAST_PATH: u64 = 1 << 21;
/// Fused AES-CBC/HMAC generation-tagged streaming sessions.
pub const CAPABILITY_AES_CBC_HMAC_SHA256_STREAMING: u64 = 1 << 22;
/// Fixed-shape scalar ABI for secure random integers.
pub const CAPABILITY_RANDOM_FAST_PATH: u64 = 1 << 23;
/// Complete capability set provided by this native library revision.
pub const CAPABILITIES: u64 = CAPABILITY_HKDF_SHA256
    | CAPABILITY_PBKDF2_SHA256
    | CAPABILITY_ARGON2
    | CAPABILITY_RANDOM
    | CAPABILITY_HMAC
    | CAPABILITY_DIGEST
    | CAPABILITY_AES
    | CAPABILITY_STREAMING
    | CAPABILITY_KDBX_ARGON2
    | CAPABILITY_STREAM_CIPHER_XOR_AT_OFFSET
    | CAPABILITY_TWOFISH_CBC_PKCS7
    | CAPABILITY_RSA_OAEP
    | CAPABILITY_RSA_KEY_FORMATS
    | CAPABILITY_SSH_AGENT_TCP_CHACHA20_POLY1305
    | CAPABILITY_SSH_KEYS
    | CAPABILITY_SSH_AGENT_SIGNING
    | CAPABILITY_SSH_PRIVATE_KEY_IMPORT
    | CAPABILITY_OPENPGP_READ
    | CAPABILITY_OPENPGP_WRITE
    | CAPABILITY_OPENPGP_MUTATION_AGENT
    | CAPABILITY_AES_CBC_HMAC_SHA256
    | CAPABILITY_AES_CBC_HMAC_SHA256_FAST_PATH
    | CAPABILITY_AES_CBC_HMAC_SHA256_STREAMING
    | CAPABILITY_RANDOM_FAST_PATH;

static PANIC_HOOK: Once = Once::new();

/// Installs the process-wide native boundary panic hook exactly once.
///
/// Rust's default hook prints panic payloads before `catch_unwind` runs. Panic
/// payloads can contain secrets from upstream code, so native bridges call
/// this function before entering any catch boundary. The hook deliberately
/// emits nothing; callers report only the stable operation and `PANIC` code.
pub fn install_redacting_panic_hook() {
    PANIC_HOOK.call_once(|| std::panic::set_hook(Box::new(|_| {})));
}

/// Executes a versioned one-shot protobuf request.
#[must_use]
pub fn call(request_bytes: &[u8]) -> Vec<u8> {
    if request_bytes.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return error_response("", NativeErrorCode::ResourceLimit);
    }
    let request = match NativeRequest::decode(request_bytes) {
        Ok(request) => request,
        Err(_) => return error_response("", NativeErrorCode::InvalidRequest),
    };
    if request.protocol_version != PROTOCOL_VERSION {
        return error_response("", NativeErrorCode::UnsupportedProtocol);
    }
    let operation = match request.operation {
        Some(operation) => operation,
        None => return error_response("", NativeErrorCode::InvalidRequest),
    };
    let operation_name = one_shot_operation_name(&operation);
    match execute_one_shot(operation) {
        Ok(result) => success_response(operation_name, result),
        Err(error) => error_response(operation_name, primitive_error_code(error)),
    }
}

/// Opens a generation-tagged streaming session from a protobuf request.
#[must_use]
pub fn stream_open(request_bytes: &[u8]) -> Vec<u8> {
    if request_bytes.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return error_response("stream.open", NativeErrorCode::ResourceLimit);
    }
    let request = match NativeStreamOpenRequest::decode(request_bytes) {
        Ok(request) => request,
        Err(_) => return error_response("stream.open", NativeErrorCode::InvalidRequest),
    };
    if request.protocol_version != PROTOCOL_VERSION {
        return error_response("stream.open", NativeErrorCode::UnsupportedProtocol);
    }
    let operation = match request.operation {
        Some(operation) => operation,
        None => return error_response("stream.open", NativeErrorCode::InvalidRequest),
    };
    let operation_name = stream_open_operation_name(&operation);
    match operation {
        native_stream_open_request::Operation::HmacSha256(mut request) => {
            let key = std::mem::take(&mut request.key);
            stream_open_response(operation_name, sessions::open_hmac_sha256(key))
        }
        native_stream_open_request::Operation::Digest(request) => {
            let algorithm = match parse_hash_algorithm(request.algorithm) {
                Ok(algorithm) => algorithm,
                Err(error) => {
                    return error_response(operation_name, primitive_error_code(error));
                }
            };
            stream_open_response(operation_name, sessions::open_digest(algorithm))
        }
        native_stream_open_request::Operation::Hmac(mut request) => {
            let algorithm = match parse_hash_algorithm(request.algorithm) {
                Ok(algorithm) => algorithm,
                Err(error) => {
                    return error_response(operation_name, primitive_error_code(error));
                }
            };
            let key = std::mem::take(&mut request.key);
            stream_open_response(operation_name, sessions::open_hmac(algorithm, key))
        }
        native_stream_open_request::Operation::AesCbcPkcs7(mut request) => {
            let direction = match parse_cipher_direction(request.direction) {
                Ok(direction) => direction,
                Err(error) => {
                    return error_response(operation_name, primitive_error_code(error));
                }
            };
            let key = std::mem::take(&mut request.key);
            let iv = std::mem::take(&mut request.iv);
            stream_open_response(
                operation_name,
                sessions::open_aes_cbc_pkcs7(direction, key, iv),
            )
        }
        native_stream_open_request::Operation::TwofishCbcPkcs7(mut request) => {
            let direction = match parse_cipher_direction(request.direction) {
                Ok(direction) => direction,
                Err(error) => {
                    return error_response(operation_name, primitive_error_code(error));
                }
            };
            let key = std::mem::take(&mut request.key);
            let iv = std::mem::take(&mut request.iv);
            stream_open_response(
                operation_name,
                sessions::open_twofish_cbc_pkcs7(direction, key, iv),
            )
        }
        native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Encrypt(mut request) => {
            let encryption_key = std::mem::take(&mut request.encryption_key);
            let mac_key = std::mem::take(&mut request.mac_key);
            let iv = std::mem::take(&mut request.iv);
            stream_open_response(
                operation_name,
                sessions::open_aes_cbc_pkcs7_hmac_sha256_encrypt(encryption_key, mac_key, iv),
            )
        }
        native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Decrypt(mut request) => {
            let encryption_key = std::mem::take(&mut request.encryption_key);
            let mac_key = std::mem::take(&mut request.mac_key);
            let iv = std::mem::take(&mut request.iv);
            let expected_mac = std::mem::take(&mut request.expected_mac);
            stream_open_response(
                operation_name,
                sessions::open_aes_cbc_pkcs7_hmac_sha256_decrypt(
                    encryption_key,
                    mac_key,
                    iv,
                    expected_mac,
                ),
            )
        }
        native_stream_open_request::Operation::OpenPgpDetachedVerify(request) => {
            stream_open_response(
                operation_name,
                sessions::open_openpgp_detached_verify(request),
            )
        }
        native_stream_open_request::Operation::OpenPgpDetachedSign(request) => {
            stream_open_response(
                operation_name,
                sessions::open_openpgp_detached_sign(request),
            )
        }
        native_stream_open_request::Operation::OpenPgpEncrypt(request) => {
            stream_open_response(operation_name, sessions::open_openpgp_encrypt(request))
        }
        native_stream_open_request::Operation::OpenPgpDecrypt(request) => {
            stream_open_response(operation_name, sessions::open_openpgp_decrypt(request))
        }
    }
}

/// Adds a raw chunk to a streaming session.
#[must_use]
pub fn stream_update(handle: u64, data: &[u8]) -> Vec<u8> {
    if data.len() > MAX_STREAM_CHUNK_BYTES {
        return error_response("stream.update", NativeErrorCode::ResourceLimit);
    }
    match sessions::update(handle, data) {
        Ok(output) => {
            success_response("stream.update", native_response::Result::BytesValue(output))
        }
        Err(error) => error_response("stream.update", session_error_code(error)),
    }
}

/// Finalizes and consumes a streaming session.
#[must_use]
pub fn stream_finish(handle: u64) -> Vec<u8> {
    match sessions::finish(handle) {
        Ok(output) => {
            success_response("stream.finish", native_response::Result::BytesValue(output))
        }
        Err(error) => error_response("stream.finish", session_error_code(error)),
    }
}

/// Closes a streaming session. Repeated close calls are idempotent while the
/// generation-tagged slot has not been reused.
#[must_use]
pub fn stream_close(handle: u64) -> Vec<u8> {
    match sessions::close(handle) {
        Ok(()) => success_response(
            "stream.close",
            native_response::Result::BytesValue(Vec::new()),
        ),
        Err(error) => error_response("stream.close", session_error_code(error)),
    }
}

/// Encodes a contained-panic response without exposing a panic payload.
#[must_use]
pub fn panic_response(operation: &'static str) -> Vec<u8> {
    error_response(operation, NativeErrorCode::Panic)
}

/// Encodes a bridge-internal error without exposing backend diagnostics.
#[must_use]
pub fn internal_response(operation: &'static str) -> Vec<u8> {
    error_response(operation, NativeErrorCode::Internal)
}

/// Encodes a resource-limit response for boundary-side size rejection.
#[must_use]
pub fn resource_limit_response(operation: &'static str) -> Vec<u8> {
    error_response(operation, NativeErrorCode::ResourceLimit)
}

fn execute_one_shot(
    operation: native_request::Operation,
) -> Result<native_response::Result, PrimitiveError> {
    let result = match operation {
        native_request::Operation::HkdfSha256(mut request) => {
            let seed = std::mem::take(&mut request.seed);
            let salt = std::mem::take(&mut request.salt);
            let info = std::mem::take(&mut request.info);
            primitives::hkdf_sha256(seed, salt, info, request.length)?
        }
        native_request::Operation::Pbkdf2Sha256(mut request) => {
            let seed = std::mem::take(&mut request.seed);
            let salt = std::mem::take(&mut request.salt);
            primitives::pbkdf2_sha256(seed, salt, request.iterations, request.length)?
        }
        native_request::Operation::Argon2(mut request) => {
            let mode = parse_argon2_mode(request.mode)?;
            let version = parse_argon2_version(request.version)?;
            let seed = std::mem::take(&mut request.seed);
            let salt = std::mem::take(&mut request.salt);
            let secret = std::mem::take(&mut request.secret);
            let associated_data = std::mem::take(&mut request.associated_data);
            primitives::argon2(
                mode,
                version,
                seed,
                salt,
                secret,
                associated_data,
                request.iterations,
                request.memory_kib,
                request.parallelism,
                request.length,
            )?
        }
        native_request::Operation::RandomBytes(request) => {
            primitives::random_bytes(request.length)?
        }
        native_request::Operation::RandomInt(request) => {
            return primitives::random_int(request.bounded, request.exclusive_upper_bound)
                .map(native_response::Result::Int32Value);
        }
        native_request::Operation::RandomInts(request) => primitives::random_ints(
            request.bounded,
            request.exclusive_upper_bound,
            request.count,
        )?,
        native_request::Operation::Hmac(mut request) => {
            let algorithm = parse_hash_algorithm(request.algorithm)?;
            let key = std::mem::take(&mut request.key);
            let data = std::mem::take(&mut request.data);
            primitives::hmac(algorithm, key, data)?
        }
        native_request::Operation::Digest(mut request) => {
            let algorithm = parse_hash_algorithm(request.algorithm)?;
            let data = std::mem::take(&mut request.data);
            primitives::digest(algorithm, data)?
        }
        native_request::Operation::AesEcbNoPaddingEncrypt(mut request) => {
            let key = std::mem::take(&mut request.key);
            let data = std::mem::take(&mut request.data);
            primitives::aes_ecb_no_padding_encrypt(key, data)?
        }
        native_request::Operation::AesCbcPkcs7(mut request) => {
            let direction = parse_cipher_direction(request.direction)?;
            let key = std::mem::take(&mut request.key);
            let iv = std::mem::take(&mut request.iv);
            let data = std::mem::take(&mut request.data);
            primitives::aes_cbc_pkcs7(direction, key, iv, data)?
        }
        native_request::Operation::AesCbcPkcs7HmacSha256Encrypt(mut request) => {
            let encryption_key = std::mem::take(&mut request.encryption_key);
            let mac_key = std::mem::take(&mut request.mac_key);
            let iv = std::mem::take(&mut request.iv);
            let plaintext = std::mem::take(&mut request.plaintext);
            let (ciphertext, mac) = primitives::aes_cbc_pkcs7_hmac_sha256_encrypt(
                encryption_key,
                mac_key,
                iv,
                plaintext,
            )?;
            protocol::AesCbcPkcs7HmacSha256EncryptResult { ciphertext, mac }.encode_to_vec()
        }
        native_request::Operation::AesCbcPkcs7HmacSha256Decrypt(mut request) => {
            let encryption_key = std::mem::take(&mut request.encryption_key);
            let mac_key = std::mem::take(&mut request.mac_key);
            let iv = std::mem::take(&mut request.iv);
            let ciphertext = std::mem::take(&mut request.ciphertext);
            let expected_mac = std::mem::take(&mut request.expected_mac);
            primitives::aes_cbc_pkcs7_hmac_sha256_decrypt(
                encryption_key,
                mac_key,
                iv,
                ciphertext,
                expected_mac,
            )?
        }
        native_request::Operation::AesEcbNoPaddingTransform(mut request) => {
            let key = std::mem::take(&mut request.key);
            let data = std::mem::take(&mut request.data);
            primitives::aes_ecb_no_padding_transform(key, data, request.rounds)?
        }
        native_request::Operation::StreamCipherXorAtOffset(mut request) => {
            let algorithm = parse_stream_cipher_algorithm(request.algorithm)?;
            let key = std::mem::take(&mut request.key);
            let nonce = std::mem::take(&mut request.nonce);
            let data = std::mem::take(&mut request.data);
            primitives::stream_cipher_xor_at_offset(algorithm, key, nonce, request.offset, data)?
        }
        native_request::Operation::TwofishCbcPkcs7(mut request) => {
            let direction = parse_cipher_direction(request.direction)?;
            let key = std::mem::take(&mut request.key);
            let iv = std::mem::take(&mut request.iv);
            let data = std::mem::take(&mut request.data);
            primitives::twofish_cbc_pkcs7(direction, key, iv, data)?
        }
        native_request::Operation::RsaOaepEncrypt(mut request) => {
            let hash = parse_rsa_oaep_hash(request.hash)?;
            let public_key_spki = std::mem::take(&mut request.public_key_spki);
            let plaintext = std::mem::take(&mut request.plaintext);
            primitives::rsa_oaep_encrypt(hash, public_key_spki, plaintext)?
        }
        native_request::Operation::RsaOaepDecrypt(mut request) => {
            let hash = parse_rsa_oaep_hash(request.hash)?;
            let private_key_pkcs8 = std::mem::take(&mut request.private_key_pkcs8);
            let ciphertext = std::mem::take(&mut request.ciphertext);
            primitives::rsa_oaep_decrypt(hash, private_key_pkcs8, ciphertext)?
        }
        native_request::Operation::RsaPkcs8ToSpki(mut request) => {
            let private_key_pkcs8 = std::mem::take(&mut request.private_key_pkcs8);
            primitives::rsa_pkcs8_to_spki(private_key_pkcs8)?
        }
        native_request::Operation::SshAgentTcpChacha20Poly1305(mut request) => {
            let direction = parse_cipher_direction(request.direction)?;
            let key = std::mem::take(&mut request.key);
            let nonce = std::mem::take(&mut request.nonce);
            let header = std::mem::take(&mut request.header);
            let payload = std::mem::take(&mut request.payload);
            primitives::ssh_agent_tcp_chacha20_poly1305(direction, key, nonce, header, payload)?
        }
        native_request::Operation::SshKeyGenerate(request) => {
            ssh_keys::generate(parse_ssh_key_type(request.r#type)?, request.rsa_bits)?
        }
        native_request::Operation::SshKeyParse(mut request) => ssh_keys::parse(
            std::mem::take(&mut request.private_key_pem),
            std::mem::take(&mut request.public_key_openssh),
        )?,
        native_request::Operation::SshKeyDescribe(mut request) => ssh_keys::describe(
            parse_ssh_key_type(request.r#type)?,
            std::mem::take(&mut request.private_key),
            std::mem::take(&mut request.public_key),
        )?,
        native_request::Operation::SshPrivateKeyRsaBits(mut request) => {
            let bits = ssh_keys::private_key_rsa_bits(std::mem::take(&mut request.private_key));
            return Ok(native_response::Result::Int32Value(bits));
        }
        native_request::Operation::SshPrivateKeyFormat(mut request) => {
            ssh_keys::format_private_key(
                parse_ssh_key_type(request.r#type)?,
                std::mem::take(&mut request.private_key),
            )?
        }
        native_request::Operation::SshAgentSign(mut request) => ssh_keys::sign(
            std::mem::take(&mut request.private_key_pem),
            std::mem::take(&mut request.public_key_openssh),
            std::mem::take(&mut request.data),
            request.flags,
        )?,
        native_request::Operation::SshPrivateKeyImport(mut request) => ssh_import::import(
            std::mem::take(&mut request.content),
            std::mem::take(&mut request.passphrase_utf8),
        )?,
        native_request::Operation::OpenPgpPublicKeyParse(request) => {
            openpgp_read::parse_public_key_request(request).map_err(openpgp_read_error)?
        }
        native_request::Operation::OpenPgpVerify(request) => {
            openpgp_read::verify_request(request).map_err(openpgp_read_error)?
        }
        native_request::Operation::OpenPgpMetadataResolve(request) => {
            openpgp_read::resolve_metadata(request).map_err(openpgp_read_error)?
        }
        native_request::Operation::OpenPgpKeyGenerate(request) => {
            openpgp_write::generate_key_request(request).map_err(openpgp_write_error)?
        }
        native_request::Operation::OpenPgpKeyImport(request) => {
            openpgp_write::import_key_request(request).map_err(openpgp_write_error)?
        }
        native_request::Operation::OpenPgpSign(request) => {
            openpgp_write::sign_request(request).map_err(openpgp_write_error)?
        }
        native_request::Operation::OpenPgpEncrypt(request) => {
            openpgp_write::encrypt_request(request).map_err(openpgp_write_error)?
        }
        native_request::Operation::OpenPgpDecrypt(request) => {
            openpgp_write::decrypt_request(request).map_err(openpgp_write_error)?
        }
        native_request::Operation::OpenPgpExpirationUpdate(request) => {
            openpgp_mutation::update_expiration_request(request).map_err(openpgp_mutation_error)?
        }
        native_request::Operation::OpenPgpAgentSign(request) => {
            openpgp_agent::sign_request(request).map_err(openpgp_agent_error)?
        }
        native_request::Operation::OpenPgpAgentDecrypt(request) => {
            openpgp_agent::decrypt_request(request).map_err(openpgp_agent_error)?
        }
    };
    Ok(native_response::Result::BytesValue(result))
}

fn one_shot_operation_name(operation: &native_request::Operation) -> &'static str {
    match operation {
        native_request::Operation::HkdfSha256(_) => "hkdf_sha256",
        native_request::Operation::Pbkdf2Sha256(_) => "pbkdf2_sha256",
        native_request::Operation::Argon2(_) => "argon2",
        native_request::Operation::RandomBytes(_) => "random_bytes",
        native_request::Operation::RandomInt(_) => "random_int",
        native_request::Operation::RandomInts(_) => "random_ints",
        native_request::Operation::Hmac(_) => "hmac",
        native_request::Operation::Digest(_) => "digest",
        native_request::Operation::AesEcbNoPaddingEncrypt(_) => "aes_ecb_no_padding_encrypt",
        native_request::Operation::AesCbcPkcs7(_) => "aes_cbc_pkcs7",
        native_request::Operation::AesCbcPkcs7HmacSha256Encrypt(_) => {
            "aes_cbc_pkcs7_hmac_sha256_encrypt"
        }
        native_request::Operation::AesCbcPkcs7HmacSha256Decrypt(_) => {
            "aes_cbc_pkcs7_hmac_sha256_decrypt"
        }
        native_request::Operation::AesEcbNoPaddingTransform(_) => "aes_ecb_no_padding_transform",
        native_request::Operation::StreamCipherXorAtOffset(_) => "stream_cipher_xor_at_offset",
        native_request::Operation::TwofishCbcPkcs7(_) => "twofish_cbc_pkcs7",
        native_request::Operation::RsaOaepEncrypt(_) => "rsa_oaep_encrypt",
        native_request::Operation::RsaOaepDecrypt(_) => "rsa_oaep_decrypt",
        native_request::Operation::RsaPkcs8ToSpki(_) => "rsa_pkcs8_to_spki",
        native_request::Operation::SshAgentTcpChacha20Poly1305(_) => {
            "ssh_agent_tcp_chacha20_poly1305"
        }
        native_request::Operation::SshKeyGenerate(_) => "ssh_key_generate",
        native_request::Operation::SshKeyParse(_) => "ssh_key_parse",
        native_request::Operation::SshKeyDescribe(_) => "ssh_key_describe",
        native_request::Operation::SshPrivateKeyRsaBits(_) => "ssh_private_key_rsa_bits",
        native_request::Operation::SshPrivateKeyFormat(_) => "ssh_private_key_format",
        native_request::Operation::SshAgentSign(_) => "ssh_agent_sign",
        native_request::Operation::SshPrivateKeyImport(_) => "ssh_private_key_import",
        native_request::Operation::OpenPgpPublicKeyParse(_) => "open_pgp_public_key_parse",
        native_request::Operation::OpenPgpVerify(_) => "open_pgp_verify",
        native_request::Operation::OpenPgpMetadataResolve(_) => "open_pgp_metadata_resolve",
        native_request::Operation::OpenPgpKeyGenerate(_) => "open_pgp_key_generate",
        native_request::Operation::OpenPgpKeyImport(_) => "open_pgp_key_import",
        native_request::Operation::OpenPgpSign(_) => "open_pgp_sign",
        native_request::Operation::OpenPgpEncrypt(_) => "open_pgp_encrypt",
        native_request::Operation::OpenPgpDecrypt(_) => "open_pgp_decrypt",
        native_request::Operation::OpenPgpExpirationUpdate(_) => "open_pgp_expiration_update",
        native_request::Operation::OpenPgpAgentSign(_) => "open_pgp_agent_sign",
        native_request::Operation::OpenPgpAgentDecrypt(_) => "open_pgp_agent_decrypt",
    }
}

fn stream_open_operation_name(operation: &native_stream_open_request::Operation) -> &'static str {
    match operation {
        native_stream_open_request::Operation::HmacSha256(_) => "hmac_sha256.stream_open",
        native_stream_open_request::Operation::Digest(_) => "digest.stream_open",
        native_stream_open_request::Operation::Hmac(_) => "hmac.stream_open",
        native_stream_open_request::Operation::AesCbcPkcs7(_) => "aes_cbc_pkcs7.stream_open",
        native_stream_open_request::Operation::TwofishCbcPkcs7(_) => {
            "twofish_cbc_pkcs7.stream_open"
        }
        native_stream_open_request::Operation::OpenPgpDetachedVerify(_) => {
            "open_pgp_detached_verify.stream_open"
        }
        native_stream_open_request::Operation::OpenPgpDetachedSign(_) => {
            "open_pgp_detached_sign.stream_open"
        }
        native_stream_open_request::Operation::OpenPgpEncrypt(_) => "open_pgp_encrypt.stream_open",
        native_stream_open_request::Operation::OpenPgpDecrypt(_) => "open_pgp_decrypt.stream_open",
        native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Encrypt(_) => {
            "aes_cbc_pkcs7_hmac_sha256_encrypt.stream_open"
        }
        native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Decrypt(_) => {
            "aes_cbc_pkcs7_hmac_sha256_decrypt.stream_open"
        }
    }
}

fn stream_open_response(operation: &'static str, result: Result<u64, SessionError>) -> Vec<u8> {
    match result {
        Ok(handle) => success_response(operation, native_response::Result::Uint64Value(handle)),
        Err(error) => error_response(operation, session_error_code(error)),
    }
}

fn parse_argon2_mode(value: i32) -> Result<Argon2Mode, PrimitiveError> {
    Argon2Mode::try_from(value)
        .ok()
        .filter(|mode| *mode != Argon2Mode::Unspecified)
        .ok_or(PrimitiveError::InvalidArgument)
}

fn parse_argon2_version(value: u32) -> Result<argon2::Version, PrimitiveError> {
    match value {
        0 | 0x13 => Ok(argon2::Version::V0x13),
        0x10 => Ok(argon2::Version::V0x10),
        _ => Err(PrimitiveError::InvalidArgument),
    }
}

fn parse_stream_cipher_algorithm(value: i32) -> Result<StreamCipherAlgorithm, PrimitiveError> {
    StreamCipherAlgorithm::try_from(value)
        .ok()
        .filter(|algorithm| *algorithm != StreamCipherAlgorithm::Unspecified)
        .ok_or(PrimitiveError::InvalidArgument)
}

fn parse_hash_algorithm(value: i32) -> Result<HashAlgorithm, PrimitiveError> {
    HashAlgorithm::try_from(value)
        .ok()
        .filter(|algorithm| *algorithm != HashAlgorithm::Unspecified)
        .ok_or(PrimitiveError::InvalidArgument)
}

fn parse_rsa_oaep_hash(value: i32) -> Result<RsaOaepHash, PrimitiveError> {
    RsaOaepHash::try_from(value)
        .ok()
        .filter(|hash| *hash != RsaOaepHash::Unspecified)
        .ok_or(PrimitiveError::InvalidArgument)
}

fn parse_ssh_key_type(value: i32) -> Result<SshKeyType, PrimitiveError> {
    SshKeyType::try_from(value)
        .ok()
        .filter(|key_type| *key_type != SshKeyType::Unspecified)
        .ok_or(PrimitiveError::InvalidArgument)
}

fn parse_cipher_direction(value: i32) -> Result<CipherDirection, PrimitiveError> {
    CipherDirection::try_from(value)
        .ok()
        .filter(|direction| *direction != CipherDirection::Unspecified)
        .ok_or(PrimitiveError::InvalidArgument)
}

fn openpgp_read_error(error: openpgp_read::OpenPgpReadError) -> PrimitiveError {
    match error {
        openpgp_read::OpenPgpReadError::InvalidArgument => PrimitiveError::InvalidArgument,
        openpgp_read::OpenPgpReadError::ResourceLimit => PrimitiveError::ResourceLimit,
        openpgp_read::OpenPgpReadError::Internal => PrimitiveError::CryptoFailure,
    }
}

fn openpgp_write_error(error: openpgp_write::OpenPgpWriteError) -> PrimitiveError {
    match error {
        openpgp_write::OpenPgpWriteError::InvalidArgument => PrimitiveError::InvalidArgument,
        openpgp_write::OpenPgpWriteError::MissingKey => PrimitiveError::NoUsableKey,
        openpgp_write::OpenPgpWriteError::UnsupportedKeyVersion(_) => {
            PrimitiveError::UnsupportedKeyVersion
        }
        openpgp_write::OpenPgpWriteError::AuthenticationFailed => {
            PrimitiveError::AuthenticationFailed
        }
        openpgp_write::OpenPgpWriteError::ResourceLimit => PrimitiveError::ResourceLimit,
        openpgp_write::OpenPgpWriteError::CryptoFailure => PrimitiveError::CryptoFailure,
        openpgp_write::OpenPgpWriteError::Internal => PrimitiveError::Internal,
        openpgp_write::OpenPgpWriteError::Panic => PrimitiveError::Panic,
    }
}

fn openpgp_mutation_error(error: openpgp_mutation::OpenPgpMutationFatal) -> PrimitiveError {
    match error {
        openpgp_mutation::OpenPgpMutationFatal::ResourceLimit => PrimitiveError::ResourceLimit,
    }
}

fn openpgp_agent_error(error: openpgp_agent::OpenPgpAgentError) -> PrimitiveError {
    match error {
        openpgp_agent::OpenPgpAgentError::InvalidArgument => PrimitiveError::InvalidArgument,
        openpgp_agent::OpenPgpAgentError::ResourceLimit => PrimitiveError::ResourceLimit,
        openpgp_agent::OpenPgpAgentError::CryptoFailure => PrimitiveError::CryptoFailure,
        openpgp_agent::OpenPgpAgentError::Internal => PrimitiveError::Internal,
    }
}

fn primitive_error_code(error: PrimitiveError) -> NativeErrorCode {
    match error {
        PrimitiveError::InvalidArgument => NativeErrorCode::InvalidArgument,
        PrimitiveError::ResourceLimit => NativeErrorCode::ResourceLimit,
        PrimitiveError::CryptoFailure => NativeErrorCode::CryptoFailure,
        PrimitiveError::Internal => NativeErrorCode::Internal,
        PrimitiveError::AuthenticationFailed => NativeErrorCode::AuthenticationFailed,
        PrimitiveError::UnsupportedKeyVersion => NativeErrorCode::UnsupportedKeyVersion,
        PrimitiveError::NoUsableKey => NativeErrorCode::NoUsableKey,
        PrimitiveError::Panic => NativeErrorCode::Panic,
    }
}

fn session_error_code(error: SessionError) -> NativeErrorCode {
    match error {
        SessionError::InvalidSession => NativeErrorCode::InvalidSession,
        SessionError::InvalidArgument => NativeErrorCode::InvalidArgument,
        SessionError::ResourceLimit => NativeErrorCode::ResourceLimit,
        SessionError::CryptoFailure => NativeErrorCode::CryptoFailure,
        SessionError::UnsupportedKeyVersion => NativeErrorCode::UnsupportedKeyVersion,
        SessionError::AuthenticationFailed => NativeErrorCode::AuthenticationFailed,
        SessionError::NoUsableKey => NativeErrorCode::NoUsableKey,
        SessionError::Internal => NativeErrorCode::Internal,
        SessionError::Panic => NativeErrorCode::Panic,
    }
}

fn success_response(operation: &'static str, result: native_response::Result) -> Vec<u8> {
    encode_response(NativeResponse {
        protocol_version: PROTOCOL_VERSION,
        status: Some(NativeStatus {
            code: NativeErrorCode::Ok as i32,
            operation: operation.to_owned(),
        }),
        result: Some(result),
    })
}

fn error_response(operation: &'static str, code: NativeErrorCode) -> Vec<u8> {
    encode_response_unchecked(NativeResponse {
        protocol_version: PROTOCOL_VERSION,
        status: Some(NativeStatus {
            code: code as i32,
            operation: operation.to_owned(),
        }),
        result: None,
    })
}

fn encode_response(response: NativeResponse) -> Vec<u8> {
    let mut encoded = encode_response_unchecked(response);
    if encoded.len() <= MAX_CONTROL_ENVELOPE_BYTES {
        encoded
    } else {
        encoded.zeroize();
        error_response("", NativeErrorCode::ResourceLimit)
    }
}

fn encode_response_unchecked(mut response: NativeResponse) -> Vec<u8> {
    let guard = ResponseBytesGuard(&mut response);
    guard.0.encode_to_vec()
}

struct ResponseBytesGuard<'a>(&'a mut NativeResponse);

impl Drop for ResponseBytesGuard<'_> {
    fn drop(&mut self) {
        if let Some(native_response::Result::BytesValue(bytes)) = self.0.result.as_mut() {
            bytes.zeroize();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aws_lc_rs::{
        encoding::{AsDer, Pkcs8V1Der, PublicKeyX509Der},
        rsa::{KeySize, PrivateDecryptingKey},
    };
    use protocol::{
        AesCbcPkcs7HmacSha256DecryptRequest, AesCbcPkcs7HmacSha256DecryptStreamOpenRequest,
        AesCbcPkcs7HmacSha256EncryptRequest, AesCbcPkcs7HmacSha256EncryptResult,
        AesCbcPkcs7HmacSha256EncryptStreamOpenRequest, AesCbcPkcs7Request,
        AesCbcPkcs7StreamOpenRequest, AesEcbNoPaddingEncryptRequest,
        AesEcbNoPaddingTransformRequest, Argon2Request, DigestRequest, DigestStreamOpenRequest,
        HkdfSha256Request, HmacRequest, HmacSha256StreamOpenRequest, HmacStreamOpenRequest,
        NativeStreamOpenRequest, OpenPgpDetachedVerifyStreamOpenRequest,
        OpenPgpMetadataResolveRequest, OpenPgpVerifyKind, OpenPgpVerifyRequest,
        Pbkdf2Sha256Request, RandomBytesRequest, RandomIntRequest, RandomIntsRequest,
        RsaOaepDecryptRequest, RsaOaepEncryptRequest, RsaPkcs8ToSpkiRequest, SshAgentSignRequest,
        SshAgentTcpChaCha20Poly1305Request, SshKeyDescribeRequest, SshKeyGenerateRequest,
        SshKeyMaterial, SshKeyParseRequest, SshKeyType, SshPrivateKeyFormatRequest,
        SshPrivateKeyImportRequest, StreamCipherXorAtOffsetRequest, TwofishCbcPkcs7Request,
        TwofishCbcPkcs7StreamOpenRequest,
    };

    fn invoke(operation: native_request::Operation) -> NativeResponse {
        let request = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(operation),
        };
        NativeResponse::decode(call(&request.encode_to_vec()).as_slice())
            .expect("native response must decode")
    }

    #[test]
    fn no_usable_openpgp_key_has_distinct_wire_code() {
        let error = openpgp_write_error(openpgp_write::OpenPgpWriteError::MissingKey);

        assert_eq!(primitive_error_code(error), NativeErrorCode::NoUsableKey);
        assert_eq!(
            session_error_code(SessionError::NoUsableKey),
            NativeErrorCode::NoUsableKey
        );
    }

    #[test]
    fn decoded_secret_requests_zeroize_on_every_early_return_path() {
        let unsupported = NativeRequest {
            protocol_version: PROTOCOL_VERSION + 1,
            operation: Some(native_request::Operation::Hmac(HmacRequest {
                algorithm: HashAlgorithm::Sha256 as i32,
                key: b"unsupported-secret-key".to_vec(),
                data: b"unsupported-secret-data".to_vec(),
            })),
        }
        .encode_to_vec();
        protocol::reset_zeroized_secret_request_drops();
        let response = NativeResponse::decode(call(&unsupported).as_slice())
            .expect("unsupported-protocol response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::UnsupportedProtocol as i32)
        );
        assert_eq!(protocol::zeroized_secret_request_drops(), 1);

        let invalid_enum = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_request::Operation::Hmac(HmacRequest {
                algorithm: HashAlgorithm::Unspecified as i32,
                key: b"invalid-enum-secret-key".to_vec(),
                data: b"invalid-enum-secret-data".to_vec(),
            })),
        }
        .encode_to_vec();
        protocol::reset_zeroized_secret_request_drops();
        let response = NativeResponse::decode(call(&invalid_enum).as_slice())
            .expect("invalid-enum response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );
        assert_eq!(protocol::zeroized_secret_request_drops(), 1);

        let mut malformed = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_request::Operation::Hmac(HmacRequest {
                algorithm: HashAlgorithm::Sha256 as i32,
                key: b"malformed-secret-key".to_vec(),
                data: b"malformed-secret-data".to_vec(),
            })),
        }
        .encode_to_vec();
        malformed.push(0x80);
        protocol::reset_zeroized_secret_request_drops();
        let response = NativeResponse::decode(call(&malformed).as_slice())
            .expect("malformed-request response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidRequest as i32)
        );
        assert_eq!(protocol::zeroized_secret_request_drops(), 1);

        let invalid_stream = NativeStreamOpenRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_stream_open_request::Operation::AesCbcPkcs7(
                AesCbcPkcs7StreamOpenRequest {
                    direction: CipherDirection::Unspecified as i32,
                    key: b"invalid-stream-secret-key-32b".to_vec(),
                    iv: b"invalid-streamiv".to_vec(),
                },
            )),
        }
        .encode_to_vec();
        protocol::reset_zeroized_secret_request_drops();
        let response = NativeResponse::decode(stream_open(&invalid_stream).as_slice())
            .expect("invalid-stream response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );
        assert_eq!(protocol::zeroized_secret_request_drops(), 1);
    }

    #[test]
    fn decoded_ssh_agent_tcp_request_zeroizes_on_invalid_direction() {
        let encoded = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_request::Operation::SshAgentTcpChacha20Poly1305(
                SshAgentTcpChaCha20Poly1305Request {
                    direction: CipherDirection::Unspecified as i32,
                    key: vec![0x11; 32],
                    nonce: vec![0x22; 12],
                    header: vec![0x33; 18],
                    payload: b"secret transport payload".to_vec(),
                },
            )),
        }
        .encode_to_vec();
        protocol::reset_zeroized_secret_request_drops();

        let response = NativeResponse::decode(call(&encoded).as_slice())
            .expect("invalid-direction response must decode");

        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );
        assert_eq!(protocol::zeroized_secret_request_drops(), 1);
    }

    #[test]
    fn decoded_ssh_key_requests_zeroize_on_invalid_early_returns() {
        let cases = [
            native_request::Operation::SshKeyDescribe(SshKeyDescribeRequest {
                r#type: SshKeyType::Unspecified as i32,
                private_key: b"invalid-enum-private-key".to_vec(),
                public_key: b"invalid-enum-public-key".to_vec(),
            }),
            native_request::Operation::SshPrivateKeyFormat(SshPrivateKeyFormatRequest {
                r#type: SshKeyType::Unspecified as i32,
                private_key: b"invalid-enum-private-key".to_vec(),
            }),
            native_request::Operation::SshKeyParse(SshKeyParseRequest {
                private_key_pem: "invalid parse private key".to_owned(),
                public_key_openssh: "invalid public key".to_owned(),
            }),
            native_request::Operation::SshAgentSign(SshAgentSignRequest {
                private_key_pem: "invalid signing private key".to_owned(),
                public_key_openssh: None,
                data: b"invalid signing secret payload".to_vec(),
                flags: 0,
            }),
            native_request::Operation::SshPrivateKeyImport(SshPrivateKeyImportRequest {
                content: "invalid import private key".to_owned(),
                passphrase_utf8: Some(vec![0xff]),
            }),
        ];

        for operation in cases {
            let encoded = NativeRequest {
                protocol_version: PROTOCOL_VERSION,
                operation: Some(operation),
            }
            .encode_to_vec();
            protocol::reset_zeroized_secret_request_drops();

            let response = NativeResponse::decode(call(&encoded).as_slice())
                .expect("invalid SSH-key response must decode");

            assert_eq!(
                response.status.map(|status| status.code),
                Some(NativeErrorCode::InvalidArgument as i32),
            );
            assert_eq!(protocol::zeroized_secret_request_drops(), 1);
        }
    }

    #[test]
    fn ssh_key_secret_outputs_zeroize_after_response_encoding() {
        protocol::reset_zeroized_secret_output_drops();
        let generated = response_bytes(invoke(native_request::Operation::SshKeyGenerate(
            SshKeyGenerateRequest {
                r#type: SshKeyType::Ed25519 as i32,
                rsa_bits: 0,
            },
        )));
        assert_eq!(protocol::zeroized_secret_output_drops(), 1);

        let material = SshKeyMaterial::decode(generated.as_slice())
            .expect("generated SSH key material must decode");
        let private_key = material.private_key.clone();
        let public_key = material.public_key.clone();
        drop(material);

        protocol::reset_zeroized_secret_output_drops();
        let _description = response_bytes(invoke(native_request::Operation::SshKeyDescribe(
            SshKeyDescribeRequest {
                r#type: SshKeyType::Ed25519 as i32,
                private_key: private_key.clone(),
                public_key,
            },
        )));
        assert_eq!(protocol::zeroized_secret_output_drops(), 1);

        protocol::reset_zeroized_secret_output_drops();
        let _formatted = response_bytes(invoke(native_request::Operation::SshPrivateKeyFormat(
            SshPrivateKeyFormatRequest {
                r#type: SshKeyType::Ed25519 as i32,
                private_key,
            },
        )));
        assert_eq!(protocol::zeroized_secret_output_drops(), 1);

        protocol::reset_zeroized_secret_output_drops();
        let _imported = response_bytes(invoke(native_request::Operation::SshPrivateKeyImport(
            SshPrivateKeyImportRequest {
                content: include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519"
                )
                .to_owned(),
                passphrase_utf8: None,
            },
        )));
        assert_eq!(protocol::zeroized_secret_output_drops(), 1);
    }

    fn response_bytes(response: NativeResponse) -> Vec<u8> {
        assert_eq!(
            response.status.as_ref().map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );
        match response.result {
            Some(native_response::Result::BytesValue(bytes)) => bytes,
            _ => panic!("expected byte response"),
        }
    }

    fn open_stream(operation: native_stream_open_request::Operation) -> u64 {
        let request = NativeStreamOpenRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(operation),
        };
        let response = NativeResponse::decode(stream_open(&request.encode_to_vec()).as_slice())
            .expect("stream-open response must decode");
        assert_eq!(
            response.status.as_ref().map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );
        match response.result {
            Some(native_response::Result::Uint64Value(handle)) => handle,
            _ => panic!("expected session handle"),
        }
    }

    fn update_stream(handle: u64, data: &[u8]) -> NativeResponse {
        NativeResponse::decode(stream_update(handle, data).as_slice())
            .expect("stream-update response must decode")
    }

    fn finish_stream(handle: u64) -> NativeResponse {
        NativeResponse::decode(stream_finish(handle).as_slice())
            .expect("stream-finish response must decode")
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        value
            .as_bytes()
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| {
                let pair = std::str::from_utf8(pair).expect("test hex must be UTF-8");
                u8::from_str_radix(pair, 16).expect("test hex must decode")
            })
            .collect()
    }

    fn openssl_rsa_fixture(name: &str) -> Vec<u8> {
        let prefix = format!("{name}=");
        let value = include_str!("../tests/fixtures/rsa_oaep_openssl.hex")
            .lines()
            .find_map(|line| line.strip_prefix(&prefix))
            .expect("OpenSSL RSA fixture field must exist");
        decode_hex(value)
    }

    fn ssh_agent_tcp_fixture(name: &str) -> Vec<u8> {
        let prefix = format!("{name}=");
        let value = include_str!("../tests/fixtures/ssh_agent_tcp_v2.hex")
            .lines()
            .find_map(|line| line.strip_prefix(&prefix))
            .expect("SSH-agent TCP fixture field must exist");
        decode_hex(value)
    }

    #[test]
    fn reports_stable_abi_and_capabilities() {
        assert_eq!(ABI_VERSION, 1);
        assert_eq!(CAPABILITIES, 0xffffff);
        assert_eq!(CAPABILITY_AES_CBC_HMAC_SHA256, 1 << 20);
        assert_eq!(CAPABILITY_AES_CBC_HMAC_SHA256_FAST_PATH, 1 << 21);
        assert_eq!(CAPABILITY_RANDOM_FAST_PATH, 1 << 23);
        assert_eq!(CAPABILITY_AES_CBC_HMAC_SHA256_STREAMING, 1 << 22);
    }

    #[test]
    fn chacha20_poly1305_matches_rfc_8439_aead_vector() {
        let key = decode_hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        let nonce = decode_hex("070000004041424344454647");
        let associated_data = decode_hex("50515253c0c1c2c3c4c5c6c7");
        let plaintext = decode_hex(concat!(
            "4c616469657320616e642047656e746c656d656e206f662074686520636c617373",
            "206f66202739393a204966204920636f756c64206f6666657220796f75206f6e",
            "6c79206f6e652074697020666f7220746865206675747572652c2073756e736372",
            "65656e20776f756c642062652069742e"
        ));
        let expected = decode_hex(concat!(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6",
            "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36",
            "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc",
            "3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691"
        ));

        let mut encrypted = plaintext.clone();
        primitives::chacha20_poly1305_in_place(
            CipherDirection::Encrypt,
            &key,
            &nonce,
            &associated_data,
            &mut encrypted,
        )
        .expect("RFC 8439 vector must encrypt");
        assert_eq!(encrypted, expected);

        primitives::chacha20_poly1305_in_place(
            CipherDirection::Decrypt,
            &key,
            &nonce,
            &associated_data,
            &mut encrypted,
        )
        .expect("RFC 8439 vector must decrypt");
        assert_eq!(encrypted, plaintext);
    }

    #[test]
    fn ssh_agent_tcp_aead_matches_cross_language_frame_golden() {
        let key = ssh_agent_tcp_fixture("key");
        let nonce = ssh_agent_tcp_fixture("nonce");
        let header = ssh_agent_tcp_fixture("header");
        let plaintext = ssh_agent_tcp_fixture("plaintext");
        let expected = ssh_agent_tcp_fixture("ciphertext_and_tag");

        let encrypted = invoke(native_request::Operation::SshAgentTcpChacha20Poly1305(
            SshAgentTcpChaCha20Poly1305Request {
                direction: CipherDirection::Encrypt as i32,
                key: key.clone(),
                nonce: nonce.clone(),
                header: header.clone(),
                payload: plaintext.clone(),
            },
        ));
        assert_eq!(
            encrypted
                .status
                .as_ref()
                .map(|status| status.operation.as_str()),
            Some("ssh_agent_tcp_chacha20_poly1305")
        );
        assert_eq!(response_bytes(encrypted), expected);

        let decrypted = invoke(native_request::Operation::SshAgentTcpChacha20Poly1305(
            SshAgentTcpChaCha20Poly1305Request {
                direction: CipherDirection::Decrypt as i32,
                key,
                nonce,
                header,
                payload: expected,
            },
        ));
        assert_eq!(response_bytes(decrypted), plaintext);
    }

    #[test]
    fn ssh_agent_tcp_aead_enforces_bounds_and_authentication() {
        let key = ssh_agent_tcp_fixture("key");
        let nonce = ssh_agent_tcp_fixture("nonce");
        let header = ssh_agent_tcp_fixture("header");
        let ciphertext = ssh_agent_tcp_fixture("ciphertext_and_tag");
        let request = |direction: CipherDirection,
                       key: Vec<u8>,
                       nonce: Vec<u8>,
                       header: Vec<u8>,
                       payload: Vec<u8>| {
            invoke(native_request::Operation::SshAgentTcpChacha20Poly1305(
                SshAgentTcpChaCha20Poly1305Request {
                    direction: direction as i32,
                    key,
                    nonce,
                    header,
                    payload,
                },
            ))
        };
        let code = |response: NativeResponse| {
            response
                .status
                .map(|status| status.code)
                .expect("response must contain status")
        };

        assert_eq!(
            code(request(
                CipherDirection::Encrypt,
                vec![0; 31],
                nonce.clone(),
                header.clone(),
                Vec::new(),
            )),
            NativeErrorCode::InvalidArgument as i32
        );
        assert_eq!(
            code(request(
                CipherDirection::Encrypt,
                key.clone(),
                vec![0; 11],
                header.clone(),
                Vec::new(),
            )),
            NativeErrorCode::InvalidArgument as i32
        );
        assert_eq!(
            code(request(
                CipherDirection::Encrypt,
                key.clone(),
                nonce.clone(),
                vec![0; 17],
                Vec::new(),
            )),
            NativeErrorCode::InvalidArgument as i32
        );
        assert_eq!(
            code(request(
                CipherDirection::Encrypt,
                key.clone(),
                nonce.clone(),
                header.clone(),
                vec![0; primitives::MAX_SSH_AGENT_TCP_PAYLOAD_BYTES + 1],
            )),
            NativeErrorCode::ResourceLimit as i32
        );
        assert_eq!(
            code(request(
                CipherDirection::Decrypt,
                key.clone(),
                nonce.clone(),
                header.clone(),
                vec![0; 15],
            )),
            NativeErrorCode::InvalidArgument as i32
        );

        for (bad_nonce, bad_header, mut bad_ciphertext) in [
            (vec![0; 12], header.clone(), ciphertext.clone()),
            (nonce.clone(), vec![0; 18], ciphertext.clone()),
            (nonce.clone(), header.clone(), ciphertext.clone()),
        ] {
            if bad_nonce == nonce && bad_header == header {
                bad_ciphertext[0] ^= 0x80;
            }
            assert_eq!(
                code(request(
                    CipherDirection::Decrypt,
                    key.clone(),
                    bad_nonce,
                    bad_header,
                    bad_ciphertext,
                )),
                NativeErrorCode::AuthenticationFailed as i32
            );
        }
    }

    fn rsa_fixture() -> (Vec<u8>, Vec<u8>) {
        let private_key =
            PrivateDecryptingKey::generate(KeySize::Rsa2048).expect("RSA test key must generate");
        let pkcs8: Pkcs8V1Der<'static> = private_key
            .as_der()
            .expect("RSA test key must encode as PKCS#8");
        let spki: PublicKeyX509Der<'static> = private_key
            .public_key()
            .as_der()
            .expect("RSA test public key must encode as SPKI");
        (pkcs8.as_ref().to_vec(), spki.as_ref().to_vec())
    }

    #[test]
    fn rsa_oaep_and_pkcs8_spki_operations_preserve_vault_contracts() {
        let (pkcs8, spki) = rsa_fixture();
        let derived_spki = invoke(native_request::Operation::RsaPkcs8ToSpki(
            RsaPkcs8ToSpkiRequest {
                private_key_pkcs8: pkcs8.clone(),
            },
        ));
        assert_eq!(response_bytes(derived_spki), spki);

        for hash in [RsaOaepHash::Sha1, RsaOaepHash::Sha256] {
            for plaintext in [Vec::new(), b"keyguard vault rsa oaep".to_vec()] {
                let encrypted = invoke(native_request::Operation::RsaOaepEncrypt(
                    RsaOaepEncryptRequest {
                        hash: hash as i32,
                        public_key_spki: spki.clone(),
                        plaintext: plaintext.clone(),
                    },
                ));
                let ciphertext = response_bytes(encrypted);
                assert_eq!(ciphertext.len(), 256);
                let decrypted = invoke(native_request::Operation::RsaOaepDecrypt(
                    RsaOaepDecryptRequest {
                        hash: hash as i32,
                        private_key_pkcs8: pkcs8.clone(),
                        ciphertext,
                    },
                ));
                assert_eq!(response_bytes(decrypted), plaintext);
            }
        }
    }

    #[test]
    fn rsa_operations_match_fixed_openssl_sha1_sha256_and_spki_goldens() {
        let pkcs8 = openssl_rsa_fixture("pkcs8");
        let plaintext = openssl_rsa_fixture("plaintext");
        let spki = invoke(native_request::Operation::RsaPkcs8ToSpki(
            RsaPkcs8ToSpkiRequest {
                private_key_pkcs8: pkcs8.clone(),
            },
        ));
        assert_eq!(response_bytes(spki), openssl_rsa_fixture("spki"));

        for (hash, field) in [
            (RsaOaepHash::Sha1, "oaep_sha1"),
            (RsaOaepHash::Sha256, "oaep_sha256"),
        ] {
            let decrypted = invoke(native_request::Operation::RsaOaepDecrypt(
                RsaOaepDecryptRequest {
                    hash: hash as i32,
                    private_key_pkcs8: pkcs8.clone(),
                    ciphertext: openssl_rsa_fixture(field),
                },
            ));
            assert_eq!(response_bytes(decrypted), plaintext);
        }
    }

    #[test]
    fn rsa_oaep_accepts_bouncy_castle_shortened_positive_integers() {
        let (pkcs8, spki) = rsa_fixture();
        let plaintext = b"leading-zero compatibility".to_vec();
        let ciphertext = (0..4_096)
            .find_map(|_| {
                let encrypted = invoke(native_request::Operation::RsaOaepEncrypt(
                    RsaOaepEncryptRequest {
                        hash: RsaOaepHash::Sha256 as i32,
                        public_key_spki: spki.clone(),
                        plaintext: plaintext.clone(),
                    },
                ));
                let ciphertext = response_bytes(encrypted);
                (ciphertext.first() == Some(&0)).then_some(ciphertext)
            })
            .expect("OAEP test must find a ciphertext with a leading zero");

        let decrypted = invoke(native_request::Operation::RsaOaepDecrypt(
            RsaOaepDecryptRequest {
                hash: RsaOaepHash::Sha256 as i32,
                private_key_pkcs8: pkcs8,
                ciphertext: ciphertext[1..].to_vec(),
            },
        ));
        assert_eq!(response_bytes(decrypted), plaintext);
    }

    #[test]
    fn rsa_operations_return_stable_errors_for_malformed_inputs() {
        let (pkcs8, spki) = rsa_fixture();
        let invalid_key = invoke(native_request::Operation::RsaPkcs8ToSpki(
            RsaPkcs8ToSpkiRequest {
                private_key_pkcs8: b"not a private key".to_vec(),
            },
        ));
        assert_eq!(
            invalid_key.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );

        let mut trailing_pkcs8 = pkcs8.clone();
        trailing_pkcs8.push(0);
        let trailing_private_key = invoke(native_request::Operation::RsaPkcs8ToSpki(
            RsaPkcs8ToSpkiRequest {
                private_key_pkcs8: trailing_pkcs8,
            },
        ));
        assert_eq!(
            trailing_private_key.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );

        let mut trailing_spki = spki.clone();
        trailing_spki.push(0);
        let trailing_public_key = invoke(native_request::Operation::RsaOaepEncrypt(
            RsaOaepEncryptRequest {
                hash: RsaOaepHash::Sha256 as i32,
                public_key_spki: trailing_spki,
                plaintext: b"trailing DER must be rejected".to_vec(),
            },
        ));
        assert_eq!(
            trailing_public_key.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );

        let oversized_plaintext = invoke(native_request::Operation::RsaOaepEncrypt(
            RsaOaepEncryptRequest {
                hash: RsaOaepHash::Sha256 as i32,
                public_key_spki: spki.clone(),
                plaintext: vec![0_u8; 191],
            },
        ));
        assert_eq!(
            oversized_plaintext.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );

        let ciphertext = response_bytes(invoke(native_request::Operation::RsaOaepEncrypt(
            RsaOaepEncryptRequest {
                hash: RsaOaepHash::Sha256 as i32,
                public_key_spki: spki,
                plaintext: b"authenticated rsa oaep".to_vec(),
            },
        )));
        let wrong_hash = invoke(native_request::Operation::RsaOaepDecrypt(
            RsaOaepDecryptRequest {
                hash: RsaOaepHash::Sha1 as i32,
                private_key_pkcs8: pkcs8.clone(),
                ciphertext,
            },
        ));
        assert_eq!(
            wrong_hash.status.map(|status| status.code),
            Some(NativeErrorCode::AuthenticationFailed as i32)
        );

        let oversized_ciphertext = invoke(native_request::Operation::RsaOaepDecrypt(
            RsaOaepDecryptRequest {
                hash: RsaOaepHash::Sha256 as i32,
                private_key_pkcs8: pkcs8,
                ciphertext: vec![0_u8; 257],
            },
        ));
        assert_eq!(
            oversized_ciphertext.status.map(|status| status.code),
            Some(NativeErrorCode::AuthenticationFailed as i32)
        );
    }

    #[test]
    fn malformed_request_returns_typed_error() {
        let response =
            NativeResponse::decode(call(&[0xff]).as_slice()).expect("error response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidRequest as i32)
        );
    }

    #[test]
    fn hkdf_matches_rfc5869_case_one() {
        let response = invoke(native_request::Operation::HkdfSha256(HkdfSha256Request {
            seed: vec![0x0b; 22],
            salt: Some(decode_hex("000102030405060708090a0b0c")),
            info: Some(decode_hex("f0f1f2f3f4f5f6f7f8f9")),
            length: 42,
        }));
        assert_eq!(
            response_bytes(response),
            decode_hex(
                "3cb25f25faacd57a90434f64d0362f2a\
                 2d2d0a90cf1a5a4c5db02d56ecc4c5bf\
                 34007208d5b887185865"
                    .replace(' ', "")
                    .as_str()
            )
        );
    }

    #[test]
    fn hkdf_null_salt_skips_extract_even_for_empty_seed() {
        let anchor = invoke(native_request::Operation::HkdfSha256(HkdfSha256Request {
            seed: (0_u8..32).collect(),
            salt: None,
            info: Some(b"enc".to_vec()),
            length: 32,
        }));
        assert_eq!(
            response_bytes(anchor),
            decode_hex("9c5639fac602366b486253191cb7900d7d8e3a1514676b118d5803a11dd97213")
        );

        let empty = invoke(native_request::Operation::HkdfSha256(HkdfSha256Request {
            seed: Vec::new(),
            salt: None,
            info: Some(Vec::new()),
            length: 32,
        }));
        assert_eq!(response_bytes(empty).len(), 32);
    }

    #[test]
    fn pbkdf2_sha256_matches_known_vector() {
        let response = invoke(native_request::Operation::Pbkdf2Sha256(
            Pbkdf2Sha256Request {
                seed: b"password".to_vec(),
                salt: b"salt".to_vec(),
                iterations: 1,
                length: 32,
            },
        ));
        assert_eq!(
            response_bytes(response),
            decode_hex("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b")
        );
    }

    #[test]
    fn pbkdf2_accepts_every_positive_kotlin_int_iteration_count() {
        let invoke_iterations = |iterations, length| {
            invoke(native_request::Operation::Pbkdf2Sha256(
                Pbkdf2Sha256Request {
                    seed: b"password".to_vec(),
                    salt: b"salt".to_vec(),
                    iterations,
                    length,
                },
            ))
        };

        let zero = invoke_iterations(0, 32);
        assert_eq!(
            zero.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );

        // A zero-length derivation validates the inclusive Kotlin Int boundary
        // without spending more than two billion rounds in the unit suite.
        let boundary = invoke_iterations(primitives::MAX_PBKDF2_ITERATIONS, 0);
        assert_eq!(
            boundary.status.map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );

        let unsigned_only = invoke_iterations(primitives::MAX_PBKDF2_ITERATIONS + 1, 0);
        assert_eq!(
            unsigned_only.status.map(|status| status.code),
            Some(NativeErrorCode::ResourceLimit as i32)
        );
    }

    #[test]
    fn argon2_modes_match_repository_vectors() {
        let cases = [
            (
                Argon2Mode::I,
                "b9c401d1844a67d50eae3967dc28870b22e508092e861a37",
            ),
            (
                Argon2Mode::D,
                "8727405fd07c32c78d64f547f24150d3f2e703a89f981a19",
            ),
            (
                Argon2Mode::Id,
                "655ad15eac652dc59f7170a7332bf49b8469be1fdb9c28bb",
            ),
        ];
        for (mode, expected) in cases {
            let response = invoke(native_request::Operation::Argon2(Argon2Request {
                mode: mode as i32,
                seed: b"password".to_vec(),
                salt: b"somesalt".to_vec(),
                iterations: 1,
                memory_kib: 64,
                parallelism: 1,
                length: 24,
                version: 0,
                secret: None,
                associated_data: None,
            }));
            assert_eq!(response_bytes(response), decode_hex(expected));
        }
    }

    #[test]
    fn argon2_versions_with_secret_and_associated_data_match_reference_vectors() {
        let cases = [
            (
                Argon2Mode::D,
                0x10,
                "96a9d4e5a1734092c85e29f410a45914a5dd1f5cbf08b2670da68a0285abf32b",
            ),
            (
                Argon2Mode::I,
                0x10,
                "87aeedd6517ab830cd9765cd8231abb2e647a5dee08f7c05e02fcb763335d0fd",
            ),
            (
                Argon2Mode::Id,
                0x10,
                "b64615f07789b66b645b67ee9ed3b377ae350b6bfcbb0fc95141ea8f322613c0",
            ),
            (
                Argon2Mode::D,
                0x13,
                "512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb",
            ),
            (
                Argon2Mode::I,
                0x13,
                "c814d9d1dc7f37aa13f0d77f2494bda1c8de6b016dd388d29952a4c4672b6ce8",
            ),
            (
                Argon2Mode::Id,
                0x13,
                "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            ),
        ];
        for (mode, version, expected) in cases {
            let response = invoke(native_request::Operation::Argon2(Argon2Request {
                mode: mode as i32,
                seed: vec![1; 32],
                salt: vec![2; 16],
                iterations: 3,
                memory_kib: 32,
                parallelism: 4,
                length: 32,
                version,
                secret: Some(vec![3; 8]),
                associated_data: Some(vec![4; 12]),
            }));
            assert_eq!(response_bytes(response), decode_hex(expected));
        }
    }

    #[test]
    fn argon2_preserves_bouncy_castle_minimum_output_length() {
        let invoke_length = |length| {
            invoke(native_request::Operation::Argon2(Argon2Request {
                mode: Argon2Mode::Id as i32,
                seed: b"password".to_vec(),
                salt: b"somesalt".to_vec(),
                iterations: 1,
                memory_kib: 8,
                parallelism: 1,
                length,
                version: 0,
                secret: None,
                associated_data: None,
            }))
        };
        for length in 0..4 {
            let response = invoke_length(length);
            assert_eq!(
                response.status.map(|status| status.code),
                Some(NativeErrorCode::InvalidArgument as i32)
            );
        }
        assert_eq!(response_bytes(invoke_length(4)).len(), 4);
    }

    #[test]
    fn argon2_matches_bouncy_castle_short_salt_and_low_memory_goldens() {
        let cases = [
            (
                Argon2Mode::D,
                Vec::new(),
                0,
                1,
                1,
                16,
                "cb063836bdb36a2c1852ec84ffc54252",
            ),
            (
                Argon2Mode::I,
                vec![1],
                1,
                1,
                1,
                16,
                "ed70e423b7baa3bef6be68c903e2450d",
            ),
            (
                Argon2Mode::Id,
                (0_u8..7).collect(),
                7,
                1,
                2,
                32,
                "07c78de530efe48c07a234f55a5ddb58ccfc86409a3ab469a097c9a3a6ba8c6f",
            ),
            (
                Argon2Mode::Id,
                vec![3, 1, 4],
                8,
                2,
                1,
                16,
                "f02eff70d9c7cd447ee93a77e75c2e19",
            ),
        ];

        for (mode, salt, memory_kib, parallelism, iterations, length, expected) in cases {
            let response = invoke(native_request::Operation::Argon2(Argon2Request {
                mode: mode as i32,
                seed: b"password".to_vec(),
                salt,
                iterations,
                memory_kib,
                parallelism,
                length,
                version: 0,
                secret: None,
                associated_data: None,
            }));
            assert_eq!(response_bytes(response), decode_hex(expected));
        }
    }

    #[test]
    fn digest_and_hmac_match_known_vectors() {
        let digests = [
            (
                HashAlgorithm::Sha1,
                "a9993e364706816aba3e25717850c26c9cd0d89d",
            ),
            (
                HashAlgorithm::Sha256,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
            (
                HashAlgorithm::Sha512,
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a\
                 2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            ),
            (HashAlgorithm::Md5, "900150983cd24fb0d6963f7d28e17f72"),
        ];
        for (algorithm, expected) in digests {
            let digest = invoke(native_request::Operation::Digest(DigestRequest {
                algorithm: algorithm as i32,
                data: b"abc".to_vec(),
            }));
            assert_eq!(response_bytes(digest), decode_hex(expected));
        }

        let hmacs = [
            (
                HashAlgorithm::Sha1,
                "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9",
            ),
            (
                HashAlgorithm::Sha256,
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            ),
            (
                HashAlgorithm::Sha512,
                "b42af09057bac1e2d41708e48a902e09b5ff7f12ab428a4fe86653c73dd248fb\
                 82f948a549f7b791a5b41915ee4d1ec3935357e4e2317250d0372afa2ebeeb3a",
            ),
            (HashAlgorithm::Md5, "80070713463e7749b90c2dc24911e275"),
        ];
        for (algorithm, expected) in hmacs {
            let hmac = invoke(native_request::Operation::Hmac(HmacRequest {
                algorithm: algorithm as i32,
                key: b"key".to_vec(),
                data: b"The quick brown fox jumps over the lazy dog".to_vec(),
            }));
            assert_eq!(response_bytes(hmac), decode_hex(expected));
        }
    }

    #[test]
    fn aes_vectors_and_padding_round_trip() {
        let ecb = invoke(native_request::Operation::AesEcbNoPaddingEncrypt(
            AesEcbNoPaddingEncryptRequest {
                key: decode_hex("000102030405060708090a0b0c0d0e0f"),
                data: decode_hex("00112233445566778899aabbccddeeff"),
            },
        ));
        assert_eq!(
            response_bytes(ecb),
            decode_hex("69c4e0d86a7b0430d8cdb78070b4c55a")
        );

        let key: Vec<u8> = (0_u8..32).collect();
        let iv: Vec<u8> = (16_u8..32).collect();
        let plaintext = b"hello".to_vec();
        let encrypted = response_bytes(invoke(native_request::Operation::AesCbcPkcs7(
            AesCbcPkcs7Request {
                direction: CipherDirection::Encrypt as i32,
                key: key.clone(),
                iv: iv.clone(),
                data: plaintext.clone(),
            },
        )));
        let decrypted = response_bytes(invoke(native_request::Operation::AesCbcPkcs7(
            AesCbcPkcs7Request {
                direction: CipherDirection::Decrypt as i32,
                key,
                iv,
                data: encrypted,
            },
        )));
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn fused_aes_cbc_hmac_sha256_matches_independent_golden_and_round_trips() {
        let encryption_key: Vec<u8> = (0_u8..32).collect();
        let mac_key: Vec<u8> = (32_u8..64).collect();
        let iv: Vec<u8> = (64_u8..80).collect();
        let plaintext = b"Bitwarden fused AES-CBC/HMAC test vector".to_vec();

        let encrypted = response_bytes(invoke(
            native_request::Operation::AesCbcPkcs7HmacSha256Encrypt(
                AesCbcPkcs7HmacSha256EncryptRequest {
                    encryption_key: encryption_key.clone(),
                    mac_key: mac_key.clone(),
                    iv: iv.clone(),
                    plaintext: plaintext.clone(),
                },
            ),
        ));
        let encrypted = AesCbcPkcs7HmacSha256EncryptResult::decode(encrypted.as_slice())
            .expect("fused encryption result must decode");
        assert_eq!(
            encrypted.ciphertext,
            decode_hex(
                "b3ae5dcb9dd806f8266f89d2e9e3489d37964364df9a2b1767d16f3fda8f82ae\
                 088a3c1a342b9b5b72417ed002bc0248"
                    .replace(' ', "")
                    .as_str()
            )
        );
        assert_eq!(
            encrypted.mac,
            decode_hex("6f9cc3bd0c5cd61850923fe87d0edb133fc1f84e7f7a513658b87dd2d35359c8")
        );

        let decrypted = response_bytes(invoke(
            native_request::Operation::AesCbcPkcs7HmacSha256Decrypt(
                AesCbcPkcs7HmacSha256DecryptRequest {
                    encryption_key,
                    mac_key,
                    iv,
                    ciphertext: encrypted.ciphertext,
                    expected_mac: encrypted.mac,
                },
            ),
        ));
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn fused_aes_cbc_hmac_sha256_authenticates_before_decrypting() {
        let encryption_key = vec![0x11; 32];
        let mac_key = vec![0x22; 32];
        let iv = vec![0x33; 16];
        let malformed_ciphertext = vec![0x44; 15];

        let unauthenticated = invoke(native_request::Operation::AesCbcPkcs7HmacSha256Decrypt(
            AesCbcPkcs7HmacSha256DecryptRequest {
                encryption_key: encryption_key.clone(),
                mac_key: mac_key.clone(),
                iv: iv.clone(),
                ciphertext: malformed_ciphertext.clone(),
                expected_mac: vec![0x55; 32],
            },
        ));
        assert_eq!(
            unauthenticated.status.map(|status| status.code),
            Some(NativeErrorCode::AuthenticationFailed as i32)
        );
        assert!(unauthenticated.result.is_none());

        let mut authenticated_data = iv.clone();
        authenticated_data.extend_from_slice(&malformed_ciphertext);
        let valid_mac = response_bytes(invoke(native_request::Operation::Hmac(HmacRequest {
            algorithm: HashAlgorithm::Sha256 as i32,
            key: mac_key.clone(),
            data: authenticated_data,
        })));
        let malformed = invoke(native_request::Operation::AesCbcPkcs7HmacSha256Decrypt(
            AesCbcPkcs7HmacSha256DecryptRequest {
                encryption_key,
                mac_key,
                iv,
                ciphertext: malformed_ciphertext,
                expected_mac: valid_mac,
            },
        ));
        assert_eq!(
            malformed.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidArgument as i32)
        );
        assert!(malformed.result.is_none());
    }

    #[test]
    fn fused_aes_cbc_hmac_sha256_requests_zeroize_on_failure() {
        let cases = [
            native_request::Operation::AesCbcPkcs7HmacSha256Encrypt(
                AesCbcPkcs7HmacSha256EncryptRequest {
                    encryption_key: b"invalid encryption key".to_vec(),
                    mac_key: b"secret mac key".to_vec(),
                    iv: vec![0x33; 16],
                    plaintext: b"secret plaintext".to_vec(),
                },
            ),
            native_request::Operation::AesCbcPkcs7HmacSha256Decrypt(
                AesCbcPkcs7HmacSha256DecryptRequest {
                    encryption_key: vec![0x11; 32],
                    mac_key: b"secret mac key".to_vec(),
                    iv: vec![0x33; 16],
                    ciphertext: vec![0x44; 16],
                    expected_mac: vec![0x55; 32],
                },
            ),
        ];

        for operation in cases {
            let encoded = NativeRequest {
                protocol_version: PROTOCOL_VERSION,
                operation: Some(operation),
            }
            .encode_to_vec();
            protocol::reset_zeroized_secret_request_drops();

            let response = NativeResponse::decode(call(&encoded).as_slice())
                .expect("fused failure response must decode");
            assert_ne!(
                response.status.map(|status| status.code),
                Some(NativeErrorCode::Ok as i32)
            );
            assert_eq!(protocol::zeroized_secret_request_drops(), 1);
        }
    }

    #[test]
    fn repeated_aes_transform_preserves_kdbx_loop_semantics() {
        let key = decode_hex("000102030405060708090a0b0c0d0e0f");
        let data = decode_hex("00112233445566778899aabbccddeeff");
        let once = response_bytes(invoke(native_request::Operation::AesEcbNoPaddingEncrypt(
            AesEcbNoPaddingEncryptRequest {
                key: key.clone(),
                data: data.clone(),
            },
        )));
        let twice_sequential = response_bytes(invoke(
            native_request::Operation::AesEcbNoPaddingEncrypt(AesEcbNoPaddingEncryptRequest {
                key: key.clone(),
                data: once.clone(),
            }),
        ));

        let zero = response_bytes(invoke(native_request::Operation::AesEcbNoPaddingTransform(
            AesEcbNoPaddingTransformRequest {
                key: Vec::new(),
                data: b"zero rounds do not inspect blocks".to_vec(),
                rounds: 0,
            },
        )));
        assert_eq!(zero, b"zero rounds do not inspect blocks");

        let transformed_once = response_bytes(invoke(
            native_request::Operation::AesEcbNoPaddingTransform(AesEcbNoPaddingTransformRequest {
                key: key.clone(),
                data: data.clone(),
                rounds: 1,
            }),
        ));
        assert_eq!(transformed_once, once);
        let transformed_twice = response_bytes(invoke(
            native_request::Operation::AesEcbNoPaddingTransform(AesEcbNoPaddingTransformRequest {
                key: key.clone(),
                data,
                rounds: 2,
            }),
        ));
        assert_eq!(transformed_twice, twice_sequential);

        let boundary = invoke(native_request::Operation::AesEcbNoPaddingTransform(
            AesEcbNoPaddingTransformRequest {
                key: key.clone(),
                data: Vec::new(),
                rounds: primitives::MAX_AES_TRANSFORM_ROUNDS,
            },
        ));
        assert_eq!(
            boundary.status.map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );
        let excessive_rounds = invoke(native_request::Operation::AesEcbNoPaddingTransform(
            AesEcbNoPaddingTransformRequest {
                key: key.clone(),
                data: Vec::new(),
                rounds: primitives::MAX_AES_TRANSFORM_ROUNDS + 1,
            },
        ));
        assert_eq!(
            excessive_rounds.status.map(|status| status.code),
            Some(NativeErrorCode::ResourceLimit as i32)
        );
        let excessive_product = invoke(native_request::Operation::AesEcbNoPaddingTransform(
            AesEcbNoPaddingTransformRequest {
                key,
                data: vec![0; 3 * 16],
                rounds: primitives::MAX_AES_TRANSFORM_ROUNDS,
            },
        ));
        assert_eq!(
            excessive_product.status.map(|status| status.code),
            Some(NativeErrorCode::ResourceLimit as i32)
        );
    }

    #[test]
    fn kdbx_stream_ciphers_match_independent_vectors_and_absolute_offsets() {
        let mut salsa_key = vec![0_u8; 32];
        salsa_key[0] = 0x80;
        let salsa = response_bytes(invoke(native_request::Operation::StreamCipherXorAtOffset(
            StreamCipherXorAtOffsetRequest {
                algorithm: StreamCipherAlgorithm::Salsa20 as i32,
                key: salsa_key,
                nonce: vec![0; 8],
                offset: 0,
                data: vec![0; 8],
            },
        )));
        assert_eq!(salsa, decode_hex("e3be8fdd8beca2e3"));

        let key: Vec<u8> = (0_u8..32).collect();
        let nonce = decode_hex("000000000000004a00000000");
        let plaintext = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".to_vec();
        let expected = decode_hex(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b\
             f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8\
             07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736\
             5af90bbf74a35be6b40b8eedf2785e42874d"
                .replace(' ', "")
                .as_str(),
        );
        let transform = |offset: u64, data: Vec<u8>| {
            response_bytes(invoke(native_request::Operation::StreamCipherXorAtOffset(
                StreamCipherXorAtOffsetRequest {
                    algorithm: StreamCipherAlgorithm::Chacha20 as i32,
                    key: key.clone(),
                    nonce: nonce.clone(),
                    offset,
                    data,
                },
            )))
        };
        assert_eq!(transform(64, plaintext.clone()), expected);

        let input: Vec<u8> = (0_u8..130).collect();
        let one_shot = transform(0, input.clone());
        let mut partitioned = Vec::new();
        for (start, end) in [(0, 63), (63, 64), (64, 65), (65, input.len())] {
            partitioned.extend(transform(start as u64, input[start..end].to_vec()));
        }
        assert_eq!(partitioned, one_shot);
        assert_eq!(transform(0, one_shot), input);
    }

    #[test]
    fn twofish_cbc_pkcs7_matches_vectors_streams_and_rejects_bad_padding() {
        let key = vec![0_u8; 16];
        let iv = vec![0_u8; 16];
        let plaintext = decode_hex("80000000000000000000000000000000");
        let expected =
            decode_hex("73b9ff14cf2589901ff52a0d6f4b7edef10da92ef7a287d4f38319cbf7ab1570");
        let ciphertext = response_bytes(invoke(native_request::Operation::TwofishCbcPkcs7(
            TwofishCbcPkcs7Request {
                direction: CipherDirection::Encrypt as i32,
                key: key.clone(),
                iv: iv.clone(),
                data: plaintext.clone(),
            },
        )));
        assert_eq!(ciphertext, expected);

        for chunk_size in [1, 7, 16, 31] {
            let encrypt_handle =
                open_stream(native_stream_open_request::Operation::TwofishCbcPkcs7(
                    TwofishCbcPkcs7StreamOpenRequest {
                        direction: CipherDirection::Encrypt as i32,
                        key: key.clone(),
                        iv: iv.clone(),
                    },
                ));
            let mut streamed = Vec::new();
            for chunk in plaintext.chunks(chunk_size) {
                streamed.extend(response_bytes(update_stream(encrypt_handle, chunk)));
            }
            streamed.extend(response_bytes(finish_stream(encrypt_handle)));
            assert_eq!(streamed, expected);

            let decrypt_handle =
                open_stream(native_stream_open_request::Operation::TwofishCbcPkcs7(
                    TwofishCbcPkcs7StreamOpenRequest {
                        direction: CipherDirection::Decrypt as i32,
                        key: key.clone(),
                        iv: iv.clone(),
                    },
                ));
            let mut decrypted = Vec::new();
            for chunk in streamed.chunks(chunk_size) {
                decrypted.extend(response_bytes(update_stream(decrypt_handle, chunk)));
            }
            decrypted.extend(response_bytes(finish_stream(decrypt_handle)));
            assert_eq!(decrypted, plaintext);
        }

        let mut corrupted = expected;
        let last = corrupted.len() - 1;
        corrupted[last] = 0;
        let response = invoke(native_request::Operation::TwofishCbcPkcs7(
            TwofishCbcPkcs7Request {
                direction: CipherDirection::Decrypt as i32,
                key,
                iv,
                data: corrupted,
            },
        ));
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::AuthenticationFailed as i32),
        );
    }

    #[test]
    fn oversized_success_response_is_replaced_with_resource_limit() {
        let request = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_request::Operation::AesEcbNoPaddingTransform(
                AesEcbNoPaddingTransformRequest {
                    key: Vec::new(),
                    data: vec![0x5a; MAX_CONTROL_ENVELOPE_BYTES - 32],
                    rounds: 0,
                },
            )),
        }
        .encode_to_vec();
        assert!(request.len() <= MAX_CONTROL_ENVELOPE_BYTES);

        let response = NativeResponse::decode(call(&request).as_slice())
            .expect("resource-limit response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::ResourceLimit as i32)
        );
    }

    #[test]
    fn generic_digest_and_hmac_streams_match_one_shot_at_arbitrary_boundaries() {
        let data: Vec<u8> = (0_u16..521).map(|value| value as u8).collect();
        for algorithm in [
            HashAlgorithm::Sha1,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha512,
            HashAlgorithm::Md5,
        ] {
            let expected_digest =
                response_bytes(invoke(native_request::Operation::Digest(DigestRequest {
                    algorithm: algorithm as i32,
                    data: data.clone(),
                })));
            let digest_handle = open_stream(native_stream_open_request::Operation::Digest(
                DigestStreamOpenRequest {
                    algorithm: algorithm as i32,
                },
            ));
            for chunk in data.chunks(37) {
                assert!(response_bytes(update_stream(digest_handle, chunk)).is_empty());
            }
            assert_eq!(
                response_bytes(finish_stream(digest_handle)),
                expected_digest
            );

            let key = b"streaming-key".to_vec();
            let expected_hmac =
                response_bytes(invoke(native_request::Operation::Hmac(HmacRequest {
                    algorithm: algorithm as i32,
                    key: key.clone(),
                    data: data.clone(),
                })));
            let hmac_handle = open_stream(native_stream_open_request::Operation::Hmac(
                HmacStreamOpenRequest {
                    algorithm: algorithm as i32,
                    key,
                },
            ));
            for chunk in data.chunks(29) {
                assert!(response_bytes(update_stream(hmac_handle, chunk)).is_empty());
            }
            assert_eq!(response_bytes(finish_stream(hmac_handle)), expected_hmac);
        }
    }

    #[test]
    fn aes_cbc_streams_match_one_shot_for_chunk_and_padding_boundaries() {
        let key: Vec<u8> = (0_u8..32).collect();
        let iv: Vec<u8> = (32_u8..48).collect();
        for plaintext_length in [0, 1, 15, 16, 17, 31, 32, 33, 127] {
            let plaintext: Vec<u8> = (0..plaintext_length).map(|value| value as u8).collect();
            let expected_ciphertext = response_bytes(invoke(
                native_request::Operation::AesCbcPkcs7(AesCbcPkcs7Request {
                    direction: CipherDirection::Encrypt as i32,
                    key: key.clone(),
                    iv: iv.clone(),
                    data: plaintext.clone(),
                }),
            ));
            for chunk_size in [1, 7, 16, 31, 64] {
                let encrypt_handle =
                    open_stream(native_stream_open_request::Operation::AesCbcPkcs7(
                        AesCbcPkcs7StreamOpenRequest {
                            direction: CipherDirection::Encrypt as i32,
                            key: key.clone(),
                            iv: iv.clone(),
                        },
                    ));
                let mut ciphertext = Vec::new();
                for chunk in plaintext.chunks(chunk_size) {
                    ciphertext.extend(response_bytes(update_stream(encrypt_handle, chunk)));
                }
                ciphertext.extend(response_bytes(finish_stream(encrypt_handle)));
                assert_eq!(ciphertext, expected_ciphertext);

                let decrypt_handle =
                    open_stream(native_stream_open_request::Operation::AesCbcPkcs7(
                        AesCbcPkcs7StreamOpenRequest {
                            direction: CipherDirection::Decrypt as i32,
                            key: key.clone(),
                            iv: iv.clone(),
                        },
                    ));
                let mut decrypted = Vec::new();
                for chunk in ciphertext.chunks(chunk_size) {
                    decrypted.extend(response_bytes(update_stream(decrypt_handle, chunk)));
                }
                decrypted.extend(response_bytes(finish_stream(decrypt_handle)));
                assert_eq!(decrypted, plaintext);
            }
        }
    }

    #[test]
    fn fused_aes_cbc_hmac_streams_match_one_shot_and_authenticate_at_finish() {
        let encryption_key: Vec<u8> = (0_u8..32).collect();
        let mac_key: Vec<u8> = (32_u8..64).collect();
        let iv: Vec<u8> = (64_u8..80).collect();
        let plaintext: Vec<u8> = (0..100_003).map(|value| (value * 31 + 7) as u8).collect();
        let expected = response_bytes(invoke(
            native_request::Operation::AesCbcPkcs7HmacSha256Encrypt(
                AesCbcPkcs7HmacSha256EncryptRequest {
                    encryption_key: encryption_key.clone(),
                    mac_key: mac_key.clone(),
                    iv: iv.clone(),
                    plaintext: plaintext.clone(),
                },
            ),
        ));
        let expected = AesCbcPkcs7HmacSha256EncryptResult::decode(expected.as_slice())
            .expect("one-shot fused result must decode");

        let encrypt_handle = open_stream(
            native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Encrypt(
                AesCbcPkcs7HmacSha256EncryptStreamOpenRequest {
                    encryption_key: encryption_key.clone(),
                    mac_key: mac_key.clone(),
                    iv: iv.clone(),
                },
            ),
        );
        let mut ciphertext = Vec::new();
        for chunk in plaintext.chunks(64 * 1024) {
            ciphertext.extend(response_bytes(update_stream(encrypt_handle, chunk)));
        }
        let final_result = response_bytes(finish_stream(encrypt_handle));
        let final_result = AesCbcPkcs7HmacSha256EncryptResult::decode(final_result.as_slice())
            .expect("streamed fused result must decode");
        ciphertext.extend_from_slice(&final_result.ciphertext);
        assert_eq!(ciphertext, expected.ciphertext);
        assert_eq!(final_result.mac, expected.mac);

        let decrypt_handle = open_stream(
            native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Decrypt(
                AesCbcPkcs7HmacSha256DecryptStreamOpenRequest {
                    encryption_key: encryption_key.clone(),
                    mac_key: mac_key.clone(),
                    iv: iv.clone(),
                    expected_mac: final_result.mac.clone(),
                },
            ),
        );
        let mut decrypted = Vec::new();
        for chunk in ciphertext.chunks(64 * 1024) {
            decrypted.extend(response_bytes(update_stream(decrypt_handle, chunk)));
        }
        decrypted.extend(response_bytes(finish_stream(decrypt_handle)));
        assert_eq!(decrypted, plaintext);

        let mut tampered_mac = final_result.mac;
        tampered_mac[0] ^= 1;
        let tampered_handle = open_stream(
            native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Decrypt(
                AesCbcPkcs7HmacSha256DecryptStreamOpenRequest {
                    encryption_key,
                    mac_key,
                    iv,
                    expected_mac: tampered_mac,
                },
            ),
        );
        for chunk in ciphertext.chunks(64 * 1024) {
            response_bytes(update_stream(tampered_handle, chunk));
        }
        let failed = finish_stream(tampered_handle);
        assert_eq!(
            failed.status.map(|status| status.code),
            Some(NativeErrorCode::AuthenticationFailed as i32)
        );
        let reused = update_stream(tampered_handle, b"reused");
        assert_eq!(
            reused.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidSession as i32)
        );
    }

    #[test]
    fn aes_cbc_decrypt_authentication_failure_consumes_session() {
        let key = vec![0x11; 32];
        let iv = vec![0x22; 16];
        let plaintext = vec![0x33; 80];
        let mut ciphertext = response_bytes(invoke(native_request::Operation::AesCbcPkcs7(
            AesCbcPkcs7Request {
                direction: CipherDirection::Encrypt as i32,
                key: key.clone(),
                iv: iv.clone(),
                data: plaintext,
            },
        )));
        let final_previous_byte = ciphertext.len() - 16 - 1;
        ciphertext[final_previous_byte] ^= 0xff;
        let handle = open_stream(native_stream_open_request::Operation::AesCbcPkcs7(
            AesCbcPkcs7StreamOpenRequest {
                direction: CipherDirection::Decrypt as i32,
                key,
                iv,
            },
        ));
        let unauthenticated = response_bytes(update_stream(handle, &ciphertext));
        assert!(!unauthenticated.is_empty());
        let failed = finish_stream(handle);
        assert_eq!(
            failed.status.map(|status| status.code),
            Some(NativeErrorCode::AuthenticationFailed as i32)
        );
        let reused = update_stream(handle, b"reused");
        assert_eq!(
            reused.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidSession as i32)
        );
    }

    #[test]
    fn unsigned_wire_resource_fields_are_bounded_before_work() {
        let cases = [
            invoke(native_request::Operation::HkdfSha256(HkdfSha256Request {
                seed: b"seed".to_vec(),
                salt: None,
                info: None,
                length: u32::MAX,
            })),
            invoke(native_request::Operation::RandomBytes(RandomBytesRequest {
                length: u32::MAX,
            })),
            invoke(native_request::Operation::Pbkdf2Sha256(
                Pbkdf2Sha256Request {
                    seed: b"p".to_vec(),
                    salt: b"s".to_vec(),
                    iterations: 1,
                    length: u32::MAX,
                },
            )),
            invoke(native_request::Operation::Argon2(Argon2Request {
                mode: Argon2Mode::Id as i32,
                seed: b"p".to_vec(),
                salt: b"12345678".to_vec(),
                iterations: u32::MAX,
                memory_kib: 8,
                parallelism: 1,
                length: 32,
                version: 0,
                secret: None,
                associated_data: None,
            })),
            invoke(native_request::Operation::Argon2(Argon2Request {
                mode: Argon2Mode::Id as i32,
                seed: b"p".to_vec(),
                salt: b"12345678".to_vec(),
                iterations: 1,
                memory_kib: u32::MAX,
                parallelism: 1,
                length: 32,
                version: 0,
                secret: None,
                associated_data: None,
            })),
            invoke(native_request::Operation::Argon2(Argon2Request {
                mode: Argon2Mode::Id as i32,
                seed: b"p".to_vec(),
                salt: b"12345678".to_vec(),
                iterations: 1,
                memory_kib: 8,
                parallelism: u32::MAX,
                length: 32,
                version: 0,
                secret: None,
                associated_data: None,
            })),
            invoke(native_request::Operation::Argon2(Argon2Request {
                mode: Argon2Mode::Id as i32,
                seed: b"p".to_vec(),
                salt: b"12345678".to_vec(),
                iterations: 1,
                memory_kib: 8,
                parallelism: 1,
                length: u32::MAX,
                version: 0,
                secret: None,
                associated_data: None,
            })),
        ];
        for response in cases {
            assert_eq!(
                response.status.map(|status| status.code),
                Some(NativeErrorCode::ResourceLimit as i32)
            );
        }
    }

    #[test]
    fn openpgp_parser_quotas_map_to_stable_resource_limit_status() {
        let oversized_documents = vec![Vec::new(); 65];
        for response in [
            invoke(native_request::Operation::OpenPgpVerify(
                OpenPgpVerifyRequest {
                    kind: OpenPgpVerifyKind::Detached as i32,
                    content: Vec::new(),
                    signature: Vec::new(),
                    public_keys: oversized_documents.clone(),
                    reference_time_epoch_seconds: Some(0),
                },
            )),
            invoke(native_request::Operation::OpenPgpMetadataResolve(
                OpenPgpMetadataResolveRequest {
                    private_key_data: None,
                    public_key_data: None,
                    normalized_fingerprint: String::new(),
                    candidate_revocation_keys: oversized_documents.clone(),
                    reference_time_epoch_seconds: Some(0),
                },
            )),
        ] {
            assert_eq!(
                response.status.map(|status| status.code),
                Some(NativeErrorCode::ResourceLimit as i32),
            );
        }

        let open = NativeStreamOpenRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(
                native_stream_open_request::Operation::OpenPgpDetachedVerify(
                    OpenPgpDetachedVerifyStreamOpenRequest {
                        signature: Vec::new(),
                        public_keys: oversized_documents,
                        reference_time_epoch_seconds: Some(0),
                    },
                ),
            ),
        };
        let response = NativeResponse::decode(stream_open(&open.encode_to_vec()).as_slice())
            .expect("stream-open response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::ResourceLimit as i32),
        );
    }

    #[test]
    fn bounded_random_int_stays_in_range() {
        for _ in 0..256 {
            let response = invoke(native_request::Operation::RandomInt(RandomIntRequest {
                bounded: true,
                exclusive_upper_bound: 7,
            }));
            match response.result {
                Some(native_response::Result::Int32Value(value)) => {
                    assert!((0..7).contains(&value));
                }
                _ => panic!("expected integer response"),
            }
        }
    }

    #[test]
    fn batched_random_ints_are_exact_little_endian_values() {
        let bytes = response_bytes(invoke(native_request::Operation::RandomInts(
            RandomIntsRequest {
                bounded: true,
                exclusive_upper_bound: 256,
                count: 17,
            },
        )));
        assert_eq!(bytes.len(), 17 * size_of::<i32>());
        for encoded in bytes.as_chunks::<{ size_of::<i32>() }>().0 {
            assert_eq!(&encoded[1..], &[0, 0, 0]);
            let value = i32::from_le_bytes(*encoded);
            assert!((0..256).contains(&value));
            assert_eq!(value.to_le_bytes(), *encoded);
        }
    }

    #[test]
    fn batched_random_ints_preserve_bounds_and_resource_limit() {
        let all_zero = response_bytes(invoke(native_request::Operation::RandomInts(
            RandomIntsRequest {
                bounded: true,
                exclusive_upper_bound: 1,
                count: 32,
            },
        )));
        assert_eq!(all_zero, vec![0; 32 * size_of::<i32>()]);

        for bound in [2, 7, i32::MAX as u32] {
            let bytes = response_bytes(invoke(native_request::Operation::RandomInts(
                RandomIntsRequest {
                    bounded: true,
                    exclusive_upper_bound: bound,
                    count: 256,
                },
            )));
            assert_eq!(bytes.len(), 256 * size_of::<i32>());
            for encoded in bytes.as_chunks::<{ size_of::<i32>() }>().0 {
                let value = i32::from_le_bytes(*encoded);
                assert!(value >= 0);
                assert!(u32::try_from(value).is_ok_and(|value| value < bound));
            }
        }

        let oversized = invoke(native_request::Operation::RandomInts(RandomIntsRequest {
            bounded: false,
            exclusive_upper_bound: 0,
            count: 1025,
        }));
        assert_eq!(
            oversized.status.map(|status| status.code),
            Some(NativeErrorCode::ResourceLimit as i32)
        );
    }

    #[test]
    fn streaming_hmac_enforces_lifecycle() {
        let open = NativeStreamOpenRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_stream_open_request::Operation::HmacSha256(
                HmacSha256StreamOpenRequest {
                    key: b"key".to_vec(),
                },
            )),
        };
        let response = NativeResponse::decode(stream_open(&open.encode_to_vec()).as_slice())
            .expect("open response must decode");
        let handle = match response.result {
            Some(native_response::Result::Uint64Value(handle)) => handle,
            _ => panic!("expected session handle"),
        };

        let update = NativeResponse::decode(stream_update(handle, b"abc").as_slice())
            .expect("update response must decode");
        assert_eq!(
            update.status.map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );
        let finished = NativeResponse::decode(stream_finish(handle).as_slice())
            .expect("finish response must decode");
        assert_eq!(
            response_bytes(finished),
            decode_hex("9c196e32dc0175f86f4b1cb89289d6619de6bee699e4c378e68309ed97a1a6ab")
        );

        let reused = NativeResponse::decode(stream_update(handle, b"x").as_slice())
            .expect("error response must decode");
        assert_eq!(
            reused.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidSession as i32)
        );
        let closed = NativeResponse::decode(stream_close(handle).as_slice())
            .expect("close response must decode");
        assert_eq!(
            closed.status.map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );
        let closed_again = NativeResponse::decode(stream_close(handle).as_slice())
            .expect("second close response must decode");
        assert_eq!(
            closed_again.status.map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );

        // Finished sessions must release their registry slot even when the
        // high-level client correctly treats finish as consuming close.
        let mut last_finished_handle = 0;
        for _ in 0..1_100 {
            let response = NativeResponse::decode(stream_open(&open.encode_to_vec()).as_slice())
                .expect("repeated open response must decode");
            let handle = match response.result {
                Some(native_response::Result::Uint64Value(handle)) => handle,
                _ => panic!("expected repeated session handle"),
            };
            last_finished_handle = handle;
            let response = NativeResponse::decode(stream_finish(handle).as_slice())
                .expect("repeated finish response must decode");
            assert_eq!(
                response.status.map(|status| status.code),
                Some(NativeErrorCode::Ok as i32)
            );
        }

        let replacement = NativeResponse::decode(stream_open(&open.encode_to_vec()).as_slice())
            .expect("replacement open response must decode");
        let replacement_handle = match replacement.result {
            Some(native_response::Result::Uint64Value(handle)) => handle,
            _ => panic!("expected replacement session handle"),
        };
        let stale_close = NativeResponse::decode(stream_close(last_finished_handle).as_slice())
            .expect("stale close response must decode");
        assert_eq!(
            stale_close.status.map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );
        let replacement_update =
            NativeResponse::decode(stream_update(replacement_handle, b"replacement").as_slice())
                .expect("replacement update response must decode");
        assert_eq!(
            replacement_update.status.map(|status| status.code),
            Some(NativeErrorCode::Ok as i32)
        );
        let _ = stream_finish(replacement_handle);
    }
}
