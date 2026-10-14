//! Bounded SSH private-key import and normalization.
//!
//! This module is intentionally parser-only for asymmetric key material.
//! Symmetric decryption is used for encrypted containers, but RSA private
//! operations remain confined to the AWS-LC sensitive backend.
//!
//! Import-only KDF limits are part of the resource-exhaustion policy:
//! OpenSSH bcrypt accepts at most 1,024 rounds and a 1 KiB salt. PuTTY v3
//! Argon2 accepts at most 32 passes, 64 MiB of memory, eight lanes, a 1 KiB
//! salt, and a combined 256 MiB-pass work factor. PBES2 limits are documented
//! alongside its parser. Inputs over these caps fail before KDF execution.

use std::collections::HashMap;

use base64ct::{Base64, Encoding};
use keyguard_crypto_sensitive::{
    DigestAlgorithm, DigestContext, HmacContext, RsaPrimeComponents, RsaPrivateComponents,
    SensitiveBackendError, complete_rsa_pkcs1_der,
};
use pkcs1::RsaPrivateKey;
use pkcs8::{
    AlgorithmIdentifierRef, ObjectIdentifier, PrivateKeyInfo,
    der::{
        Decode as _, Encode as _,
        asn1::{AnyRef, OctetStringRef, UintRef},
    },
};
use prost::Message;
use ssh_key::{
    Cipher, Error as SshKeyError, Kdf, KdfAlg, PrivateKey, PublicKey as SshPublicKey,
    private::{KeypairData, RsaKeypair},
};
use zeroize::Zeroizing;

use crate::{
    legacy_pem::{LegacyPemError, decrypt_legacy_openssl_pem},
    pkcs8_pbes2::{Pbes2Error, decrypt_jdk21_encrypted_pkcs8},
    primitives::{self, PrimitiveError},
    protocol::{
        Argon2Mode, SshKeyMaterial, SshKeyType, SshPrivateKeyImportError,
        SshPrivateKeyImportErrorReason, SshPrivateKeyImportNeedsPassphrase,
        SshPrivateKeyImportResult, SshPrivateKeyImportSuccess, ssh_private_key_import_result,
    },
    ssh_keys,
};

const MAX_IMPORT_TEXT_BYTES: usize = 1024 * 1024;
const MAX_DECODED_KEY_BYTES: usize = 512 * 1024;
const MAX_PUTTY_PAYLOAD_LINES: usize = 8192;
const MAX_RSA_COMPONENT_BYTES: usize = 2_048;
const MAX_PASSPHRASE_BYTES: usize = 16 * 1024;
const MAX_OPENSSH_BCRYPT_ROUNDS: u32 = 1_024;
const MAX_OPENSSH_BCRYPT_SALT_BYTES: usize = 1_024;
const MAX_PUTTY_ARGON2_PASSES: u32 = 32;
const MAX_PUTTY_ARGON2_MEMORY_KIB: u32 = 64 * 1_024;
const MAX_PUTTY_ARGON2_PARALLELISM: u32 = 8;
const MAX_PUTTY_ARGON2_SALT_BYTES: usize = 1_024;
const MAX_PUTTY_ARGON2_WORK_KIB_PASSES: u64 = 256 * 1_024;
const RSA_ENCRYPTION_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.1.1.1");
const ED25519_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.3.101.112");
const PUTTY_MAC_PREFIX: &[u8] = b"putty-private-key-file-mac-key";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ImportError {
    UnsupportedFormat,
    UnsupportedAlgorithm,
    InvalidPassphrase,
    MalformedKey,
    ResourceLimit,
    BackendFailure,
}

impl ImportError {
    const fn reason(self) -> Option<SshPrivateKeyImportErrorReason> {
        match self {
            Self::UnsupportedFormat => Some(SshPrivateKeyImportErrorReason::UnsupportedFormat),
            Self::UnsupportedAlgorithm => {
                Some(SshPrivateKeyImportErrorReason::UnsupportedAlgorithm)
            }
            Self::InvalidPassphrase => Some(SshPrivateKeyImportErrorReason::InvalidPassphrase),
            Self::MalformedKey => Some(SshPrivateKeyImportErrorReason::MalformedKey),
            Self::ResourceLimit | Self::BackendFailure => None,
        }
    }
}

enum ImportOutcome {
    Success(SshKeyMaterial),
    NeedsPassphrase(&'static str),
    Error(SshPrivateKeyImportErrorReason),
}

pub(super) fn import(
    content: Vec<u8>,
    passphrase_utf8: Option<Vec<u8>>,
) -> Result<Vec<u8>, PrimitiveError> {
    let content = Zeroizing::new(content);
    let passphrase = passphrase_utf8.map(Zeroizing::new);
    if content.is_empty() {
        return encode_outcome(ImportOutcome::Error(
            SshPrivateKeyImportErrorReason::UnsupportedFormat,
        ));
    }
    if content.len() > MAX_IMPORT_TEXT_BYTES
        || passphrase
            .as_ref()
            .is_some_and(|value| value.len() > MAX_PASSPHRASE_BYTES)
    {
        return Err(PrimitiveError::ResourceLimit);
    }
    let passphrase = passphrase.as_ref().map(|value| value.as_slice());
    if passphrase.is_some_and(|value| std::str::from_utf8(value).is_err()) {
        return Err(PrimitiveError::InvalidArgument);
    }

    // PPK comments can contain arbitrary bytes. Preserve them through the
    // file and wire boundaries; PEM/OpenSSH still require UTF-8 text.
    let text = std::str::from_utf8(&content).ok().map(str::trim);
    let content = text.map_or_else(|| content.trim_ascii(), str::as_bytes);
    if passphrase.is_none() {
        let format_label = if content.starts_with(b"PuTTY-User-Key-File-")
            && shallow_putty_is_encrypted(content)
        {
            Some("PuTTY")
        } else if text
            .is_some_and(|text| text.starts_with("-----BEGIN ") && shallow_pem_is_encrypted(text))
        {
            Some("PEM")
        } else {
            None
        };
        if let Some(format_label) = format_label {
            return encode_outcome(ImportOutcome::NeedsPassphrase(format_label));
        }
    }
    let result = if content.starts_with(b"PuTTY-User-Key-File-") {
        import_putty(content, passphrase)
    } else {
        match text {
            Some(text) if text.starts_with("-----BEGIN OPENSSH PRIVATE KEY-----") => {
                import_openssh(text, passphrase)
            }
            Some(text) if text.starts_with("-----BEGIN ") => import_pem(text, passphrase),
            // Armored keys are text; other binary files are not keys at all.
            None if content.starts_with(b"-----BEGIN ") => Err(ImportError::MalformedKey),
            _ => Err(ImportError::UnsupportedFormat),
        }
    };

    match result {
        Ok(outcome) => encode_outcome(outcome),
        Err(error) => match error.reason() {
            Some(reason) => encode_outcome(ImportOutcome::Error(reason)),
            None if error == ImportError::ResourceLimit => Err(PrimitiveError::ResourceLimit),
            None => Err(PrimitiveError::CryptoFailure),
        },
    }
}

fn shallow_pem_is_encrypted(content: &str) -> bool {
    content.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----")
        || content.lines().any(|line| {
            line.trim()
                .strip_prefix("Proc-Type:")
                .is_some_and(|value| value.trim().eq_ignore_ascii_case("4,ENCRYPTED"))
        })
}

fn shallow_putty_is_encrypted(content: &[u8]) -> bool {
    content.split(|byte| *byte == b'\n').find_map(|line| {
        line.trim_ascii()
            .strip_prefix(b"Encryption:")
            .map(|value| !value.trim_ascii().eq_ignore_ascii_case(b"none"))
    }) == Some(true)
}

fn decode_openssh_armor(content: &str) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    const BEGIN: &str = "-----BEGIN OPENSSH PRIVATE KEY-----";
    const END: &str = "-----END OPENSSH PRIVATE KEY-----";

    let mut lines = content.lines().map(str::trim);
    if lines.next() != Some(BEGIN) {
        return Err(ImportError::UnsupportedFormat);
    }
    let mut encoded_length = 0_usize;
    let mut footer_seen = false;
    for line in lines.clone() {
        if line == END {
            footer_seen = true;
            break;
        }
        if line.starts_with("-----") {
            return Err(ImportError::MalformedKey);
        }
        encoded_length = encoded_length.saturating_add(line.len());
        if encoded_length > MAX_DECODED_KEY_BYTES * 2 {
            return Err(ImportError::ResourceLimit);
        }
    }
    if !footer_seen {
        return Err(ImportError::MalformedKey);
    }
    let encoded = ssh_keys::join_secret_lines(lines.take_while(|line| *line != END))
        .map_err(map_primitive_error)?;
    decode_bounded_base64_secret(&encoded)
}

fn shallow_openssh_is_encrypted(decoded: &[u8]) -> bool {
    const AUTH_MAGIC: &[u8] = b"openssh-key-v1\0";
    let Some(remainder) = decoded.strip_prefix(AUTH_MAGIC) else {
        return false;
    };
    let mut reader = WireReader::new(remainder);
    reader
        .read_string()
        .ok()
        .is_some_and(|cipher| cipher != b"none")
}

fn encode_outcome(outcome: ImportOutcome) -> Result<Vec<u8>, PrimitiveError> {
    let result = match outcome {
        ImportOutcome::Success(key_material) => {
            ssh_private_key_import_result::Result::Success(SshPrivateKeyImportSuccess {
                key_material: Some(key_material),
            })
        }
        ImportOutcome::NeedsPassphrase(format_label) => {
            ssh_private_key_import_result::Result::NeedsPassphrase(
                SshPrivateKeyImportNeedsPassphrase {
                    format_label: format_label.to_owned(),
                },
            )
        }
        ImportOutcome::Error(reason) => {
            ssh_private_key_import_result::Result::Error(SshPrivateKeyImportError {
                reason: reason as i32,
            })
        }
    };
    let encoded = SshPrivateKeyImportResult {
        result: Some(result),
    }
    .encode_to_vec();
    if encoded.len() > crate::MAX_CONTROL_ENVELOPE_BYTES {
        Err(PrimitiveError::ResourceLimit)
    } else {
        Ok(encoded)
    }
}

fn import_openssh(content: &str, passphrase: Option<&[u8]>) -> Result<ImportOutcome, ImportError> {
    let decoded = decode_openssh_armor(content)?;
    if passphrase.is_none() && shallow_openssh_is_encrypted(&decoded) {
        return Ok(ImportOutcome::NeedsPassphrase("OpenSSH"));
    }
    validate_openssh_container_algorithms(&decoded)?;
    let outer_public = parse_openssh_outer_public(&decoded)?;
    // The compatibility decoder accepts SSHJ's extended sequential padding,
    // so it cannot enforce the upstream outer/inner public-key check itself.
    // `normalize_openssh_key` performs that identity check immediately after
    // the private record is available.
    let private_key =
        PrivateKey::from_bytes_without_public_key_check(&decoded).map_err(map_ssh_parse_error)?;
    validate_openssh_kdf(private_key.kdf())?;
    if private_key.is_encrypted() {
        let passphrase = match passphrase {
            Some(passphrase) => passphrase,
            None => return Ok(ImportOutcome::NeedsPassphrase("OpenSSH")),
        };
        let decrypted = private_key
            .decrypt_without_public_key_check(passphrase)
            .map_err(map_ssh_decrypt_error)?;
        normalize_openssh_key(&decrypted, &outer_public).map(ImportOutcome::Success)
    } else {
        normalize_openssh_key(&private_key, &outer_public).map(ImportOutcome::Success)
    }
}

fn validate_openssh_container_algorithms(decoded: &[u8]) -> Result<(), ImportError> {
    const AUTH_MAGIC: &[u8] = b"openssh-key-v1\0";
    let mut reader = WireReader::new(
        decoded
            .strip_prefix(AUTH_MAGIC)
            .ok_or(ImportError::MalformedKey)?,
    );
    let cipher =
        std::str::from_utf8(reader.read_string()?).map_err(|_| ImportError::MalformedKey)?;
    let kdf = std::str::from_utf8(reader.read_string()?).map_err(|_| ImportError::MalformedKey)?;
    Cipher::new(cipher).map_err(|_| ImportError::MalformedKey)?;
    KdfAlg::new(kdf).map_err(|_| ImportError::MalformedKey)?;
    Ok(())
}

fn parse_openssh_outer_public(decoded: &[u8]) -> Result<Vec<u8>, ImportError> {
    const AUTH_MAGIC: &[u8] = b"openssh-key-v1\0";
    let mut reader = WireReader::new(
        decoded
            .strip_prefix(AUTH_MAGIC)
            .ok_or(ImportError::MalformedKey)?,
    );
    for _ in 0..3 {
        reader.read_string()?;
    }
    if reader.read_u32()? != 1 {
        return Err(ImportError::MalformedKey);
    }
    let encoded = reader.read_string()?;
    let public = SshPublicKey::from_bytes(encoded).map_err(map_ssh_parse_error)?;
    match public.key_data() {
        ssh_key::public::KeyData::Rsa(key) => {
            let modulus = positive_mpint(&key.n)?;
            let public_exponent = positive_mpint(&key.e)?;
            validate_rsa_public_components(&modulus, &public_exponent)?;
        }
        ssh_key::public::KeyData::Ed25519(_) => {}
        _ => return Err(ImportError::UnsupportedAlgorithm),
    };
    public.to_bytes().map_err(map_ssh_parse_error)
}

fn validate_openssh_kdf(kdf: &Kdf) -> Result<(), ImportError> {
    match kdf {
        Kdf::None => Ok(()),
        Kdf::Bcrypt { salt, rounds } => {
            if salt.is_empty() || *rounds == 0 {
                return Err(ImportError::MalformedKey);
            }
            if salt.len() > MAX_OPENSSH_BCRYPT_SALT_BYTES || *rounds > MAX_OPENSSH_BCRYPT_ROUNDS {
                return Err(ImportError::ResourceLimit);
            }
            Ok(())
        }
        #[allow(unreachable_patterns)]
        _ => Err(ImportError::UnsupportedAlgorithm),
    }
}

fn map_ssh_parse_error(error: SshKeyError) -> ImportError {
    match error {
        SshKeyError::AlgorithmUnknown | SshKeyError::AlgorithmUnsupported { .. } => {
            ImportError::UnsupportedAlgorithm
        }
        _ => ImportError::MalformedKey,
    }
}

fn map_ssh_decrypt_error(error: SshKeyError) -> ImportError {
    match error {
        SshKeyError::AlgorithmUnknown | SshKeyError::AlgorithmUnsupported { .. } => {
            ImportError::UnsupportedAlgorithm
        }
        SshKeyError::Crypto => ImportError::InvalidPassphrase,
        _ => ImportError::MalformedKey,
    }
}

fn normalize_openssh_key(
    private_key: &PrivateKey,
    outer_public: &[u8],
) -> Result<SshKeyMaterial, ImportError> {
    let material = match private_key.key_data() {
        KeypairData::Rsa(keypair) => rsa_material_from_openssh(keypair)?,
        KeypairData::Ed25519(keypair) => {
            let seed = Zeroizing::new(keypair.private.to_bytes());
            let material = ssh_keys::encode_ed25519_material(&seed, random_checkint()?)
                .map_err(map_primitive_error)?;
            if material.public_key
                != private_key
                    .public_key()
                    .to_bytes()
                    .map_err(|_| ImportError::MalformedKey)?
            {
                return Err(ImportError::MalformedKey);
            }
            material
        }
        KeypairData::Dsa(_)
        | KeypairData::Other(_)
        | KeypairData::SkEd25519(_)
        | KeypairData::Encrypted(_) => return Err(ImportError::UnsupportedAlgorithm),
        #[allow(unreachable_patterns)]
        _ => return Err(ImportError::UnsupportedAlgorithm),
    };
    if material.public_key != outer_public {
        return Err(ImportError::MalformedKey);
    }
    Ok(material)
}

/// Converts an OpenSSH-armored RSA keypair into canonical RSA PKCS#8 DER.
///
/// Used by [`ssh_keys::export_cxf`]: a synced RSA key is stored OpenSSH-armored
/// rather than as PKCS#1 or PKCS#8. The OpenSSH record carries `d`, `p`, `q`
/// and `iqmp`, so the CRT parameters can be completed before the PKCS#1 is
/// wrapped.
pub(crate) fn rsa_openssh_to_pkcs8(
    keypair: &RsaKeypair,
) -> Result<Zeroizing<Vec<u8>>, PrimitiveError> {
    let mut material = rsa_material_from_openssh(keypair).map_err(map_import_error)?;
    // Move the key out so it keeps zeroizing ownership; what stays behind in
    // the material is an empty vec and the public key.
    Ok(Zeroizing::new(std::mem::take(&mut material.private_key)))
}

const fn map_import_error(error: ImportError) -> PrimitiveError {
    match error {
        ImportError::ResourceLimit => PrimitiveError::ResourceLimit,
        ImportError::BackendFailure => PrimitiveError::CryptoFailure,
        ImportError::UnsupportedFormat
        | ImportError::UnsupportedAlgorithm
        | ImportError::InvalidPassphrase
        | ImportError::MalformedKey => PrimitiveError::InvalidArgument,
    }
}

fn rsa_material_from_openssh(keypair: &RsaKeypair) -> Result<SshKeyMaterial, ImportError> {
    let modulus = positive_mpint(&keypair.public.n)?;
    let public_exponent = positive_mpint(&keypair.public.e)?;
    let mut private_exponent = sensitive_positive_mpint(&keypair.private.d)?;
    let mut prime_p = sensitive_positive_mpint(&keypair.private.p)?;
    let mut prime_q = sensitive_positive_mpint(&keypair.private.q)?;
    let mut coefficient = sensitive_positive_mpint(&keypair.private.iqmp)?;
    let components = RsaPrivateComponents::new(
        modulus,
        public_exponent,
        std::mem::take(&mut *private_exponent),
        None,
    );
    let primes = RsaPrimeComponents::new(
        std::mem::take(&mut *prime_p),
        std::mem::take(&mut *prime_q),
        std::mem::take(&mut *coefficient),
    );
    let pkcs1 =
        complete_rsa_pkcs1_der(&components, &primes).map_err(|_| ImportError::MalformedKey)?;
    normalize_rsa_pkcs1(&pkcs1)
}

fn sensitive_positive_mpint(value: &ssh_key::Mpint) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    positive_mpint(value).map(Zeroizing::new)
}

fn positive_mpint(value: &ssh_key::Mpint) -> Result<Vec<u8>, ImportError> {
    value
        .as_positive_bytes()
        .filter(|bytes| !bytes.is_empty())
        .map(ToOwned::to_owned)
        .ok_or(ImportError::MalformedKey)
}

fn import_pem(content: &str, passphrase: Option<&[u8]>) -> Result<ImportOutcome, ImportError> {
    let document = PemDocument::parse(content)?;
    let is_legacy_encrypted = document
        .headers
        .get("Proc-Type")
        .is_some_and(|value| value.eq_ignore_ascii_case("4,ENCRYPTED"));
    let is_encrypted_pkcs8 = document.label == "ENCRYPTED PRIVATE KEY";
    if (is_legacy_encrypted || is_encrypted_pkcs8) && passphrase.is_none() {
        return Ok(ImportOutcome::NeedsPassphrase("PEM"));
    }

    match document.label {
        "RSA PRIVATE KEY" if is_legacy_encrypted => {
            let passphrase = passphrase.ok_or(ImportError::InvalidPassphrase)?;
            let decrypted = document.decrypt_legacy(passphrase)?;
            normalize_rsa_pkcs1(&decrypted).map(ImportOutcome::Success)
        }
        "RSA PRIVATE KEY" => normalize_rsa_pkcs1(&document.body).map(ImportOutcome::Success),
        "PRIVATE KEY" => normalize_pkcs8(&document.body).map(ImportOutcome::Success),
        "ENCRYPTED PRIVATE KEY" => {
            let passphrase = passphrase.ok_or(ImportError::InvalidPassphrase)?;
            let decrypted = decrypt_jdk21_encrypted_pkcs8(&document.body, passphrase)
                .map_err(map_pbes2_error)?;
            normalize_pkcs8(&decrypted).map(ImportOutcome::Success)
        }
        "DSA PRIVATE KEY" | "EC PRIVATE KEY" => {
            if is_legacy_encrypted {
                let passphrase = passphrase.ok_or(ImportError::InvalidPassphrase)?;
                let _decrypted = document.decrypt_legacy(passphrase)?;
            }
            Ok(ImportOutcome::Error(
                SshPrivateKeyImportErrorReason::UnsupportedAlgorithm,
            ))
        }
        _ => Err(ImportError::UnsupportedFormat),
    }
}

struct PemDocument<'a> {
    label: &'a str,
    headers: HashMap<&'a str, &'a str>,
    body: Zeroizing<Vec<u8>>,
}

impl<'a> PemDocument<'a> {
    fn parse(content: &'a str) -> Result<Self, ImportError> {
        let mut lines = content.lines();
        let begin = lines.next().ok_or(ImportError::MalformedKey)?.trim();
        let label = begin
            .strip_prefix("-----BEGIN ")
            .and_then(|value| value.strip_suffix("-----"))
            .filter(|value| !value.is_empty())
            .ok_or(ImportError::UnsupportedFormat)?;
        let end = format!("-----END {label}-----");
        let mut lines = lines.map(str::trim);
        let mut headers = HashMap::new();
        // Headers run until the first blank or non-header line.
        while let Some((name, value)) = lines
            .clone()
            .next()
            .filter(|line| *line != end)
            .and_then(|line| line.split_once(':'))
        {
            let name = name.trim();
            let value = value.trim();
            if name.is_empty() || headers.insert(name, value).is_some() {
                return Err(ImportError::MalformedKey);
            }
            lines.next();
        }
        let mut encoded_length = 0_usize;
        let mut footer_seen = false;
        for line in lines.clone() {
            if line == end {
                footer_seen = true;
                // SSHJ's StandardPEMKeyReader consumes the first matching
                // private-key block and leaves any following certificate or
                // key blocks unread.
                break;
            }
            encoded_length = encoded_length.saturating_add(line.len());
            if encoded_length > MAX_DECODED_KEY_BYTES * 2 {
                return Err(ImportError::ResourceLimit);
            }
        }
        if !footer_seen || encoded_length == 0 {
            return Err(ImportError::MalformedKey);
        }
        let encoded = ssh_keys::join_secret_lines(lines.take_while(|line| *line != end))
            .map_err(map_primitive_error)?;
        let body = decode_bounded_base64_secret(&encoded)?;
        Ok(Self {
            label,
            headers,
            body,
        })
    }

    fn decrypt_legacy(&self, passphrase: &[u8]) -> Result<Zeroizing<Vec<u8>>, ImportError> {
        let (cipher_name, iv_hex) = self
            .headers
            .get("DEK-Info")
            .and_then(|value| value.split_once(','))
            .ok_or(ImportError::MalformedKey)?;
        let iv = Zeroizing::new(decode_hex(iv_hex.trim())?);
        decrypt_legacy_openssl_pem(cipher_name.trim(), passphrase, &iv, &self.body)
            .map_err(map_legacy_pem_error)
    }
}

fn map_legacy_pem_error(error: LegacyPemError) -> ImportError {
    match error {
        LegacyPemError::UnsupportedAlgorithm => ImportError::UnsupportedAlgorithm,
        LegacyPemError::Malformed => ImportError::MalformedKey,
        LegacyPemError::InvalidPassphrase => ImportError::InvalidPassphrase,
        LegacyPemError::ResourceLimit => ImportError::ResourceLimit,
        LegacyPemError::Backend => ImportError::BackendFailure,
    }
}

fn map_pbes2_error(error: Pbes2Error) -> ImportError {
    match error {
        Pbes2Error::UnsupportedAlgorithm => ImportError::UnsupportedAlgorithm,
        Pbes2Error::Malformed => ImportError::MalformedKey,
        Pbes2Error::InvalidPassphrase => ImportError::InvalidPassphrase,
        Pbes2Error::ResourceLimit => ImportError::ResourceLimit,
        Pbes2Error::Backend => ImportError::BackendFailure,
    }
}

fn normalize_pkcs8(pkcs8: &[u8]) -> Result<SshKeyMaterial, ImportError> {
    let info = PrivateKeyInfo::from_der(pkcs8).map_err(|_| ImportError::MalformedKey)?;
    if info.algorithm.oid == RSA_ENCRYPTION_OID {
        let private =
            RsaPrivateKey::from_der(info.private_key).map_err(|_| ImportError::MalformedKey)?;
        let canonical_pkcs8 =
            canonical_rsa_pkcs8(&private, info.algorithm.parameters.or(Some(AnyRef::NULL)))?;
        return rsa_material_from_parsed(&private, &canonical_pkcs8);
    }
    if info.algorithm.oid == ED25519_OID {
        if info.algorithm.parameters.is_some() {
            return Err(ImportError::MalformedKey);
        }
        let seed =
            OctetStringRef::from_der(info.private_key).map_err(|_| ImportError::MalformedKey)?;
        let seed = Zeroizing::new(
            seed.as_bytes()
                .try_into()
                .map_err(|_| ImportError::MalformedKey)?,
        );
        let material = ssh_keys::encode_ed25519_material(&seed, random_checkint()?)
            .map_err(map_primitive_error)?;
        if info
            .public_key
            .is_some_and(|public| public != material_public_ed25519(&material).unwrap_or_default())
        {
            return Err(ImportError::MalformedKey);
        }
        return Ok(material);
    }
    Err(ImportError::UnsupportedAlgorithm)
}

fn normalize_rsa_pkcs1(pkcs1: &[u8]) -> Result<SshKeyMaterial, ImportError> {
    let private = RsaPrivateKey::from_der(pkcs1).map_err(|_| ImportError::MalformedKey)?;
    let pkcs8 = canonical_rsa_pkcs8(&private, Some(AnyRef::NULL))?;
    rsa_material_from_parsed(&private, &pkcs8)
}

fn canonical_rsa_pkcs8(
    private: &RsaPrivateKey<'_>,
    parameters: Option<AnyRef<'_>>,
) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    let pkcs1 = private
        .to_der()
        .map(Zeroizing::new)
        .map_err(|_| ImportError::BackendFailure)?;
    wrap_rsa_pkcs8_with_parameters(&pkcs1, parameters)
}

fn rsa_material_from_parsed(
    private: &RsaPrivateKey<'_>,
    private_pkcs8: &[u8],
) -> Result<SshKeyMaterial, ImportError> {
    if private.other_prime_infos.is_some() {
        return Err(ImportError::UnsupportedAlgorithm);
    }
    let modulus = validate_rsa_component(private.modulus.as_bytes())?;
    let public_exponent = validate_rsa_component(private.public_exponent.as_bytes())?;
    let private_exponent = validate_rsa_component(private.private_exponent.as_bytes())?;
    validate_rsa_triplet(modulus, public_exponent, private_exponent)?;
    let public_key =
        ssh_keys::encode_rsa_public(modulus, public_exponent).map_err(map_primitive_error)?;
    Ok(SshKeyMaterial {
        r#type: SshKeyType::Rsa as i32,
        private_key: private_pkcs8.to_vec(),
        public_key,
    })
}

fn rsa_material_from_components(
    modulus: &[u8],
    public_exponent: &[u8],
    private_exponent: &[u8],
) -> Result<SshKeyMaterial, ImportError> {
    let modulus = validate_rsa_component(modulus)?;
    let public_exponent = validate_rsa_component(public_exponent)?;
    let private_exponent = validate_rsa_component(private_exponent)?;
    validate_rsa_triplet(modulus, public_exponent, private_exponent)?;
    let private_key = incomplete_rsa_pkcs8(modulus, public_exponent, private_exponent)?;
    let public_key =
        ssh_keys::encode_rsa_public(modulus, public_exponent).map_err(map_primitive_error)?;
    Ok(SshKeyMaterial {
        r#type: SshKeyType::Rsa as i32,
        private_key: private_key.to_vec(),
        public_key,
    })
}

fn validate_rsa_component(value: &[u8]) -> Result<&[u8], ImportError> {
    let value = strip_leading_zeroes(value);
    if value.is_empty() || value.len() > MAX_RSA_COMPONENT_BYTES {
        Err(ImportError::MalformedKey)
    } else {
        Ok(value)
    }
}

fn validate_rsa_triplet(
    modulus: &[u8],
    public_exponent: &[u8],
    private_exponent: &[u8],
) -> Result<(), ImportError> {
    validate_rsa_public_components(modulus, public_exponent)?;
    if private_exponent.len() > modulus.len() {
        Err(ImportError::MalformedKey)
    } else {
        Ok(())
    }
}

fn validate_rsa_public_components(
    modulus: &[u8],
    public_exponent: &[u8],
) -> Result<(), ImportError> {
    ssh_keys::validate_rsa_public_components(modulus, public_exponent).map_err(map_primitive_error)
}

fn wrap_rsa_pkcs8(pkcs1: &[u8]) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    wrap_rsa_pkcs8_with_parameters(pkcs1, Some(AnyRef::NULL))
}

fn wrap_rsa_pkcs8_with_parameters(
    pkcs1: &[u8],
    parameters: Option<AnyRef<'_>>,
) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    PrivateKeyInfo::new(
        AlgorithmIdentifierRef {
            oid: RSA_ENCRYPTION_OID,
            parameters,
        },
        pkcs1,
    )
    .to_der()
    .map(Zeroizing::new)
    .map_err(|_| ImportError::BackendFailure)
}

fn incomplete_rsa_pkcs8(
    modulus: &[u8],
    public_exponent: &[u8],
    private_exponent: &[u8],
) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    let zero_bytes = [0_u8];
    let zero = UintRef::new(&zero_bytes).map_err(|_| ImportError::MalformedKey)?;
    let modulus = UintRef::new(modulus).map_err(|_| ImportError::MalformedKey)?;
    let public_exponent = UintRef::new(public_exponent).map_err(|_| ImportError::MalformedKey)?;
    let private_exponent = UintRef::new(private_exponent).map_err(|_| ImportError::MalformedKey)?;
    let pkcs1 = Zeroizing::new(
        RsaPrivateKey {
            modulus,
            public_exponent,
            private_exponent,
            prime1: zero,
            prime2: zero,
            exponent1: zero,
            exponent2: zero,
            coefficient: zero,
            other_prime_infos: None,
        }
        .to_der()
        .map_err(|_| ImportError::BackendFailure)?,
    );
    wrap_rsa_pkcs8(&pkcs1)
}

fn material_public_ed25519(material: &SshKeyMaterial) -> Option<&[u8]> {
    // SSH wire value is: uint32 algorithm length, algorithm, uint32 key
    // length, then the 32-byte public key.
    material
        .public_key
        .get(material.public_key.len().checked_sub(32)?..)
}

fn strip_leading_zeroes(mut value: &[u8]) -> &[u8] {
    while value.first() == Some(&0) {
        value = &value[1..];
    }
    value
}

fn random_checkint() -> Result<u32, ImportError> {
    ssh_keys::random_u32().map_err(map_primitive_error)
}

fn map_primitive_error(error: PrimitiveError) -> ImportError {
    match error {
        PrimitiveError::InvalidArgument | PrimitiveError::AuthenticationFailed => {
            ImportError::MalformedKey
        }
        PrimitiveError::ResourceLimit => ImportError::ResourceLimit,
        PrimitiveError::CryptoFailure
        | PrimitiveError::Internal
        | PrimitiveError::UnsupportedKeyVersion
        | PrimitiveError::NoUsableKey
        | PrimitiveError::Panic => ImportError::BackendFailure,
    }
}

fn sensitive_error(_error: SensitiveBackendError) -> ImportError {
    ImportError::BackendFailure
}

fn decode_hex(value: &str) -> Result<Vec<u8>, ImportError> {
    if value.is_empty() || !value.len().is_multiple_of(2) {
        return Err(ImportError::MalformedKey);
    }
    if value.len() > MAX_DECODED_KEY_BYTES * 2 {
        return Err(ImportError::ResourceLimit);
    }
    value
        .as_bytes()
        .as_chunks::<2>()
        .0
        .iter()
        .map(|pair| {
            let high = hex_digit(pair[0]).ok_or(ImportError::MalformedKey)?;
            let low = hex_digit(pair[1]).ok_or(ImportError::MalformedKey)?;
            Ok((high << 4) | low)
        })
        .collect()
}

const fn hex_digit(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

// PuTTY parsing follows below. Keeping it separate from PEM/OpenSSH parsing
// prevents legacy container quirks from weakening the strict DER paths.
fn import_putty(content: &[u8], passphrase: Option<&[u8]>) -> Result<ImportOutcome, ImportError> {
    let document = PuttyDocument::parse(content)?;
    if document.encryption != "none" && document.encryption != "aes256-cbc" {
        return Err(ImportError::MalformedKey);
    }
    if document.encryption == "aes256-cbc" && passphrase.is_none() {
        return Ok(ImportOutcome::NeedsPassphrase("PuTTY"));
    }
    if !matches!(document.algorithm, "ssh-rsa" | "ssh-ed25519") {
        return Err(ImportError::UnsupportedAlgorithm);
    }

    let public_key = document.decode_public()?;
    let PuttyPrivatePayload {
        private_key,
        mac_key,
    } = document.private_payload(passphrase)?;
    let integrity = document.verify_mac(&mac_key, &public_key, &private_key)?;

    let material = match document.algorithm {
        "ssh-rsa" => normalize_putty_rsa(&public_key, &private_key, integrity)?,
        "ssh-ed25519" if integrity == PuttyIntegrity::WholeFile => {
            normalize_putty_ed25519(&public_key, &private_key)?
        }
        _ => return Err(ImportError::UnsupportedAlgorithm),
    };
    Ok(ImportOutcome::Success(material))
}

struct PuttyDocument<'a> {
    version: u8,
    algorithm: &'a str,
    encryption: &'a str,
    comment: &'a [u8],
    headers: HashMap<&'a str, &'a str>,
    public_base64: String,
    private_base64: Zeroizing<String>,
}

impl<'a> PuttyDocument<'a> {
    fn parse(content: &'a [u8]) -> Result<Self, ImportError> {
        let lines: Vec<&[u8]> = content
            .split(|byte| *byte == b'\n')
            .map(|line| line.strip_suffix(b"\r").unwrap_or(line))
            .collect();
        let first = lines
            .first()
            .and_then(|line| std::str::from_utf8(line).ok())
            .map(str::trim)
            .ok_or(ImportError::MalformedKey)?;
        let (header, algorithm) = first.split_once(": ").ok_or(ImportError::MalformedKey)?;
        let version = header
            .strip_prefix("PuTTY-User-Key-File-")
            .and_then(|value| value.parse::<u8>().ok())
            .filter(|value| (1..=3).contains(value))
            .ok_or(ImportError::UnsupportedFormat)?;
        let mut headers = HashMap::new();
        let mut public_base64 = String::new();
        let mut private_lines: Vec<&str> = Vec::new();
        let mut comment = None;
        let mut index = 1;
        while index < lines.len() {
            let raw_line = lines[index];
            index += 1;
            // Only the comment is opaque bytes. Structural headers and Base64
            // remain text, with the existing whitespace compatibility.
            if let Some(value) = raw_line.trim_ascii_start().strip_prefix(b"Comment: ") {
                if comment.replace(value).is_some() {
                    return Err(ImportError::MalformedKey);
                }
                continue;
            }
            let line = std::str::from_utf8(raw_line)
                .map_err(|_| ImportError::MalformedKey)?
                .trim();
            if line.is_empty() {
                continue;
            }
            let (name, value) = line.split_once(": ").ok_or(ImportError::MalformedKey)?;
            if headers.insert(name, value).is_some() {
                return Err(ImportError::MalformedKey);
            }
            if matches!(name, "Public-Lines" | "Private-Lines") {
                let count = value
                    .parse::<usize>()
                    .ok()
                    .filter(|count| *count <= MAX_PUTTY_PAYLOAD_LINES)
                    .ok_or(ImportError::ResourceLimit)?;
                if index.checked_add(count).is_none_or(|end| end > lines.len()) {
                    return Err(ImportError::MalformedKey);
                }
                let payload = lines[index..index + count]
                    .iter()
                    .map(|line| std::str::from_utf8(line).map_err(|_| ImportError::MalformedKey))
                    .collect::<Result<Vec<_>, _>>()?;
                let payload_length = payload
                    .iter()
                    .map(|line| line.trim().len())
                    .fold(0_usize, usize::saturating_add);
                if payload_length > MAX_DECODED_KEY_BYTES * 2 {
                    return Err(ImportError::ResourceLimit);
                }
                if name == "Public-Lines" {
                    public_base64 = payload.iter().map(|line| line.trim()).collect();
                } else {
                    private_lines = payload;
                }
                index += count;
            }
        }
        let encryption = *headers.get("Encryption").ok_or(ImportError::MalformedKey)?;
        let comment = comment.unwrap_or_default();
        let private_base64 =
            ssh_keys::join_secret_lines(private_lines.iter().map(|line| line.trim()))
                .map_err(map_primitive_error)?;
        if public_base64.is_empty() || private_base64.is_empty() {
            return Err(ImportError::MalformedKey);
        }
        Ok(Self {
            version,
            algorithm,
            encryption,
            comment,
            headers,
            public_base64,
            private_base64,
        })
    }

    fn decode_public(&self) -> Result<Vec<u8>, ImportError> {
        decode_bounded_base64(&self.public_base64)
    }

    fn decode_private(&self) -> Result<Zeroizing<Vec<u8>>, ImportError> {
        decode_bounded_base64_secret(&self.private_base64)
    }

    // Keep decryption and MAC-key selection together so callers cannot use a
    // supplied passphrase or run Argon2 for an unencrypted document.
    fn private_payload(
        &self,
        passphrase: Option<&[u8]>,
    ) -> Result<PuttyPrivatePayload, ImportError> {
        let mut private_key = self.decode_private()?;
        let mac_key = if self.encryption == "aes256-cbc" {
            let derived =
                self.derive_encryption(passphrase.ok_or(ImportError::InvalidPassphrase)?)?;
            if private_key.is_empty() || !private_key.len().is_multiple_of(16) {
                return Err(ImportError::MalformedKey);
            }
            Cipher::Aes256Cbc
                .decrypt(&derived.key, &derived.iv, &mut private_key, None)
                .map_err(|_| ImportError::InvalidPassphrase)?;
            derived.mac_key
        } else if self.version <= 2 {
            sensitive_digest(DigestAlgorithm::Sha1, &[PUTTY_MAC_PREFIX])?
        } else {
            // PPK v3 with Encryption: none uses an empty MAC key and no Argon2.
            Zeroizing::new(Vec::new())
        };
        Ok(PuttyPrivatePayload {
            private_key,
            mac_key,
        })
    }

    fn derive_encryption(&self, passphrase: &[u8]) -> Result<PuttyDerived, ImportError> {
        if self.version <= 2 {
            let key = putty_v2_encryption_key(passphrase)?;
            let mac_key = sensitive_digest(DigestAlgorithm::Sha1, &[PUTTY_MAC_PREFIX, passphrase])?;
            return Ok(PuttyDerived {
                key,
                iv: Zeroizing::new(vec![0_u8; 16]),
                mac_key,
            });
        }
        let mode = match self.headers.get("Key-Derivation").copied() {
            Some(value) if value.eq_ignore_ascii_case("Argon2d") => Argon2Mode::D,
            Some(value) if value.eq_ignore_ascii_case("Argon2i") => Argon2Mode::I,
            Some(value) if value.eq_ignore_ascii_case("Argon2id") => Argon2Mode::Id,
            Some(_) => return Err(ImportError::UnsupportedAlgorithm),
            None => return Err(ImportError::MalformedKey),
        };
        let iterations = self.parse_u32_header("Argon2-Passes")?;
        let memory_kib = self.parse_u32_header("Argon2-Memory")?;
        let parallelism = self.parse_u32_header("Argon2-Parallelism")?;
        let salt = decode_hex(
            self.headers
                .get("Argon2-Salt")
                .copied()
                .ok_or(ImportError::MalformedKey)?,
        )?;
        validate_putty_argon2_bounds(iterations, memory_kib, parallelism, salt.len())?;
        let derived = Zeroizing::new(
            primitives::argon2(
                mode,
                argon2::Version::V0x13,
                passphrase.to_vec(),
                salt,
                None,
                None,
                iterations,
                memory_kib,
                parallelism,
                80,
            )
            .map_err(map_primitive_error)?,
        );
        Ok(PuttyDerived {
            key: Zeroizing::new(derived[..32].to_vec()),
            iv: Zeroizing::new(derived[32..48].to_vec()),
            mac_key: Zeroizing::new(derived[48..80].to_vec()),
        })
    }

    fn parse_u32_header(&self, name: &str) -> Result<u32, ImportError> {
        self.headers
            .get(name)
            .and_then(|value| value.parse::<u32>().ok())
            .filter(|value| *value > 0)
            .ok_or(ImportError::MalformedKey)
    }

    fn verify_mac(
        &self,
        mac_key: &[u8],
        public_key: &[u8],
        private_key: &[u8],
    ) -> Result<PuttyIntegrity, ImportError> {
        let integrity_error = if self.encryption == "none" {
            ImportError::MalformedKey
        } else {
            ImportError::InvalidPassphrase
        };
        if let Some(expected) = self.headers.get("Private-Hash") {
            if self.version != 1 || self.headers.contains_key("Private-MAC") {
                return Err(integrity_error);
            }
            let actual = sensitive_digest(DigestAlgorithm::Sha1, &[private_key])?;
            return if putty_mac_matches_lower_hex(expected.as_bytes(), &actual) {
                Ok(PuttyIntegrity::PrivateOnly)
            } else {
                Err(integrity_error)
            };
        }
        let expected = self
            .headers
            .get("Private-MAC")
            .copied()
            .ok_or(integrity_error)?;
        let actual = putty_mac(
            self.mac_algorithm(),
            mac_key,
            self.algorithm,
            self.encryption,
            self.comment,
            public_key,
            private_key,
        )?;
        if putty_mac_matches_lower_hex(expected.as_bytes(), &actual) {
            return Ok(PuttyIntegrity::WholeFile);
        }
        if self.version == 1 {
            // Genuine v1 authenticates only the private blob, with no string
            // wrapper. Also retain the full-field v2 MAC alias checked above.
            let actual = sensitive_hmac(DigestAlgorithm::Sha1, mac_key, &[private_key])?;
            if putty_mac_matches_lower_hex(expected.as_bytes(), &actual) {
                return Ok(PuttyIntegrity::PrivateOnly);
            }
        }
        Err(integrity_error)
    }

    const fn mac_algorithm(&self) -> DigestAlgorithm {
        if self.version <= 2 {
            DigestAlgorithm::Sha1
        } else {
            DigestAlgorithm::Sha256
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum PuttyIntegrity {
    WholeFile,
    PrivateOnly,
}

fn putty_mac_matches_lower_hex(expected: &[u8], actual: &[u8]) -> bool {
    const HEX: &[u8; 16] = b"0123456789abcdef";

    if expected.len() != actual.len() * 2 {
        return false;
    }
    let mut difference = 0_u8;
    for (index, byte) in actual.iter().copied().enumerate() {
        difference |= expected[index * 2] ^ HEX[usize::from(byte >> 4)];
        difference |= expected[index * 2 + 1] ^ HEX[usize::from(byte & 0x0f)];
    }
    difference == 0
}

fn validate_putty_argon2_bounds(
    passes: u32,
    memory_kib: u32,
    parallelism: u32,
    salt_bytes: usize,
) -> Result<(), ImportError> {
    if passes == 0 || memory_kib == 0 || parallelism == 0 || salt_bytes == 0 {
        return Err(ImportError::MalformedKey);
    }
    let work = u64::from(passes) * u64::from(memory_kib);
    if passes > MAX_PUTTY_ARGON2_PASSES
        || memory_kib > MAX_PUTTY_ARGON2_MEMORY_KIB
        || parallelism > MAX_PUTTY_ARGON2_PARALLELISM
        || salt_bytes > MAX_PUTTY_ARGON2_SALT_BYTES
        || work > MAX_PUTTY_ARGON2_WORK_KIB_PASSES
    {
        return Err(ImportError::ResourceLimit);
    }
    Ok(())
}

struct PuttyPrivatePayload {
    private_key: Zeroizing<Vec<u8>>,
    mac_key: Zeroizing<Vec<u8>>,
}

struct PuttyDerived {
    key: Zeroizing<Vec<u8>>,
    iv: Zeroizing<Vec<u8>>,
    mac_key: Zeroizing<Vec<u8>>,
}

fn putty_v2_encryption_key(passphrase: &[u8]) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    let first = sensitive_digest(DigestAlgorithm::Sha1, &[&0_u32.to_be_bytes(), passphrase])?;
    let second = sensitive_digest(DigestAlgorithm::Sha1, &[&1_u32.to_be_bytes(), passphrase])?;
    let mut key = Zeroizing::new(Vec::with_capacity(32));
    key.extend_from_slice(&first);
    key.extend_from_slice(&second[..12]);
    Ok(key)
}

fn putty_mac(
    algorithm: DigestAlgorithm,
    key: &[u8],
    key_type: &str,
    encryption: &str,
    comment: &[u8],
    public_key: &[u8],
    private_key: &[u8],
) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    let mut context = HmacContext::new(algorithm, key).map_err(sensitive_error)?;
    for value in [
        key_type.as_bytes(),
        encryption.as_bytes(),
        comment,
        public_key,
        private_key,
    ] {
        putty_mac_update(&mut context, value)?;
    }
    let mut output = Zeroizing::new(vec![0_u8; algorithm.output_size()]);
    context
        .finalize_into(&mut output)
        .map_err(sensitive_error)?;
    Ok(output)
}

fn putty_mac_update(context: &mut HmacContext, value: &[u8]) -> Result<(), ImportError> {
    let length = u32::try_from(value.len())
        .map_err(|_| ImportError::ResourceLimit)?
        .to_be_bytes();
    context.update(&length).map_err(sensitive_error)?;
    context.update(value).map_err(sensitive_error)
}

fn sensitive_digest(
    algorithm: DigestAlgorithm,
    parts: &[&[u8]],
) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    let mut context = DigestContext::new(algorithm).map_err(sensitive_error)?;
    for part in parts {
        context.update(part).map_err(sensitive_error)?;
    }
    let mut output = Zeroizing::new(vec![0_u8; algorithm.output_size()]);
    context
        .finalize_into(&mut output)
        .map_err(sensitive_error)?;
    Ok(output)
}

fn sensitive_hmac(
    algorithm: DigestAlgorithm,
    key: &[u8],
    parts: &[&[u8]],
) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    let mut context = HmacContext::new(algorithm, key).map_err(sensitive_error)?;
    for part in parts {
        context.update(part).map_err(sensitive_error)?;
    }
    let mut output = Zeroizing::new(vec![0_u8; algorithm.output_size()]);
    context
        .finalize_into(&mut output)
        .map_err(sensitive_error)?;
    Ok(output)
}

fn normalize_putty_rsa(
    public_key: &[u8],
    private_key: &[u8],
    integrity: PuttyIntegrity,
) -> Result<SshKeyMaterial, ImportError> {
    let mut public = WireReader::new(public_key);
    if public.read_string()? != b"ssh-rsa" {
        return Err(ImportError::MalformedKey);
    }
    let public_exponent = public.read_positive_mpint()?;
    let modulus = public.read_positive_mpint()?;
    public.finish_zero_padding()?;

    let mut private = WireReader::new(private_key);
    let private_exponent = private.read_positive_mpint()?;
    if integrity == PuttyIntegrity::PrivateOnly {
        // V1 leaves the public blob unauthenticated. Validate its relationship
        // to all private components before returning a key (PuTTY Appendix C.5.2).
        let prime_p = private.read_positive_mpint()?;
        let prime_q = private.read_positive_mpint()?;
        let coefficient = private.read_positive_mpint()?;
        let components = RsaPrivateComponents::new(
            modulus.to_vec(),
            public_exponent.to_vec(),
            private_exponent.to_vec(),
            None,
        );
        let primes =
            RsaPrimeComponents::new(prime_p.to_vec(), prime_q.to_vec(), coefficient.to_vec());
        complete_rsa_pkcs1_der(&components, &primes).map_err(|_| ImportError::MalformedKey)?;
    }
    // SSHJ deliberately imports only d and ignores optional p/q/iqmp fields.
    // Permit those fields and AES block padding while retaining n/e/d output.
    rsa_material_from_components(modulus, public_exponent, private_exponent)
}

fn normalize_putty_ed25519(
    public_key: &[u8],
    private_key: &[u8],
) -> Result<SshKeyMaterial, ImportError> {
    let mut public = WireReader::new(public_key);
    if public.read_string()? != b"ssh-ed25519" {
        return Err(ImportError::MalformedKey);
    }
    let expected_public = public.read_string()?;
    if expected_public.len() != 32 {
        return Err(ImportError::MalformedKey);
    }
    public.finish_zero_padding()?;

    let mut private = WireReader::new(private_key);
    let seed = Zeroizing::new(
        private
            .read_string()?
            .try_into()
            .map_err(|_| ImportError::MalformedKey)?,
    );
    // SSHJ reads exactly the length-prefixed seed and ignores the remaining
    // AES block padding. PuTTY v2 fixtures use non-zero padding bytes.
    let material = ssh_keys::encode_ed25519_material(&seed, random_checkint()?)
        .map_err(map_primitive_error)?;
    if material_public_ed25519(&material) != Some(expected_public) {
        return Err(ImportError::MalformedKey);
    }
    Ok(material)
}

struct WireReader<'a> {
    input: &'a [u8],
    offset: usize,
}

impl<'a> WireReader<'a> {
    const fn new(input: &'a [u8]) -> Self {
        Self { input, offset: 0 }
    }

    fn read_string(&mut self) -> Result<&'a [u8], ImportError> {
        let length_bytes = self.read_exact(4)?;
        let length = usize::try_from(u32::from_be_bytes(
            length_bytes
                .try_into()
                .map_err(|_| ImportError::MalformedKey)?,
        ))
        .map_err(|_| ImportError::ResourceLimit)?;
        if length > MAX_DECODED_KEY_BYTES {
            return Err(ImportError::ResourceLimit);
        }
        self.read_exact(length)
    }

    fn read_u32(&mut self) -> Result<u32, ImportError> {
        self.read_exact(4)?
            .try_into()
            .map(u32::from_be_bytes)
            .map_err(|_| ImportError::MalformedKey)
    }

    fn read_positive_mpint(&mut self) -> Result<&'a [u8], ImportError> {
        let value = self.read_string()?;
        if value.is_empty() || value.first().is_some_and(|byte| byte & 0x80 != 0) {
            return Err(ImportError::MalformedKey);
        }
        validate_rsa_component(value)
    }

    fn read_exact(&mut self, length: usize) -> Result<&'a [u8], ImportError> {
        let end = self
            .offset
            .checked_add(length)
            .filter(|end| *end <= self.input.len())
            .ok_or(ImportError::MalformedKey)?;
        let value = &self.input[self.offset..end];
        self.offset = end;
        Ok(value)
    }

    fn finish_zero_padding(&self) -> Result<(), ImportError> {
        self.input[self.offset..]
            .iter()
            .all(|byte| *byte == 0)
            .then_some(())
            .ok_or(ImportError::MalformedKey)
    }
}

fn decode_bounded_base64(value: &str) -> Result<Vec<u8>, ImportError> {
    if value.len() > MAX_DECODED_KEY_BYTES * 2 {
        return Err(ImportError::ResourceLimit);
    }
    let decoded = Base64::decode_vec(value).map_err(|_| ImportError::MalformedKey)?;
    if decoded.is_empty() {
        Err(ImportError::MalformedKey)
    } else if decoded.len() > MAX_DECODED_KEY_BYTES {
        Err(ImportError::ResourceLimit)
    } else {
        Ok(decoded)
    }
}

fn decode_bounded_base64_secret(value: &str) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    if value.is_empty() {
        return Err(ImportError::MalformedKey);
    }
    if value.len() > MAX_DECODED_KEY_BYTES * 2 {
        return Err(ImportError::ResourceLimit);
    }
    let padding = value
        .as_bytes()
        .iter()
        .rev()
        .take_while(|byte| **byte == b'=')
        .count();
    if padding > 2 {
        return Err(ImportError::MalformedKey);
    }
    let unpadded_length = value
        .len()
        .checked_sub(padding)
        .ok_or(ImportError::MalformedKey)?;
    let full_quads = unpadded_length / 4;
    let remainder = unpadded_length % 4;
    let decoded_capacity = full_quads
        .checked_mul(3)
        .and_then(|length| length.checked_add((remainder * 3) / 4))
        .ok_or(ImportError::ResourceLimit)?;
    if decoded_capacity == 0 {
        return Err(ImportError::MalformedKey);
    }
    if decoded_capacity > MAX_DECODED_KEY_BYTES {
        return Err(ImportError::ResourceLimit);
    }

    let mut decoded = Zeroizing::new(vec![0_u8; decoded_capacity]);
    let length = Base64::decode(value, &mut decoded)
        .map(|value| value.len())
        .map_err(|_| ImportError::MalformedKey)?;
    decoded.truncate(length);
    if decoded.is_empty() {
        Err(ImportError::MalformedKey)
    } else {
        Ok(decoded)
    }
}

#[cfg(test)]
mod tests {
    use keyguard_crypto_sensitive::generate_rsa_pkcs1_der;
    use ssh_key::{
        LineEnding, Mpint,
        private::{KeypairData, RsaKeypair, RsaPrivateKey as SshRsaPrivateKey},
        public::RsaPublicKey,
    };

    use super::*;
    use crate::protocol::{SshKeyDescription, SshKeyExportCxfResult};

    #[test]
    fn secret_buffer_import_collectors_accept_narrow_lines() {
        let private_key = vec![0x5a; 4096];
        let encoded = Base64::encode_string(&private_key);
        for width in [1, 2, 64, 70, 76, encoded.len()] {
            let mut body = String::new();
            for line in encoded.as_bytes().chunks(width) {
                body.push_str(std::str::from_utf8(line).expect("base64 ASCII"));
                body.push('\n');
            }
            let openssh = format!(
                "-----BEGIN OPENSSH PRIVATE KEY-----\n{body}-----END OPENSSH PRIVATE KEY-----\n"
            );
            assert_eq!(
                *decode_openssh_armor(&openssh).expect("OpenSSH armor"),
                private_key,
            );
            let pem = format!("-----BEGIN PRIVATE KEY-----\n{body}-----END PRIVATE KEY-----\n");
            assert_eq!(
                *PemDocument::parse(&pem).expect("PEM document").body,
                private_key,
            );
            let count = encoded.len().div_ceil(width);
            let putty = format!(
                "PuTTY-User-Key-File-2: ssh-rsa\nEncryption: none\nPublic-Lines: 1\nAAAA\nPrivate-Lines: {count}\n{body}"
            );
            assert_eq!(
                *PuttyDocument::parse(putty.as_bytes())
                    .expect("PuTTY document")
                    .decode_private()
                    .expect("PuTTY private body"),
                private_key,
            );
        }
    }

    const OPENSSH_NONE: &str = include_str!(
        "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519"
    );
    const RSA_512_PKCS8: &str = concat!(
        "-----BEGIN PRIVATE KEY-----\n",
        "MIIBVgIBADANBgkqhkiG9w0BAQEFAASCAUAwggE8AgEAAkEAv1eNChgOjTbDmuNq\n",
        "Qy2zizY+TRobUQraS0GRWMvKz/XEayiyAjMdsYmf+AfjNYiaH+XyeHbU4wWQcbkU\n",
        "V0z4UQIDAQABAkEAvVnPyhhqdham1eWNZ/OXBQHl/3kBZV7fDbBSPNRX3Rj7RtBS\n",
        "Q73TEq8NvxAKJJQH0MNbgt7t5oIh2jiFdRkTvQIhAO54Jy8iJvfjYLc56Fi1qLip\n",
        "aSezmfbHGvyYUs+TllUvAiEAzWh9cHcCpLU7eLDuqkbJ2XZpavTB1WxfkrcHduRx\n",
        "Kn8CIQC/LVxckQikmoki2y3GUHxe7pH63iWEjcK41nUtLKjMyQIgeVRKG/9AIXgn\n",
        "i8+++fdcTUZDWHkAcYdVIL1Z/GFNcxMCIQDmG2Gj8qCcvHGYGoVx3YlsSj6F39VJ\n",
        "/zDcDK88UMMc1g==\n",
        "-----END PRIVATE KEY-----\n",
    );
    const OPENSSH_ENCRYPTED: [&str; 10] = [
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.3des-cbc.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.aes128-cbc.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.aes192-cbc.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.aes256-cbc.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.aes128-ctr.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.aes192-ctr.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.aes256-ctr.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.aes128-gcm.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.aes256-gcm.enc"
        ),
        include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ed25519.chacha20-poly1305.enc"
        ),
    ];

    #[test]
    fn frozen_openssh_cipher_matrix_imports_and_authenticates() {
        assert_eq!(
            success(OPENSSH_NONE, None).r#type,
            SshKeyType::Ed25519 as i32
        );

        for document in OPENSSH_ENCRYPTED {
            assert_needs_passphrase(document, "OpenSSH");
            assert_eq!(
                success(document, Some(b"hunter42")).r#type,
                SshKeyType::Ed25519 as i32,
            );
            assert_error(
                document,
                Some(b"wrong-passphrase"),
                SshPrivateKeyImportErrorReason::InvalidPassphrase,
            );
        }

        let rsa = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_rsa_3072"
        );
        assert_eq!(success(rsa, None).r#type, SshKeyType::Rsa as i32);
        let rsa_encrypted = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_rsa_3072.aes256-ctr.enc"
        );
        assert_needs_passphrase(rsa_encrypted, "OpenSSH");
        assert_eq!(
            success(rsa_encrypted, Some(b"hunter42")).r#type,
            SshKeyType::Rsa as i32,
        );
        assert_error(
            rsa_encrypted,
            Some(b"wrong-passphrase"),
            SshPrivateKeyImportErrorReason::InvalidPassphrase,
        );

        for document in [
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_dsa_1024"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_ecdsa_p256"
            ),
        ] {
            assert_error(
                document,
                None,
                SshPrivateKeyImportErrorReason::UnsupportedAlgorithm,
            );
        }
    }

    #[test]
    fn openssh_import_rejects_mismatched_outer_ed25519_public_record() {
        let unencrypted = mutate_openssh_outer_public(OPENSSH_NONE);
        assert!(PrivateKey::from_openssh(&unencrypted).is_err());
        assert_error(
            &unencrypted,
            None,
            SshPrivateKeyImportErrorReason::MalformedKey,
        );

        let encrypted = mutate_openssh_outer_public(OPENSSH_ENCRYPTED[6]);
        let strict = PrivateKey::from_openssh(&encrypted).expect("encrypted container parses");
        assert!(strict.decrypt(b"hunter42").is_err());
        assert_error(
            &encrypted,
            Some(b"hunter42"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );
    }

    #[test]
    fn openssh_correct_passphrase_with_malformed_inner_padding_is_not_a_passphrase_error() {
        let malformed = mutate_openssh_last_ciphertext_byte(OPENSSH_ENCRYPTED[6]);
        assert_needs_passphrase(&malformed, "OpenSSH");
        assert_error(
            &malformed,
            Some(b"hunter42"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );
    }

    #[test]
    fn openssh_import_accepts_sshj_extended_sequential_padding() {
        let extended = extend_openssh_private_padding(
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_rsa_3072"
            ),
            8,
        );
        assert!(PrivateKey::from_openssh(&extended).is_err());
        assert_eq!(success(&extended, None).r#type, SshKeyType::Rsa as i32);

        let mut malformed = decode_openssh_fixture(&extended);
        *malformed.last_mut().expect("extended padding byte") ^= 1;
        assert_error(
            &encode_openssh_fixture(&malformed),
            None,
            SshPrivateKeyImportErrorReason::MalformedKey,
        );
    }

    #[test]
    fn openssh_armor_accepts_sshj_line_widths_and_ignores_trailing_bundle_data() {
        let decoded = decode_openssh_fixture(OPENSSH_ENCRYPTED[6]);
        let encoded_length = Base64::encode_string(&decoded).len();
        for width in [64, 76, encoded_length] {
            let document = encode_openssh_fixture_with_width(&decoded, width);
            assert_eq!(
                success(&document, Some(b"hunter42")).r#type,
                SshKeyType::Ed25519 as i32,
            );
        }

        let mut bundled = encode_openssh_fixture_with_width(&decoded, 64);
        bundled.push_str(
            "trailing text ignored by SSHJ\n-----BEGIN CERTIFICATE-----\nnot-base64\n-----END CERTIFICATE-----\n",
        );
        assert_eq!(
            success(&bundled, Some(b"hunter42")).r#type,
            SshKeyType::Ed25519 as i32,
        );
    }

    #[test]
    fn openssh_import_rejects_mismatched_outer_rsa_public_record() {
        let fixtures = [
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_rsa_3072"
                ),
                None,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_rsa_3072.aes256-ctr.enc"
                ),
                Some(b"hunter42".as_slice()),
            ),
        ];

        for (fixture, passphrase) in fixtures {
            let inner_public = success(fixture, passphrase).public_key.clone();
            let mutated = mutate_openssh_outer_public(fixture);
            let mutated_outer = parse_openssh_outer_public(&decode_openssh_fixture(&mutated))
                .expect("valid mutated outer RSA");

            assert_ne!(mutated_outer, inner_public);
            assert_error(
                &mutated,
                passphrase,
                SshPrivateKeyImportErrorReason::MalformedKey,
            );
        }
    }

    #[test]
    fn frozen_putty_v1_v2_and_v3_matrix_imports() {
        for (document, key_type) in unencrypted_putty_fixtures() {
            assert_eq!(success(document, None).r#type, key_type as i32);
        }

        assert_error(
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-ecdsa-none.ppk"
            ),
            None,
            SshPrivateKeyImportErrorReason::UnsupportedAlgorithm,
        );

        for (document, passphrase, key_type) in encrypted_putty_fixtures() {
            assert_needs_passphrase(document, "PuTTY");
            assert_eq!(success(document, Some(passphrase)).r#type, key_type as i32);
            assert_error(
                document,
                Some(b"wrong-passphrase"),
                SshPrivateKeyImportErrorReason::InvalidPassphrase,
            );
        }
    }

    #[test]
    fn upstream_putty_v3_none_uses_empty_mac_key() {
        // SSHJ's verbatim PuTTY ECDSA fixture is independently generated. Its
        // unsupported key type must not prevent this MAC known-answer check.
        let document = PuttyDocument::parse(include_bytes!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-ecdsa-none.ppk"
        )).unwrap();
        let public = document.decode_public().unwrap();
        let PuttyPrivatePayload {
            private_key: private,
            mac_key,
        } = document.private_payload(Some(b"ignored")).unwrap();
        assert!(mac_key.is_empty());
        assert_eq!(
            document.verify_mac(&mac_key, &public, &private),
            Ok(PuttyIntegrity::WholeFile)
        );
        let old_incorrect_key =
            sensitive_digest(DigestAlgorithm::Sha256, &[PUTTY_MAC_PREFIX]).unwrap();
        assert_eq!(
            document.verify_mac(&old_incorrect_key, &public, &private),
            Err(ImportError::MalformedKey)
        );
    }

    #[test]
    fn historical_putty_v1_verifies_private_integrity_and_rsa_consistency() {
        let fixture = unencrypted_putty_fixtures()[0].0;
        // Known answers computed independently with Python hashlib/hmac over
        // the frozen RSA private blob, without any SSH string framing.
        for (header, tag) in [
            ("Private-Hash", "2ed35deff1e1ab7da5fd05c0e3c0c48850e9fdbb"),
            ("Private-MAC", "38a3b3227872e3b9cbe84d3c131df8d08515b476"),
        ] {
            let document = replace_putty_private_mac(fixture, tag)
                .replace("Private-MAC:", &format!("{header}:"));
            assert_eq!(success(&document, None).r#type, SshKeyType::Rsa as i32);
            assert_eq!(
                success(&document, Some(b"ignored")).r#type,
                SshKeyType::Rsa as i32
            );
            let parsed = PuttyDocument::parse(document.as_bytes()).unwrap();
            let mut private = parsed.decode_private().unwrap();
            let last = skip_wire_string(&private, 0) - 1;
            private[last] ^= 1;
            assert_error(
                &replace_putty_payload(&document, "Private-Lines", &private),
                None,
                SshPrivateKeyImportErrorReason::MalformedKey,
            );
            // Public bytes are outside the v1 checksum, so successful integrity
            // verification must still be followed by mathematical validation.
            let mut public = parsed.decode_public().unwrap();
            *public.last_mut().unwrap() ^= 2;
            assert_error(
                &replace_putty_payload(&document, "Public-Lines", &public),
                None,
                SshPrivateKeyImportErrorReason::MalformedKey,
            );
            assert_error(
                &replace_putty_header(&document, header, "00"),
                None,
                SshPrivateKeyImportErrorReason::MalformedKey,
            );
            assert_error(
                &remove_putty_header(&document, header),
                None,
                SshPrivateKeyImportErrorReason::MalformedKey,
            );
            // Changing the version may never enable the weaker historical tag.
            for version in [2, 3] {
                assert_error(
                    &document.replace("File-1:", &format!("File-{version}:")),
                    None,
                    SshPrivateKeyImportErrorReason::MalformedKey,
                );
            }
        }
    }

    #[test]
    fn historical_putty_v1_encrypted_integrity_covers_plaintext_and_padding() {
        let fixture = unencrypted_putty_fixtures()[0].0;
        let parsed = PuttyDocument::parse(fixture.as_bytes()).unwrap();
        let mut private = parsed.decode_private().unwrap();
        let padded_len = private.len().next_multiple_of(16);
        private.resize(padded_len, 0x5a);
        let derived = parsed.derive_encryption(b"v1-passphrase").unwrap();
        let hash = sensitive_digest(DigestAlgorithm::Sha1, &[&private]).unwrap();
        let tag = sensitive_hmac(DigestAlgorithm::Sha1, &derived.mac_key, &[&private]).unwrap();
        Cipher::Aes256Cbc
            .encrypt(&derived.key, &derived.iv, &mut private)
            .unwrap();
        let encrypted = replace_putty_payload(fixture, "Private-Lines", &private);
        let encrypted = replace_putty_header(&encrypted, "Encryption", "aes256-cbc");
        for (header, tag) in [
            ("Private-Hash", hash.as_slice()),
            ("Private-MAC", tag.as_slice()),
        ] {
            let document = replace_putty_private_mac(&encrypted, &encode_test_hex(tag))
                .replace("Private-MAC:", &format!("{header}:"));
            assert_needs_passphrase(&document, "PuTTY");
            assert_eq!(
                success(&document, Some(b"v1-passphrase")).r#type,
                SshKeyType::Rsa as i32
            );
            assert_error(
                &document,
                Some(b"wrong"),
                SshPrivateKeyImportErrorReason::InvalidPassphrase,
            );
            let mut corrupted = private.clone();
            *corrupted.last_mut().unwrap() ^= 1;
            assert_error(
                &replace_putty_payload(&document, "Private-Lines", &corrupted),
                Some(b"v1-passphrase"),
                SshPrivateKeyImportErrorReason::InvalidPassphrase,
            );
        }
    }

    #[test]
    fn putty_preserves_raw_comment_bytes_for_all_versions() {
        for (fixture, passphrase, _) in putty_integrity_fixtures() {
            let document = replace_putty_comment_and_mac(fixture, passphrase, "caf\u{00e9}", true);
            let mut raw = document.replace("caf\u{00e9}", "caf?").into_bytes();
            let offset = raw.iter().position(|byte| *byte == b'?').unwrap();
            raw[offset] = 0xe9;
            assert!(std::str::from_utf8(&raw).is_err());
            assert!(matches!(
                result_bytes(&raw, passphrase),
                ssh_private_key_import_result::Result::Success(_)
            ));
            if passphrase.is_some() {
                assert!(matches!(
                    result_bytes(&raw, None),
                    ssh_private_key_import_result::Result::NeedsPassphrase(_)
                ));
            }
            raw[offset] ^= 1;
            let expected = putty_integrity_reason(passphrase);
            assert!(
                matches!(result_bytes(&raw, passphrase), ssh_private_key_import_result::Result::Error(error)
                if error.reason == expected as i32)
            );
        }
    }

    #[test]
    fn unencrypted_putty_rejects_private_exponent_changes_without_updating_mac() {
        for (document, key_type) in unencrypted_putty_fixtures() {
            if key_type != SshKeyType::Rsa {
                continue;
            }
            let parsed = PuttyDocument::parse(document.as_bytes()).expect("PPK fixture");
            let mut private = parsed.decode_private().expect("private payload");
            // Change d without changing the mpint's length/sign or its MAC.
            let last = skip_wire_string(&private, 0) - 1;
            private[last] ^= 1;
            let corrupted = replace_putty_payload(document, "Private-Lines", &private);
            assert_error(
                &corrupted,
                None,
                SshPrivateKeyImportErrorReason::MalformedKey,
            );
        }
    }

    fn encrypted_putty_fixtures() -> [(&'static str, &'static [u8], SshKeyType); 5] {
        [
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v1-ed25519-aes256-cbc.ppk"
                ),
                b"123456".as_slice(),
                SshKeyType::Ed25519,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v2-ed25519-aes256-cbc.ppk"
                ),
                b"123456".as_slice(),
                SshKeyType::Ed25519,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-rsa-argon2d-aes256-cbc.ppk"
                ),
                b"changeit".as_slice(),
                SshKeyType::Rsa,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-rsa-argon2i-aes256-cbc.ppk"
                ),
                b"changeit".as_slice(),
                SshKeyType::Rsa,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-rsa-argon2id-aes256-cbc.ppk"
                ),
                b"changeit".as_slice(),
                SshKeyType::Rsa,
            ),
        ]
    }

    fn unencrypted_putty_fixtures() -> [(&'static str, SshKeyType); 5] {
        [
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v1-rsa-none.ppk"
                ),
                SshKeyType::Rsa,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v2-rsa-none.ppk"
                ),
                SshKeyType::Rsa,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-rsa-none.ppk"
                ),
                SshKeyType::Rsa,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v2-ed25519-none.ppk"
                ),
                SshKeyType::Ed25519,
            ),
            (
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-ed25519-none.ppk"
                ),
                SshKeyType::Ed25519,
            ),
        ]
    }

    fn encrypted_putty_integrity_fixtures() -> [(&'static str, &'static [u8], SshKeyType); 3] {
        // Repeat corruption cases once per version. The frozen matrix above
        // covers all three Argon2 modes, including the more expensive v3 id key.
        let [v1, v2, _v3_d, v3_i, _v3_id] = encrypted_putty_fixtures();
        [v1, v2, v3_i]
    }

    fn putty_integrity_fixtures()
    -> impl Iterator<Item = (&'static str, Option<&'static [u8]>, SshKeyType)> {
        unencrypted_putty_fixtures()
            .into_iter()
            .map(|(document, key_type)| (document, None, key_type))
            .chain(
                encrypted_putty_integrity_fixtures()
                    .into_iter()
                    .map(|(document, passphrase, key_type)| (document, Some(passphrase), key_type)),
            )
    }

    const fn putty_integrity_reason(passphrase: Option<&[u8]>) -> SshPrivateKeyImportErrorReason {
        if passphrase.is_some() {
            SshPrivateKeyImportErrorReason::InvalidPassphrase
        } else {
            SshPrivateKeyImportErrorReason::MalformedKey
        }
    }

    #[test]
    fn unencrypted_putty_requires_integrity_for_all_versions() {
        for (document, _) in unencrypted_putty_fixtures() {
            let parsed = PuttyDocument::parse(document.as_bytes()).expect("PPK fixture");
            let mac = parsed.headers["Private-MAC"];
            let mut public = parsed.decode_public().expect("public payload");
            *public.last_mut().unwrap() ^= 1;
            let mut private = parsed.decode_private().expect("private payload");
            // Normalization ignores these trailing bytes, but the MAC must not.
            private.push(1);
            let mut corrupted = vec![
                remove_putty_header(document, "Private-MAC"),
                replace_putty_header(document, "Comment", "corrupted comment"),
                replace_putty_payload(document, "Public-Lines", &public),
                replace_putty_payload(document, "Private-Lines", &private),
            ];
            for invalid in [
                "".to_owned(),
                "0".to_owned(),
                "00".to_owned(),
                "not-hex".to_owned(),
                "0".repeat(mac.len()),
                mac.to_ascii_uppercase(),
            ] {
                corrupted.push(replace_putty_private_mac(document, &invalid));
            }
            for corrupted in corrupted {
                for passphrase in [None, Some(b"ignored".as_slice())] {
                    assert_error(
                        &corrupted,
                        passphrase,
                        SshPrivateKeyImportErrorReason::MalformedKey,
                    );
                }
            }
        }
    }

    #[test]
    fn unencrypted_putty_imports_and_signs_with_or_without_a_passphrase() {
        for (document, key_type) in unencrypted_putty_fixtures() {
            for passphrase in [Some(b"".as_slice()), Some(b"ignored".as_slice())] {
                assert_eq!(success(document, passphrase).r#type, key_type as i32);
            }
            let mut material = success(document, None);
            assert_eq!(material.r#type, key_type as i32);
            let description = ssh_keys::describe(
                key_type,
                std::mem::take(&mut material.private_key),
                std::mem::take(&mut material.public_key),
            )
            .expect("describe valid PPK");
            let mut description = SshKeyDescription::decode(description.as_slice()).unwrap();
            ssh_keys::sign(
                std::mem::take(&mut description.private_key_pem),
                Some(std::mem::take(&mut description.public_key_openssh)),
                b"PPK integrity regression".to_vec(),
                0x02,
            )
            .expect("sign with valid PPK");
            // These headers must never trigger a KDF on an unencrypted key.
            let extra_headers =
                format!("{document}\nKey-Derivation: unsupported\nArgon2-Memory: 4294967295\n");
            assert_eq!(
                success(&extra_headers, Some(b"ignored")).r#type,
                key_type as i32
            );
        }
    }

    #[test]
    fn encrypted_putty_requires_integrity_for_all_versions() {
        for (document, passphrase, _) in encrypted_putty_integrity_fixtures() {
            let parsed = PuttyDocument::parse(document.as_bytes()).expect("PPK fixture");
            let mac = parsed.headers["Private-MAC"];
            let mut private = parsed.decode_private().expect("encrypted payload");
            *private.last_mut().unwrap() ^= 1;
            let mut corrupted = vec![
                remove_putty_header(document, "Private-MAC"),
                replace_putty_header(document, "Comment", "corrupted comment"),
                replace_putty_payload(document, "Private-Lines", &private),
            ];
            for invalid in [
                "0".to_owned(),
                "00".to_owned(),
                "not-hex".to_owned(),
                "0".repeat(mac.len()),
                mac.to_ascii_uppercase(),
            ] {
                corrupted.push(replace_putty_private_mac(document, &invalid));
            }
            for corrupted in corrupted {
                assert_error(
                    &corrupted,
                    Some(passphrase),
                    SshPrivateKeyImportErrorReason::InvalidPassphrase,
                );
            }
        }
    }

    #[test]
    fn putty_requires_original_comment_bytes_for_all_versions() {
        for (fixture, passphrase, key_type) in putty_integrity_fixtures() {
            let reason = putty_integrity_reason(passphrase);
            for comment in ["  caf\u{00e9}\t ", "Ключ", "rocket \u{1f680}"] {
                for sshj_encoding in [false, true] {
                    let document =
                        replace_putty_comment_and_mac(fixture, passphrase, comment, sshj_encoding);
                    if sshj_encoding {
                        assert_error(&document, passphrase, reason);
                        continue;
                    }
                    assert_eq!(success(&document, passphrase).r#type, key_type as i32);
                    let changed = replace_putty_header(&document, "Comment", "changed comment");
                    assert_error(&changed, passphrase, reason);
                    if comment != comment.trim() {
                        let trimmed = replace_putty_header(&document, "Comment", comment.trim());
                        assert_error(&trimmed, passphrase, reason);
                    }
                    // A valid comment MAC must still authenticate the private data.
                    let parsed = PuttyDocument::parse(document.as_bytes()).unwrap();
                    let mut private = parsed.decode_private().unwrap();
                    *private.last_mut().unwrap() ^= 1;
                    let corrupted = replace_putty_payload(&document, "Private-Lines", &private);
                    assert_error(&corrupted, passphrase, reason);
                }
            }
        }
    }

    #[test]
    fn putty_rejects_comment_substitution_with_matching_utf16_low_bytes() {
        for (fixture, passphrase, key_type) in putty_integrity_fixtures() {
            let document = replace_putty_comment_and_mac(fixture, passphrase, "root@sshj", false);
            assert_eq!(success(&document, passphrase).r#type, key_type as i32);

            // U+0172 and ASCII 'r' share the low UTF-16 byte 0x72. Changing
            // the comment without updating its MAC must fail authentication.
            let changed = replace_putty_header(&document, "Comment", "\u{0172}oot@sshj");
            assert_error(&changed, passphrase, putty_integrity_reason(passphrase));
        }
    }

    #[test]
    fn frozen_pem_matrix_imports_and_classifies_expected_failures() {
        for document in [
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs1-rsa-none.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-rsa-none.pem"
            ),
        ] {
            assert_eq!(success(document, None).r#type, SshKeyType::Rsa as i32);
        }

        for document in [
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-ecdsa-none.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-dsa-none.pem"
            ),
        ] {
            assert_error(
                document,
                None,
                SshPrivateKeyImportErrorReason::UnsupportedAlgorithm,
            );
        }

        let encrypted = [
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs1-rsa-encrypted.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-rsa-encrypted.pem"
            ),
        ];
        for document in encrypted {
            assert_needs_passphrase(document, "PEM");
            assert_eq!(
                success(document, Some(b"passphrase")).r#type,
                SshKeyType::Rsa as i32,
            );
            assert_error(
                document,
                Some(b"wrong-passphrase"),
                SshPrivateKeyImportErrorReason::InvalidPassphrase,
            );
        }
    }

    #[test]
    fn every_supported_container_family_normalizes_to_a_cxf_exportable_key() {
        let fixtures: [(&str, &str, Option<&[u8]>); 6] = [
            ("OpenSSH Ed25519", OPENSSH_NONE, None),
            (
                "OpenSSH RSA",
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/openssh/id_rsa_3072"
                ),
                None,
            ),
            (
                "PuTTY Ed25519",
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v2-ed25519-none.ppk"
                ),
                None,
            ),
            (
                "encrypted PuTTY RSA",
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-rsa-argon2id-aes256-cbc.ppk"
                ),
                Some(b"changeit"),
            ),
            (
                "PKCS#1 PEM RSA",
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs1-rsa-none.pem"
                ),
                None,
            ),
            (
                "encrypted PKCS#8 PEM RSA",
                include_str!(
                    "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-rsa-encrypted.pem"
                ),
                Some(b"passphrase"),
            ),
        ];

        for (label, document, passphrase) in fixtures {
            let material = success(document, passphrase);
            let expected_type = material.r#type;
            let exported = export_imported_material_for_cxf(material);

            assert_eq!(exported.r#type, expected_type, "{label}");
            PrivateKeyInfo::from_der(&exported.private_key_pkcs8)
                .unwrap_or_else(|_| panic!("{label} did not export PKCS#8"));
        }
    }

    #[test]
    fn cxf_ed25519_pkcs8_reimports_and_reexports() {
        let exported = export_imported_material_for_cxf(success(OPENSSH_NONE, None));
        let exported_info =
            PrivateKeyInfo::from_der(&exported.private_key_pkcs8).expect("Ed25519 PKCS#8 v1");
        assert_eq!(exported_info.algorithm.oid, ED25519_OID);
        assert!(exported_info.public_key.is_none());
        let pem = encode_test_pem("PRIVATE KEY", &exported.private_key_pkcs8);
        let reexported = export_imported_material_for_cxf(success(&pem, None));

        assert_eq!(reexported.r#type, SshKeyType::Ed25519 as i32);
        assert_eq!(reexported.private_key_pkcs8, exported.private_key_pkcs8,);
    }

    #[test]
    fn rsa_pkcs8_without_parameters_is_reencoded_to_jdk21_canonical_der() {
        let canonical_pem = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-rsa-none.pem"
        );
        let canonical = PemDocument::parse(canonical_pem).expect("canonical RSA PKCS#8 fixture");
        let canonical_info =
            PrivateKeyInfo::from_der(&canonical.body).expect("canonical private-key info");
        let omitted_parameters = Zeroizing::new(
            PrivateKeyInfo::new(
                AlgorithmIdentifierRef {
                    oid: RSA_ENCRYPTION_OID,
                    parameters: None,
                },
                canonical_info.private_key,
            )
            .to_der()
            .expect("PKCS#8 without rsaEncryption parameters"),
        );
        assert!(
            PrivateKeyInfo::from_der(&omitted_parameters)
                .expect("omitted-parameters private-key info")
                .algorithm
                .parameters
                .is_none()
        );

        let input = encode_test_pem("PRIVATE KEY", &omitted_parameters);
        let material = success(&input, None);
        let normalized =
            PrivateKeyInfo::from_der(&material.private_key).expect("normalized private-key info");
        assert_eq!(normalized.algorithm.oid, RSA_ENCRYPTION_OID);
        assert_eq!(normalized.algorithm.parameters, Some(AnyRef::NULL));
        assert_eq!(material.private_key, *canonical.body);

        // Temurin JDK 21 KeyFactory.generatePrivate(...).getEncoded() golden.
        let digest = sensitive_digest(DigestAlgorithm::Sha256, &[&material.private_key])
            .expect("SHA-256 golden");
        assert_eq!(
            encode_test_hex(&digest),
            "b4fbf2f051b7a32eea7c4b9db3d7ca878ed8de7f20437bcd8ad01aeba778f7d7",
        );
    }

    #[test]
    fn rsa_pkcs8_present_non_null_parameters_are_preserved_like_jdk21() {
        let canonical_pem = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-rsa-none.pem"
        );
        let canonical = PemDocument::parse(canonical_pem).expect("canonical RSA PKCS#8 fixture");
        let canonical_info =
            PrivateKeyInfo::from_der(&canonical.body).expect("canonical private-key info");
        let parameter_der = [0x04, 0x01, 0x01];
        let parameter = AnyRef::from_der(&parameter_der).expect("OCTET STRING parameter");
        let jdk_accepted = Zeroizing::new(
            PrivateKeyInfo::new(
                AlgorithmIdentifierRef {
                    oid: RSA_ENCRYPTION_OID,
                    parameters: Some(parameter),
                },
                canonical_info.private_key,
            )
            .to_der()
            .expect("PKCS#8 with non-NULL rsaEncryption parameters"),
        );

        let input = encode_test_pem("PRIVATE KEY", &jdk_accepted);
        let material = success(&input, None);
        let normalized =
            PrivateKeyInfo::from_der(&material.private_key).expect("normalized private-key info");
        assert_eq!(normalized.algorithm.parameters, Some(parameter));
        // Temurin 21.0.9 KeyFactory.getEncoded() preserves this parameter
        // encoding exactly while still dropping any PKCS#8 attributes.
        assert_eq!(material.private_key, *jdk_accepted);
    }

    #[test]
    fn rsa_public_exponent_one_is_rejected() {
        let canonical_pem = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-rsa-none.pem"
        );
        let canonical = PemDocument::parse(canonical_pem).expect("canonical RSA PKCS#8 fixture");
        let info = PrivateKeyInfo::from_der(&canonical.body).expect("private-key info");
        let private = RsaPrivateKey::from_der(info.private_key).expect("PKCS#1 body");
        let exponent_one = [1_u8];
        let mut invalid = private.clone();
        invalid.public_exponent = UintRef::new(&exponent_one).expect("positive exponent one");
        let invalid_pkcs1 = Zeroizing::new(invalid.to_der().expect("invalid test PKCS#1"));
        let invalid_pkcs8 = wrap_rsa_pkcs8(&invalid_pkcs1).expect("invalid test PKCS#8");
        let input = encode_test_pem("PRIVATE KEY", &invalid_pkcs8);

        assert_error(&input, None, SshPrivateKeyImportErrorReason::MalformedKey);
    }

    #[test]
    fn pem_import_stops_after_first_matching_private_key_footer_like_sshj() {
        let private_key = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-rsa-none.pem"
        );
        let expected = success(private_key, None);
        let bundle = format!(
            "{private_key}-----BEGIN CERTIFICATE-----\nignored-by-sshj-reader\n-----END CERTIFICATE-----\n"
        );
        let actual = success(&bundle, None);

        assert_eq!(actual.r#type, expected.r#type);
        assert_eq!(actual.private_key, expected.private_key);
        assert_eq!(actual.public_key, expected.public_key);
    }

    #[test]
    fn frozen_legacy_pem_decrypt_only_matrix_imports() {
        let matrix = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/legacy-pem-dek-matrix.tsv"
        );
        let corpus_root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../../common/src/desktopTest/resources/ssh-import-corpus");
        let mut imported = 0_usize;
        for row in matrix.lines().skip(1).filter(|row| !row.is_empty()) {
            let columns: Vec<_> = row.split('\t').collect();
            let mode = *columns.get(2).expect("legacy PEM mode column");
            let resource = *columns.get(5).expect("legacy PEM resource column");
            let document = std::fs::read_to_string(corpus_root.join(resource))
                .expect("checked-in legacy PEM fixture");
            assert_needs_passphrase(&document, "PEM");
            assert_eq!(
                success(&document, Some(b"passphrase")).r#type,
                SshKeyType::Rsa as i32,
                "{resource}",
            );
            let wrong_passphrase_reason = if matches!(mode, "CFB" | "OFB") {
                SshPrivateKeyImportErrorReason::MalformedKey
            } else {
                SshPrivateKeyImportErrorReason::InvalidPassphrase
            };
            assert_error(
                &document,
                Some(b"wrong-passphrase"),
                wrong_passphrase_reason,
            );
            imported += 1;
        }
        assert_eq!(imported, 42);
    }

    #[test]
    fn frozen_jdk21_pbes2_matrix_imports_with_exact_passphrase_behavior() {
        let documents = [
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha1-aes128.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha1-aes256.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha224-aes128.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha224-aes256.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha256-aes128.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha256-aes256.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha384-aes128.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha384-aes256.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha512-aes128.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha512-aes256.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha512-224-aes128.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha512-224-aes256.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha512-256-aes128.pem"
            ),
            include_str!(
                "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha512-256-aes256.pem"
            ),
        ];

        for document in documents {
            assert_needs_passphrase(document, "PEM");
            assert_eq!(
                success(document, Some(b"passphrase")).r#type,
                SshKeyType::Rsa as i32,
            );
            assert_error(
                document,
                Some(b"wrong-passphrase"),
                SshPrivateKeyImportErrorReason::InvalidPassphrase,
            );
        }
    }

    #[test]
    fn classic_pbe_pkcs8_family_is_malformed_like_sshj_on_jdk21() {
        for oid in [
            "1.2.840.113549.1.5.3",
            "1.3.6.1.4.1.42.2.19.1",
            "1.2.840.113549.1.12.1.3",
            "1.2.840.113549.1.12.1.6",
            "1.2.840.113549.1.12.1.5",
            "1.2.840.113549.1.12.1.2",
            "1.2.840.113549.1.12.1.1",
        ] {
            let oid = ObjectIdentifier::new_unwrap(oid);
            let mut algorithm = oid.to_der().expect("classic PBE OID DER");
            algorithm.extend_from_slice(&[0x05, 0x00]);
            let algorithm = encode_short_test_sequence(&algorithm);
            let mut body = algorithm;
            body.extend_from_slice(&[0x04, 0x01, 0x00]);
            let document =
                encode_test_pem("ENCRYPTED PRIVATE KEY", &encode_short_test_sequence(&body));

            assert_needs_passphrase(&document, "PEM");
            assert_error(
                &document,
                Some(b"passphrase"),
                SshPrivateKeyImportErrorReason::MalformedKey,
            );
        }
    }

    #[test]
    fn pbes2_rejects_key_length_that_disagrees_with_aes_cipher() {
        let document = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pkcs8-pbe/pbes2-hmac-sha256-aes128.pem"
        );
        let parsed = PemDocument::parse(document).expect("frozen PBES2 PEM");
        let mut body = parsed.body.to_vec();
        let key_length_offset = body[..104]
            .windows(3)
            .position(|window| window == [0x02, 0x01, 0x10])
            .expect("explicit 16-byte PBKDF2 key length");
        body[key_length_offset + 2] = 0x20;
        let mismatched = encode_test_pem("ENCRYPTED PRIVATE KEY", &body);

        assert_needs_passphrase(&mismatched, "PEM");
        assert_error(
            &mismatched,
            Some(b"passphrase"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );
    }

    #[test]
    fn imported_rsa_modulus_bounds_match_jdk21_without_changing_generation_policy() {
        assert_eq!(success(RSA_512_PKCS8, None).r#type, SshKeyType::Rsa as i32);

        let exponent = [3_u8];
        let modulus_512 = [0x80_u8; 64];
        let modulus_511 = [0x7f_u8; 64];
        assert_eq!(
            validate_rsa_public_components(&modulus_512, &exponent),
            Ok(()),
        );
        assert_eq!(
            validate_rsa_public_components(&modulus_511, &exponent),
            Err(ImportError::MalformedKey),
        );

        let mut modulus_16_384 = vec![0_u8; 2_048];
        modulus_16_384[0] = 0x80;
        let mut modulus_16_385 = vec![0_u8; 2_049];
        modulus_16_385[0] = 0x01;
        assert_eq!(
            validate_rsa_public_components(&modulus_16_384, &exponent),
            Ok(()),
        );
        assert_eq!(
            validate_rsa_public_components(&modulus_16_385, &exponent),
            Err(ImportError::MalformedKey),
        );
    }

    #[test]
    fn openssh_rsa_normalization_retains_complete_crt_and_signs() {
        let generated = generate_rsa_pkcs1_der(1_024).expect("RSA generation");
        let parsed = RsaPrivateKey::from_der(&generated).expect("generated PKCS#1");
        let keypair = RsaKeypair {
            public: RsaPublicKey {
                n: mpint(parsed.modulus.as_bytes()),
                e: mpint(parsed.public_exponent.as_bytes()),
            },
            private: SshRsaPrivateKey {
                d: mpint(parsed.private_exponent.as_bytes()),
                iqmp: mpint(parsed.coefficient.as_bytes()),
                p: mpint(parsed.prime1.as_bytes()),
                q: mpint(parsed.prime2.as_bytes()),
            },
        };
        let openssh = PrivateKey::new_with_checkint(KeypairData::Rsa(keypair), "", 0x1234_5678)
            .expect("OpenSSH RSA key")
            .to_openssh(LineEnding::LF)
            .expect("OpenSSH PEM");

        let material = success(&openssh, None);
        let info = PrivateKeyInfo::from_der(&material.private_key).expect("PKCS#8 output");
        let normalized = RsaPrivateKey::from_der(info.private_key).expect("PKCS#1 output");
        for component in [
            normalized.prime1.as_bytes(),
            normalized.prime2.as_bytes(),
            normalized.exponent1.as_bytes(),
            normalized.exponent2.as_bytes(),
            normalized.coefficient.as_bytes(),
        ] {
            assert!(component.iter().any(|byte| *byte != 0));
        }

        let formatted = ssh_keys::format_private_key_text(SshKeyType::Rsa, &material.private_key)
            .expect("format complete imported RSA");
        ssh_keys::sign(
            formatted,
            None,
            b"complete OpenSSH RSA round trip".to_vec(),
            0x02,
        )
        .expect("AWS-LC signing after import");
    }

    #[test]
    fn openssh_rsa_late_component_failure_uses_zeroizing_guards() {
        let generated = generate_rsa_pkcs1_der(1_024).expect("RSA generation");
        let parsed = RsaPrivateKey::from_der(&generated).expect("generated PKCS#1");
        let keypair = RsaKeypair {
            public: RsaPublicKey {
                n: mpint(parsed.modulus.as_bytes()),
                e: mpint(parsed.public_exponent.as_bytes()),
            },
            private: SshRsaPrivateKey {
                d: mpint(parsed.private_exponent.as_bytes()),
                p: mpint(parsed.prime1.as_bytes()),
                q: mpint(parsed.prime2.as_bytes()),
                // Parsing fails only after d, p, and q have been copied into
                // Zeroizing guards, exercising their early-return path.
                iqmp: Mpint::from_bytes(&[0xff]).expect("negative mpint"),
            },
        };

        assert!(matches!(
            rsa_material_from_openssh(&keypair),
            Err(ImportError::MalformedKey),
        ));
    }

    #[test]
    fn putty_rsa_keeps_incomplete_ned_pkcs8_compatibility() {
        let document = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v2-rsa-none.ppk"
        );
        let material = success(document, None);
        let info = PrivateKeyInfo::from_der(&material.private_key).expect("PKCS#8 output");
        let private = RsaPrivateKey::from_der(info.private_key).expect("PKCS#1 output");
        for component in [
            private.prime1.as_bytes(),
            private.prime2.as_bytes(),
            private.exponent1.as_bytes(),
            private.exponent2.as_bytes(),
            private.coefficient.as_bytes(),
        ] {
            assert_eq!(component, &[0]);
        }
        let digest = sensitive_digest(DigestAlgorithm::Sha256, &[&material.private_key])
            .expect("SHA-256 golden");
        assert_eq!(
            encode_test_hex(&digest),
            "f290cb725f4985c52d3651968fb45c05271f0ce63f1c0d6e547ac59f41364ad0",
        );
    }

    #[test]
    fn malformed_unsupported_and_resource_limited_inputs_are_typed() {
        assert_error(
            "not a private key",
            None,
            SshPrivateKeyImportErrorReason::UnsupportedFormat,
        );
        assert_error(
            "-----BEGIN RSA PRIVATE KEY-----\nnot-base64\n-----END RSA PRIVATE KEY-----",
            None,
            SshPrivateKeyImportErrorReason::MalformedKey,
        );
        for (document, expected) in [
            (
                b"\x89PNG\r\n\x1a\n\xff".as_slice(),
                SshPrivateKeyImportErrorReason::UnsupportedFormat,
            ),
            (
                b"-----BEGIN RSA PRIVATE KEY-----\n\xff\n".as_slice(),
                SshPrivateKeyImportErrorReason::MalformedKey,
            ),
        ] {
            assert!(matches!(
                result_bytes(document, None),
                ssh_private_key_import_result::Result::Error(error)
                    if error.reason == expected as i32
            ));
        }
        assert_eq!(
            import(vec![b'x'; MAX_IMPORT_TEXT_BYTES + 1], None),
            Err(PrimitiveError::ResourceLimit),
        );
        assert_eq!(
            import(OPENSSH_NONE.as_bytes().to_vec(), Some(vec![0xff])),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn malformed_private_base64_prefixes_use_zeroizing_decode_buffers() {
        let openssh = corrupt_armored_base64_tail(OPENSSH_NONE);
        assert_error(&openssh, None, SshPrivateKeyImportErrorReason::MalformedKey);

        let pem = corrupt_armored_base64_tail(include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/pem/pkcs8-rsa-none.pem"
        ));
        assert_error(&pem, None, SshPrivateKeyImportErrorReason::MalformedKey);

        let putty = corrupt_putty_private_base64_tail(include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v2-rsa-none.ppk"
        ));
        assert_error(&putty, None, SshPrivateKeyImportErrorReason::MalformedKey);
    }

    #[test]
    fn encrypted_container_preflight_prompts_before_deep_parsing() {
        let malformed_pem = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nnot-base64\n-----END ENCRYPTED PRIVATE KEY-----";
        assert_needs_passphrase(malformed_pem, "PEM");
        assert_error(
            malformed_pem,
            Some(b"passphrase"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );

        let putty = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v2-rsa-none.ppk"
        )
        .replace("Encryption: none", "Encryption: unsupported");
        assert_needs_passphrase(&putty, "PuTTY");
        assert_error(
            &putty,
            Some(b"passphrase"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );

        let openssh = replace_openssh_cipher(OPENSSH_ENCRYPTED[6], b"bogus-ciph");
        assert_needs_passphrase(&openssh, "OpenSSH");
        assert_error(
            &openssh,
            Some(b"hunter42"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );

        let openssh = replace_openssh_kdf(OPENSSH_ENCRYPTED[6], b"bogus!");
        assert_needs_passphrase(&openssh, "OpenSSH");
        assert_error(
            &openssh,
            Some(b"hunter42"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );
    }

    #[test]
    fn attacker_controlled_kdf_costs_fail_before_expensive_work() {
        let openssh_resource =
            replace_openssh_bcrypt_rounds(OPENSSH_ENCRYPTED[6], MAX_OPENSSH_BCRYPT_ROUNDS + 1);
        assert_needs_passphrase(&openssh_resource, "OpenSSH");
        assert_eq!(
            import(openssh_resource.into_bytes(), Some(b"hunter42".to_vec()),),
            Err(PrimitiveError::ResourceLimit),
        );

        let openssh_malformed = replace_openssh_bcrypt_rounds(OPENSSH_ENCRYPTED[6], 0);
        assert_error(
            &openssh_malformed,
            Some(b"hunter42"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );

        let putty = include_str!(
            "../../../../../../common/src/desktopTest/resources/ssh-import-corpus/ppk/v3-rsa-argon2i-aes256-cbc.ppk"
        );
        for resource_limited in [
            putty.replace("Argon2-Passes: 5", "Argon2-Passes: 33"),
            putty.replace("Argon2-Memory: 1024", "Argon2-Memory: 65537"),
            putty.replace("Argon2-Parallelism: 2", "Argon2-Parallelism: 9"),
            putty
                .replace("Argon2-Passes: 5", "Argon2-Passes: 17")
                .replace("Argon2-Memory: 1024", "Argon2-Memory: 16384"),
            replace_putty_header(putty, "Argon2-Salt", &"aa".repeat(1_025)),
        ] {
            assert_needs_passphrase(&resource_limited, "PuTTY");
            assert_eq!(
                import(resource_limited.into_bytes(), Some(b"changeit".to_vec())),
                Err(PrimitiveError::ResourceLimit),
            );
        }

        let putty_malformed = putty.replace("Argon2-Passes: 5", "Argon2-Passes: 0");
        assert_error(
            &putty_malformed,
            Some(b"changeit"),
            SshPrivateKeyImportErrorReason::MalformedKey,
        );
    }

    fn result(document: &str, passphrase: Option<&[u8]>) -> ssh_private_key_import_result::Result {
        result_bytes(document.as_bytes(), passphrase)
    }

    fn result_bytes(
        document: &[u8],
        passphrase: Option<&[u8]>,
    ) -> ssh_private_key_import_result::Result {
        let encoded = import(document.to_vec(), passphrase.map(|value| value.to_vec()))
            .expect("domain import result");
        SshPrivateKeyImportResult::decode(encoded.as_slice())
            .expect("import result protobuf")
            .result
            .expect("typed import outcome")
    }

    fn success(document: &str, passphrase: Option<&[u8]>) -> SshKeyMaterial {
        match result(document, passphrase) {
            ssh_private_key_import_result::Result::Success(success) => {
                success.key_material.expect("success key material")
            }
            ssh_private_key_import_result::Result::NeedsPassphrase(prompt) => {
                panic!(
                    "expected successful import, got passphrase prompt for {}",
                    prompt.format_label
                )
            }
            ssh_private_key_import_result::Result::Error(error) => {
                panic!(
                    "expected successful import, got error reason {}",
                    error.reason
                )
            }
        }
    }

    fn export_imported_material_for_cxf(mut material: SshKeyMaterial) -> SshKeyExportCxfResult {
        let key_type = SshKeyType::try_from(material.r#type).expect("supported SSH key type");
        let description_payload = ssh_keys::describe(
            key_type,
            std::mem::take(&mut material.private_key),
            std::mem::take(&mut material.public_key),
        )
        .expect("stored SSH key description");
        let mut description = SshKeyDescription::decode(description_payload.as_slice())
            .expect("SSH key description payload");
        let exported_payload = ssh_keys::export_cxf(
            std::mem::take(&mut description.private_key_pem),
            std::mem::take(&mut description.public_key_openssh),
        )
        .expect("CXF export");
        SshKeyExportCxfResult::decode(exported_payload.as_slice()).expect("CXF export payload")
    }

    fn assert_needs_passphrase(document: &str, expected_label: &str) {
        match result(document, None) {
            ssh_private_key_import_result::Result::NeedsPassphrase(prompt) => {
                assert_eq!(prompt.format_label, expected_label)
            }
            _ => panic!("expected passphrase prompt"),
        }
    }

    fn assert_error(
        document: &str,
        passphrase: Option<&[u8]>,
        expected: SshPrivateKeyImportErrorReason,
    ) {
        match result(document, passphrase) {
            ssh_private_key_import_result::Result::Error(error) => {
                assert_eq!(
                    SshPrivateKeyImportErrorReason::try_from(error.reason),
                    Ok(expected),
                )
            }
            _ => panic!("expected typed import error"),
        }
    }

    fn mpint(value: &[u8]) -> Mpint {
        Mpint::from_positive_bytes(value).expect("positive RSA component")
    }

    fn mutate_openssh_outer_public(document: &str) -> String {
        let mut decoded = decode_openssh_fixture(document);
        let mut offset = b"openssh-key-v1\0".len();
        for _ in 0..3 {
            offset = skip_wire_string(&decoded, offset);
        }
        offset = offset.checked_add(4).expect("number of keys field");
        let outer_end = skip_wire_string(&decoded, offset);
        let outer_start = offset.checked_add(4).expect("outer public prefix");
        assert!(outer_end > outer_start);
        decoded[outer_end - 1] ^= 1;

        encode_openssh_fixture(&decoded)
    }

    fn mutate_openssh_last_ciphertext_byte(document: &str) -> String {
        let mut decoded = decode_openssh_fixture(document);
        let mut offset = b"openssh-key-v1\0".len();
        for _ in 0..3 {
            offset = skip_wire_string(&decoded, offset);
        }
        let key_count_end = offset.checked_add(4).expect("number of keys field");
        let key_count = u32::from_be_bytes(
            decoded[offset..key_count_end]
                .try_into()
                .expect("number of keys bytes"),
        );
        offset = key_count_end;
        for _ in 0..key_count {
            offset = skip_wire_string(&decoded, offset);
        }
        let ciphertext_end = skip_wire_string(&decoded, offset);
        let ciphertext_start = offset.checked_add(4).expect("ciphertext prefix");
        assert!(ciphertext_end > ciphertext_start);
        decoded[ciphertext_end - 1] ^= 1;
        encode_openssh_fixture(&decoded)
    }

    fn extend_openssh_private_padding(document: &str, additional_bytes: usize) -> String {
        let mut decoded = decode_openssh_fixture(document);
        let mut offset = b"openssh-key-v1\0".len();
        for _ in 0..3 {
            offset = skip_wire_string(&decoded, offset);
        }
        let key_count_end = offset.checked_add(4).expect("number of keys field");
        let key_count = u32::from_be_bytes(
            decoded[offset..key_count_end]
                .try_into()
                .expect("number of keys bytes"),
        );
        offset = key_count_end;
        for _ in 0..key_count {
            offset = skip_wire_string(&decoded, offset);
        }

        let private_length_end = offset.checked_add(4).expect("private length prefix");
        let private_length = u32::from_be_bytes(
            decoded[offset..private_length_end]
                .try_into()
                .expect("private length bytes"),
        );
        assert_eq!(
            private_length_end + private_length as usize,
            decoded.len(),
            "private section must be the final OpenSSH field",
        );
        assert!(decoded.ends_with(&[1, 2, 3]));
        let extended_length = private_length
            .checked_add(u32::try_from(additional_bytes).expect("test extension fits u32"))
            .expect("extended private section length");
        decoded[offset..private_length_end].copy_from_slice(&extended_length.to_be_bytes());
        let first = decoded.last().copied().expect("existing padding byte");
        decoded.extend((0..additional_bytes).scan(first, |value, _| {
            *value = value.wrapping_add(1);
            Some(*value)
        }));
        assert_eq!((private_length as usize + additional_bytes) % 8, 0);
        encode_openssh_fixture(&decoded)
    }

    fn replace_openssh_cipher(document: &str, replacement: &[u8]) -> String {
        let mut decoded = decode_openssh_fixture(document);
        let offset = b"openssh-key-v1\0".len();
        let cipher_end = skip_wire_string(&decoded, offset);
        let cipher_start = offset.checked_add(4).expect("cipher prefix");
        assert_eq!(replacement.len(), cipher_end - cipher_start);
        decoded[cipher_start..cipher_end].copy_from_slice(replacement);
        encode_openssh_fixture(&decoded)
    }

    fn replace_openssh_kdf(document: &str, replacement: &[u8]) -> String {
        let mut decoded = decode_openssh_fixture(document);
        let mut offset = b"openssh-key-v1\0".len();
        offset = skip_wire_string(&decoded, offset);
        let kdf_end = skip_wire_string(&decoded, offset);
        let kdf_start = offset.checked_add(4).expect("KDF prefix");
        assert_eq!(replacement.len(), kdf_end - kdf_start);
        decoded[kdf_start..kdf_end].copy_from_slice(replacement);
        encode_openssh_fixture(&decoded)
    }

    fn replace_openssh_bcrypt_rounds(document: &str, rounds: u32) -> String {
        let mut decoded = decode_openssh_fixture(document);
        let mut options_offset = b"openssh-key-v1\0".len();
        options_offset = skip_wire_string(&decoded, options_offset);
        options_offset = skip_wire_string(&decoded, options_offset);
        let options_end = skip_wire_string(&decoded, options_offset);
        let salt_offset = options_offset.checked_add(4).expect("KDF options prefix");
        let salt_end = skip_wire_string(&decoded, salt_offset);
        assert_eq!(salt_end.checked_add(4), Some(options_end));
        decoded[salt_end..options_end].copy_from_slice(&rounds.to_be_bytes());
        encode_openssh_fixture(&decoded)
    }

    fn decode_openssh_fixture(document: &str) -> Vec<u8> {
        let encoded: String = document
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty() && !line.starts_with("-----"))
            .collect();
        Base64::decode_vec(&encoded).expect("OpenSSH fixture base64")
    }

    fn encode_openssh_fixture(decoded: &[u8]) -> String {
        encode_openssh_fixture_with_width(decoded, 70)
    }

    fn encode_openssh_fixture_with_width(decoded: &[u8], width: usize) -> String {
        let encoded = Base64::encode_string(decoded);
        let mut output = String::from("-----BEGIN OPENSSH PRIVATE KEY-----\n");
        for line in encoded.as_bytes().chunks(width) {
            output.push_str(std::str::from_utf8(line).expect("base64 is ASCII"));
            output.push('\n');
        }
        output.push_str("-----END OPENSSH PRIVATE KEY-----\n");
        output
    }

    fn encode_test_pem(label: &str, decoded: &[u8]) -> String {
        let encoded = Base64::encode_string(decoded);
        let mut output = format!("-----BEGIN {label}-----\n");
        for line in encoded.as_bytes().chunks(64) {
            output.push_str(std::str::from_utf8(line).expect("base64 is ASCII"));
            output.push('\n');
        }
        output.push_str(&format!("-----END {label}-----\n"));
        output
    }

    fn encode_short_test_sequence(body: &[u8]) -> Vec<u8> {
        let mut encoded = vec![
            0x30,
            u8::try_from(body.len()).expect("test sequence uses short DER length"),
        ];
        encoded.extend_from_slice(body);
        encoded
    }

    fn skip_wire_string(input: &[u8], offset: usize) -> usize {
        let length_end = offset.checked_add(4).expect("wire length field");
        let length = u32::from_be_bytes(
            input[offset..length_end]
                .try_into()
                .expect("wire length bytes"),
        );
        length_end
            .checked_add(usize::try_from(length).expect("u32 fits usize"))
            .filter(|end| *end <= input.len())
            .expect("bounded wire string")
    }

    fn remove_putty_header(document: &str, name: &str) -> String {
        let prefix = format!("{name}:");
        let mut output = document
            .lines()
            .filter(|line| !line.starts_with(&prefix))
            .collect::<Vec<_>>()
            .join("\n");
        output.push('\n');
        output
    }

    fn replace_putty_private_mac(document: &str, value: &str) -> String {
        let mut output = document
            .lines()
            .map(|line| {
                if line.starts_with("Private-MAC:") {
                    format!("Private-MAC: {value}")
                } else {
                    line.to_owned()
                }
            })
            .collect::<Vec<_>>()
            .join("\n");
        output.push('\n');
        output
    }

    fn replace_putty_header(document: &str, name: &str, value: &str) -> String {
        let prefix = format!("{name}:");
        let mut replaced = false;
        let mut output = document
            .lines()
            .map(|line| {
                if line.starts_with(&prefix) {
                    replaced = true;
                    format!("{name}: {value}")
                } else {
                    line.to_owned()
                }
            })
            .collect::<Vec<_>>()
            .join("\n");
        assert!(replaced, "fixture must contain {name}");
        output.push('\n');
        output
    }

    fn replace_putty_payload(document: &str, name: &str, payload: &[u8]) -> String {
        let mut lines = document.lines().map(str::to_owned).collect::<Vec<_>>();
        let prefix = format!("{name}: ");
        let header = lines
            .iter()
            .position(|line| line.starts_with(&prefix))
            .expect("PPK payload header");
        let count = lines[header]
            .strip_prefix(&prefix)
            .unwrap()
            .parse::<usize>()
            .unwrap();
        let encoded = Base64::encode_string(payload);
        let replacement = encoded
            .as_bytes()
            .chunks(64)
            .map(|line| std::str::from_utf8(line).unwrap().to_owned())
            .collect::<Vec<_>>();
        lines[header] = format!("{prefix}{}", replacement.len());
        lines.splice(header + 1..header + 1 + count, replacement);
        lines.join("\n") + "\n"
    }

    fn corrupt_putty_private_base64_tail(document: &str) -> String {
        let mut lines = document.lines().map(str::to_owned).collect::<Vec<_>>();
        let header = lines
            .iter()
            .position(|line| line.starts_with("Private-Lines: "))
            .expect("PuTTY private payload header");
        let count = lines[header]
            .strip_prefix("Private-Lines: ")
            .and_then(|value| value.parse::<usize>().ok())
            .expect("PuTTY private payload count");
        let tail = lines
            .get_mut(header + count)
            .expect("PuTTY private payload tail");
        tail.pop().expect("non-empty PuTTY private payload");
        tail.push('!');
        let mut output = lines.join("\n");
        output.push('\n');
        output
    }

    fn corrupt_armored_base64_tail(document: &str) -> String {
        let mut lines = document.lines().map(str::to_owned).collect::<Vec<_>>();
        let footer = lines
            .iter()
            .position(|line| line.starts_with("-----END "))
            .expect("armor footer");
        let tail = lines[..footer]
            .iter_mut()
            .rev()
            .find(|line| !line.is_empty() && !line.starts_with("-----"))
            .expect("armored Base64 body");
        tail.pop().expect("non-empty armored Base64 body");
        tail.push('!');
        let mut output = lines.join("\n");
        output.push('\n');
        output
    }

    fn replace_putty_comment_and_mac(
        document: &str,
        passphrase: Option<&[u8]>,
        comment: &str,
        sshj_encoding: bool,
    ) -> String {
        let parsed = PuttyDocument::parse(document.as_bytes()).expect("valid PuTTY fixture");
        let public_key = parsed.decode_public().expect("valid public payload");
        let PuttyPrivatePayload {
            private_key,
            mac_key,
        } = parsed.private_payload(passphrase).unwrap();
        let comment_bytes = if sshj_encoding {
            comment
                .encode_utf16()
                .map(|unit| unit.to_le_bytes()[0])
                .collect::<Vec<_>>()
        } else {
            comment.as_bytes().to_vec()
        };
        let mac = putty_mac(
            parsed.mac_algorithm(),
            &mac_key,
            parsed.algorithm,
            parsed.encryption,
            &comment_bytes,
            &public_key,
            &private_key,
        )
        .unwrap();

        let with_comment = replace_putty_header(document, "Comment", comment);
        replace_putty_private_mac(&with_comment, &encode_test_hex(&mac))
    }

    fn encode_test_hex(value: &[u8]) -> String {
        const HEX: &[u8; 16] = b"0123456789abcdef";

        let mut output = String::with_capacity(value.len() * 2);
        for byte in value {
            output.push(char::from(HEX[usize::from(byte >> 4)]));
            output.push(char::from(HEX[usize::from(byte & 0x0f)]));
        }
        output
    }
}
