//! SSH key generation, persistence, formatting, and agent signing.
//!
//! OpenSSH structures are handled by the reviewed vendored `ssh-key` crate.
//! Private RSA operations are delegated to the AWS-LC-only sensitive backend;
//! the RustCrypto RSA implementation is never enabled in this production graph.

use aws_lc_rs::{
    rand::{SecureRandom, SystemRandom},
    signature::{Ed25519KeyPair, KeyPair},
};
use base64ct::{Base64, Encoding};
use keyguard_crypto_sensitive::{
    RsaCrtComponents, RsaPrivateComponents, RsaSignatureHash, SensitiveRsaError,
    generate_rsa_pkcs1_der, sign_rsa_pkcs1_v1_5,
};
use pkcs1::RsaPrivateKey;
use pkcs8::{
    ObjectIdentifier, PrivateKeyInfo,
    der::{Decode as _, asn1::OctetStringRef},
};
use prost::Message;
use ssh_key::{
    Mpint, PrivateKey, PublicKey,
    private::{Ed25519Keypair, Ed25519PrivateKey, KeypairData},
    public::{Ed25519PublicKey, KeyData, RsaPublicKey},
};
use zeroize::{Zeroize, Zeroizing};

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    primitives::PrimitiveError,
    protocol::{
        SshFormattedPrivateKey, SshKeyDescription, SshKeyMaterial, SshKeyType, SshSignature,
    },
};

const MAX_KEY_BYTES: usize = 64 * 1024;
const MAX_SIGN_DATA_BYTES: usize = 1024 * 1024;
const RSA_ENCRYPTION_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.113549.1.1.1");
const ED25519_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.3.101.112");

pub(crate) fn generate(key_type: SshKeyType, rsa_bits: u32) -> Result<Vec<u8>, PrimitiveError> {
    let material = match key_type {
        SshKeyType::Rsa => generate_rsa(rsa_bits)?,
        SshKeyType::Ed25519 if rsa_bits == 0 => generate_ed25519()?,
        SshKeyType::Ed25519 | SshKeyType::Unspecified => {
            return Err(PrimitiveError::InvalidArgument);
        }
    };
    Ok(material.encode_to_vec())
}

pub(crate) fn parse(
    private_key_pem: String,
    public_key_openssh: String,
) -> Result<Vec<u8>, PrimitiveError> {
    // The dispatcher transfers ownership out of the protobuf request, so the
    // local copy must retain zeroizing ownership on every early-return path.
    let private_key_pem = Zeroizing::new(private_key_pem);
    bound_text(&private_key_pem)?;
    bound_text(&public_key_openssh)?;
    let private_key = decode_private_pem(&private_key_pem)?;
    validate_private_key(&private_key)?;

    let parsed_public = PublicKey::from_openssh(&public_key_openssh)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let key_type = match parsed_public.key_data() {
        KeyData::Rsa(_) => SshKeyType::Rsa,
        KeyData::Ed25519(_) => SshKeyType::Ed25519,
        _ => return Err(PrimitiveError::InvalidArgument),
    };
    let public_key = parsed_public
        .to_bytes()
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    if public_key.len() > MAX_KEY_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }

    Ok(SshKeyMaterial {
        r#type: key_type as i32,
        private_key: private_key.to_vec(),
        public_key,
    }
    .encode_to_vec())
}

pub(crate) fn describe(
    key_type: SshKeyType,
    private_key: Vec<u8>,
    public_key: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let private_key = Zeroizing::new(private_key);
    bound_raw_key(&private_key)?;
    bound_raw_key(&public_key)?;
    let mut private_key_pem = format_private_key_text(key_type, &private_key)?;
    let public_key_openssh = match format_public_key_text(key_type, &public_key) {
        Ok(value) => value,
        Err(error) => {
            private_key_pem.zeroize();
            return Err(error);
        }
    };
    let description = SshKeyDescription {
        private_key_pem,
        public_key_openssh,
        private_fingerprint: fingerprint(&private_key),
        public_fingerprint: fingerprint(&public_key),
    };
    Ok(description.encode_to_vec())
}

pub(crate) fn private_key_rsa_bits(private_key: Vec<u8>) -> i32 {
    let private_key = Zeroizing::new(private_key);
    if private_key.len() > MAX_KEY_BYTES {
        return 0;
    }
    rsa_parts_from_openssh(&private_key)
        .or_else(|| rsa_parts_from_der(&private_key).map(|value| value.parts))
        .and_then(|parts| modulus_bits(&parts.modulus))
        .and_then(|bits| i32::try_from(bits).ok())
        .unwrap_or(0)
}

pub(crate) fn format_private_key(
    key_type: SshKeyType,
    private_key: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let private_key = Zeroizing::new(private_key);
    bound_raw_key(&private_key)?;
    Ok(SshFormattedPrivateKey {
        value: format_private_key_text(key_type, &private_key)?,
    }
    .encode_to_vec())
}

pub(crate) fn sign(
    private_key_pem: String,
    public_key_openssh: Option<String>,
    data: Vec<u8>,
    flags: u32,
) -> Result<Vec<u8>, PrimitiveError> {
    // See `parse`: `mem::take` empties the request before this function runs.
    // Keep the moved PEM text under zeroizing ownership as well.
    let private_key_pem = Zeroizing::new(private_key_pem);
    let data = Zeroizing::new(data);
    bound_text(&private_key_pem)?;
    if let Some(public_key) = public_key_openssh.as_deref() {
        bound_text(public_key)?;
    }
    if data.len() > MAX_SIGN_DATA_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }

    let private_key = decode_private_pem(&private_key_pem)?;
    let signature = if let Ok(open_ssh_key) = PrivateKey::from_bytes(&private_key) {
        match open_ssh_key.key_data() {
            KeypairData::Ed25519(keypair) => sign_ed25519(keypair, &data)?,
            KeypairData::Rsa(keypair) => {
                let parts = RsaParts {
                    modulus: positive_mpint(&keypair.public.n)?,
                    public_exponent: positive_mpint(&keypair.public.e)?,
                    private_exponent: positive_mpint(&keypair.private.d)?,
                    crt: None,
                };
                sign_rsa(parts, public_key_openssh.as_deref(), &data, flags)?
            }
            _ => return Err(PrimitiveError::InvalidArgument),
        }
    } else {
        let parts = rsa_parts_from_der(&private_key)
            .ok_or(PrimitiveError::InvalidArgument)?
            .parts;
        sign_rsa(parts, public_key_openssh.as_deref(), &data, flags)?
    };
    Ok(signature.encode_to_vec())
}

fn generate_rsa(bits: u32) -> Result<SshKeyMaterial, PrimitiveError> {
    if !matches!(bits, 1024 | 2048 | 3072 | 4096) {
        return Err(PrimitiveError::InvalidArgument);
    }
    let private_key = generate_rsa_pkcs1_der(bits).map_err(sensitive_rsa_error)?;
    if private_key.len() > MAX_KEY_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }
    let parts = rsa_parts_from_pkcs1(&private_key).ok_or(PrimitiveError::CryptoFailure)?;
    let public_key = encode_rsa_public(&parts.modulus, &parts.public_exponent)?;
    Ok(SshKeyMaterial {
        r#type: SshKeyType::Rsa as i32,
        private_key: private_key.to_vec(),
        public_key,
    })
}

fn generate_ed25519() -> Result<SshKeyMaterial, PrimitiveError> {
    let mut seed = Zeroizing::new([0_u8; 32]);
    SystemRandom::new()
        .fill(seed.as_mut())
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    encode_ed25519_material(&seed, random_u32()?)
}

pub(super) fn encode_ed25519_material(
    seed: &[u8; 32],
    checkint: u32,
) -> Result<SshKeyMaterial, PrimitiveError> {
    let aws_keypair =
        Ed25519KeyPair::from_seed_unchecked(seed).map_err(|_| PrimitiveError::CryptoFailure)?;
    let public_bytes: [u8; 32] = aws_keypair
        .public_key()
        .as_ref()
        .try_into()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let ssh_keypair = Ed25519Keypair {
        public: Ed25519PublicKey(public_bytes),
        private: Ed25519PrivateKey::from_bytes(seed),
    };
    let private = PrivateKey::new_with_checkint(ssh_keypair.into(), "", checkint)
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let public_key = private
        .public_key()
        .to_bytes()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let private_key = private
        .to_bytes()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    Ok(SshKeyMaterial {
        r#type: SshKeyType::Ed25519 as i32,
        private_key: private_key.to_vec(),
        public_key,
    })
}

fn sign_ed25519(keypair: &Ed25519Keypair, data: &[u8]) -> Result<SshSignature, PrimitiveError> {
    let key =
        Ed25519KeyPair::from_seed_and_public_key(keypair.private.as_ref(), keypair.public.as_ref())
            .map_err(|_| PrimitiveError::InvalidArgument)?;
    let signature = key
        .try_sign(data)
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    Ok(SshSignature {
        algorithm: "ssh-ed25519".to_owned(),
        signature: signature.as_ref().to_vec(),
    })
}

fn sign_rsa(
    mut parts: RsaParts,
    public_key_openssh: Option<&str>,
    data: &[u8],
    flags: u32,
) -> Result<SshSignature, PrimitiveError> {
    if parts.public_exponent.iter().all(|byte| *byte == 0) {
        let public_key = public_key_openssh.ok_or(PrimitiveError::InvalidArgument)?;
        let parsed =
            PublicKey::from_openssh(public_key).map_err(|_| PrimitiveError::InvalidArgument)?;
        let rsa = parsed
            .key_data()
            .rsa()
            .ok_or(PrimitiveError::InvalidArgument)?;
        let public_modulus = positive_mpint(&rsa.n)?;
        if strip_leading_zeroes(&public_modulus) != strip_leading_zeroes(&parts.modulus) {
            return Err(PrimitiveError::InvalidArgument);
        }
        parts.public_exponent = positive_mpint(&rsa.e)?;
    }
    let (algorithm, hash) = if flags & 0x04 != 0 {
        ("rsa-sha2-512", RsaSignatureHash::Sha512)
    } else if flags & 0x02 != 0 {
        ("rsa-sha2-256", RsaSignatureHash::Sha256)
    } else {
        ("ssh-rsa", RsaSignatureHash::Sha1)
    };

    let crt = parts.crt.take().map(|mut crt| {
        RsaCrtComponents::new(
            std::mem::take(&mut crt.prime_p),
            std::mem::take(&mut crt.prime_q),
            std::mem::take(&mut crt.exponent_p),
            std::mem::take(&mut crt.exponent_q),
            std::mem::take(&mut crt.coefficient),
        )
    });
    let components = RsaPrivateComponents::new(
        std::mem::take(&mut parts.modulus),
        std::mem::take(&mut parts.public_exponent),
        std::mem::take(&mut parts.private_exponent),
        crt,
    );
    let signature = sign_rsa_pkcs1_v1_5(&components, hash, data).map_err(sensitive_rsa_error)?;
    Ok(SshSignature {
        algorithm: algorithm.to_owned(),
        signature,
    })
}

fn validate_private_key(private_key: &[u8]) -> Result<(), PrimitiveError> {
    if let Ok(key) = PrivateKey::from_bytes(private_key) {
        return match key.key_data() {
            KeypairData::Rsa(_) | KeypairData::Ed25519(_) => Ok(()),
            _ => Err(PrimitiveError::InvalidArgument),
        };
    }
    if rsa_parts_from_der(private_key).is_some() || is_ed25519_pkcs8(private_key) {
        Ok(())
    } else {
        Err(PrimitiveError::InvalidArgument)
    }
}

fn rsa_parts_from_openssh(private_key: &[u8]) -> Option<RsaParts> {
    let key = PrivateKey::from_bytes(private_key).ok()?;
    let keypair = key.key_data().rsa()?;
    Some(RsaParts {
        modulus: positive_mpint(&keypair.public.n).ok()?,
        public_exponent: positive_mpint(&keypair.public.e).ok()?,
        private_exponent: positive_mpint(&keypair.private.d).ok()?,
        crt: None,
    })
}

fn rsa_parts_from_der(private_key: &[u8]) -> Option<ParsedRsaParts> {
    if let Ok(info) = PrivateKeyInfo::from_der(private_key) {
        if info.algorithm.oid != RSA_ENCRYPTION_OID {
            return None;
        }
        let parts = rsa_parts_from_pkcs1(info.private_key)?;
        return Some(ParsedRsaParts { parts });
    }
    rsa_parts_from_pkcs1(private_key).map(|parts| ParsedRsaParts { parts })
}

fn rsa_parts_from_pkcs1(private_key: &[u8]) -> Option<RsaParts> {
    let key = RsaPrivateKey::from_der(private_key).ok()?;
    if key.other_prime_infos.is_some() {
        return None;
    }
    let crt_values = [
        key.prime1.as_bytes(),
        key.prime2.as_bytes(),
        key.exponent1.as_bytes(),
        key.exponent2.as_bytes(),
        key.coefficient.as_bytes(),
    ];
    let crt = crt_values
        .iter()
        .all(|value| value.iter().any(|byte| *byte != 0))
        .then(|| RsaCrtParts {
            prime_p: key.prime1.as_bytes().to_vec(),
            prime_q: key.prime2.as_bytes().to_vec(),
            exponent_p: key.exponent1.as_bytes().to_vec(),
            exponent_q: key.exponent2.as_bytes().to_vec(),
            coefficient: key.coefficient.as_bytes().to_vec(),
        });
    Some(RsaParts {
        modulus: key.modulus.as_bytes().to_vec(),
        public_exponent: key.public_exponent.as_bytes().to_vec(),
        private_exponent: key.private_exponent.as_bytes().to_vec(),
        crt,
    })
}

fn is_ed25519_pkcs8(private_key: &[u8]) -> bool {
    let Ok(info) = PrivateKeyInfo::from_der(private_key) else {
        return false;
    };
    if info.algorithm.oid != ED25519_OID || info.algorithm.parameters.is_some() {
        return false;
    }

    // RFC 8410 wraps the 32-byte CurvePrivateKey in a second OCTET STRING.
    // `from_der` consumes the complete inner document, rejecting trailing or
    // malformed data instead of merely accepting a matching algorithm OID.
    let Ok(seed) = OctetStringRef::from_der(info.private_key) else {
        return false;
    };
    seed.as_bytes().len() == 32
        && info
            .public_key
            .is_none_or(|public_key| public_key.len() == 32)
}

fn is_supported_pkcs8(private_key: &[u8]) -> bool {
    let Ok(info) = PrivateKeyInfo::from_der(private_key) else {
        return false;
    };
    match info.algorithm.oid {
        RSA_ENCRYPTION_OID => rsa_parts_from_pkcs1(info.private_key).is_some(),
        ED25519_OID => is_ed25519_pkcs8(private_key),
        _ => false,
    }
}

pub(super) fn encode_rsa_public(
    modulus: &[u8],
    exponent: &[u8],
) -> Result<Vec<u8>, PrimitiveError> {
    let key = PublicKey::new(
        KeyData::Rsa(RsaPublicKey {
            e: Mpint::from_positive_bytes(exponent).map_err(|_| PrimitiveError::CryptoFailure)?,
            n: Mpint::from_positive_bytes(modulus).map_err(|_| PrimitiveError::CryptoFailure)?,
        }),
        "",
    );
    key.to_bytes().map_err(|_| PrimitiveError::CryptoFailure)
}

fn positive_mpint(value: &Mpint) -> Result<Vec<u8>, PrimitiveError> {
    value
        .as_positive_bytes()
        .filter(|bytes| !bytes.is_empty())
        .map(ToOwned::to_owned)
        .ok_or(PrimitiveError::InvalidArgument)
}

fn format_private_key_text(
    key_type: SshKeyType,
    private_key: &[u8],
) -> Result<String, PrimitiveError> {
    let (label, width) = match key_type {
        SshKeyType::Ed25519 => ("OPENSSH PRIVATE KEY", 70),
        SshKeyType::Rsa => {
            // The historical formatter selected the generic PKCS#8 label from
            // the private document independently of the persisted public type.
            let pkcs8 = is_supported_pkcs8(private_key);
            if pkcs8 {
                ("PRIVATE KEY", 64)
            } else {
                ("RSA PRIVATE KEY", 64)
            }
        }
        SshKeyType::Unspecified => return Err(PrimitiveError::InvalidArgument),
    };
    let encoded = Zeroizing::new(Base64::encode_string(private_key));
    let mut output = String::with_capacity(encoded.len() + 96);
    output.push_str("-----BEGIN ");
    output.push_str(label);
    output.push_str("-----\n");
    for line in encoded.as_bytes().chunks(width) {
        let line = std::str::from_utf8(line).map_err(|_| PrimitiveError::CryptoFailure)?;
        output.push_str(line);
        output.push('\n');
    }
    output.push_str("-----END ");
    output.push_str(label);
    output.push_str("-----\n");
    if output.len() > MAX_CONTROL_ENVELOPE_BYTES {
        output.zeroize();
        return Err(PrimitiveError::ResourceLimit);
    }
    Ok(output)
}

fn format_public_key_text(
    key_type: SshKeyType,
    public_key: &[u8],
) -> Result<String, PrimitiveError> {
    let prefix = match key_type {
        SshKeyType::Rsa => "ssh-rsa",
        SshKeyType::Ed25519 => "ssh-ed25519",
        SshKeyType::Unspecified => return Err(PrimitiveError::InvalidArgument),
    };
    Ok(format!("{prefix} {}", Base64::encode_string(public_key)))
}

fn fingerprint(value: &[u8]) -> String {
    let digest = aws_lc_rs::digest::digest(&aws_lc_rs::digest::SHA256, value);
    format!("SHA256:{}", Base64::encode_string(digest.as_ref()))
}

fn decode_private_pem(value: &str) -> Result<Zeroizing<Vec<u8>>, PrimitiveError> {
    let mut encoded = Zeroizing::new(String::new());
    for line in value.lines() {
        let line = line.trim();
        if line.is_empty() || is_private_key_boundary(line) {
            continue;
        }
        encoded.push_str(line);
    }
    if encoded.is_empty() || encoded.len() > MAX_KEY_BYTES.saturating_mul(2) {
        return Err(PrimitiveError::InvalidArgument);
    }
    let decoded =
        Zeroizing::new(Base64::decode_vec(&encoded).map_err(|_| PrimitiveError::InvalidArgument)?);
    if decoded.is_empty() || decoded.len() > MAX_KEY_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }
    Ok(decoded)
}

fn is_private_key_boundary(line: &str) -> bool {
    let leading = line.bytes().take_while(|byte| *byte == b'-').count();
    let trailing = line.bytes().rev().take_while(|byte| *byte == b'-').count();
    if !(1..=5).contains(&leading)
        || !(1..=5).contains(&trailing)
        || leading + trailing >= line.len()
    {
        return false;
    }
    matches!(
        &line[leading..line.len() - trailing],
        "BEGIN PRIVATE KEY"
            | "END PRIVATE KEY"
            | "BEGIN RSA PRIVATE KEY"
            | "END RSA PRIVATE KEY"
            | "BEGIN OPENSSH PRIVATE KEY"
            | "END OPENSSH PRIVATE KEY"
    )
}

pub(super) fn random_u32() -> Result<u32, PrimitiveError> {
    let mut bytes = [0_u8; 4];
    SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    Ok(u32::from_be_bytes(bytes))
}

fn modulus_bits(modulus: &[u8]) -> Option<u32> {
    let modulus = strip_leading_zeroes(modulus);
    let first = *modulus.first()?;
    let remaining = u32::try_from(modulus.len().checked_sub(1)?).ok()?;
    remaining
        .checked_mul(8)?
        .checked_add(8_u32.checked_sub(first.leading_zeros())?)
}

fn strip_leading_zeroes(mut value: &[u8]) -> &[u8] {
    while value.first() == Some(&0) {
        value = &value[1..];
    }
    value
}

fn bound_text(value: &str) -> Result<(), PrimitiveError> {
    if value.is_empty() {
        Err(PrimitiveError::InvalidArgument)
    } else if value.len() > MAX_KEY_BYTES {
        Err(PrimitiveError::ResourceLimit)
    } else {
        Ok(())
    }
}

fn bound_raw_key(value: &[u8]) -> Result<(), PrimitiveError> {
    if value.is_empty() {
        Err(PrimitiveError::InvalidArgument)
    } else if value.len() > MAX_KEY_BYTES {
        Err(PrimitiveError::ResourceLimit)
    } else {
        Ok(())
    }
}

fn sensitive_rsa_error(error: SensitiveRsaError) -> PrimitiveError {
    match error {
        SensitiveRsaError::InvalidKeySize | SensitiveRsaError::InvalidKey => {
            PrimitiveError::InvalidArgument
        }
        SensitiveRsaError::AllocationFailure | SensitiveRsaError::BackendFailure => {
            PrimitiveError::CryptoFailure
        }
    }
}

struct ParsedRsaParts {
    parts: RsaParts,
}

struct RsaParts {
    modulus: Vec<u8>,
    public_exponent: Vec<u8>,
    private_exponent: Vec<u8>,
    crt: Option<RsaCrtParts>,
}

impl Drop for RsaParts {
    fn drop(&mut self) {
        self.modulus.zeroize();
        self.public_exponent.zeroize();
        self.private_exponent.zeroize();
    }
}

struct RsaCrtParts {
    prime_p: Vec<u8>,
    prime_q: Vec<u8>,
    exponent_p: Vec<u8>,
    exponent_q: Vec<u8>,
    coefficient: Vec<u8>,
}

impl Drop for RsaCrtParts {
    fn drop(&mut self) {
        self.prime_p.zeroize();
        self.prime_q.zeroize();
        self.exponent_p.zeroize();
        self.exponent_q.zeroize();
        self.coefficient.zeroize();
    }
}

#[cfg(test)]
mod tests {
    use aws_lc_rs::signature::{
        ED25519, RSA_PKCS1_1024_8192_SHA1_FOR_LEGACY_USE_ONLY,
        RSA_PKCS1_1024_8192_SHA256_FOR_LEGACY_USE_ONLY,
        RSA_PKCS1_1024_8192_SHA512_FOR_LEGACY_USE_ONLY, RsaPublicKeyComponents, UnparsedPublicKey,
    };
    use pkcs8::{
        AlgorithmIdentifierRef,
        der::{
            Encode as _,
            asn1::{AnyRef, UintRef},
        },
    };

    use super::*;

    #[test]
    fn generated_ed25519_round_trips_formats_and_signs() {
        let material = material(generate(SshKeyType::Ed25519, 0).expect("Ed25519 generation"));
        assert_eq!(material.r#type, SshKeyType::Ed25519 as i32);

        let private = PrivateKey::from_bytes(&material.private_key).expect("OpenSSH private key");
        let public = PublicKey::from_bytes(&material.public_key).expect("OpenSSH public key");
        assert_eq!(
            private.public_key().to_bytes().expect("public bytes"),
            material.public_key
        );
        let public_bytes = public
            .key_data()
            .ed25519()
            .expect("Ed25519 public key")
            .as_ref();

        let pem = format_private_key_text(SshKeyType::Ed25519, &material.private_key)
            .expect("private PEM");
        assert!(pem.starts_with("-----BEGIN OPENSSH PRIVATE KEY-----\n"));
        assert!(pem.ends_with("-----END OPENSSH PRIVATE KEY-----\n"));
        assert!(
            pem.lines()
                .skip(1)
                .take_while(|line| !line.starts_with("-----END"))
                .all(|line| line.len() <= 70)
        );

        let signed =
            signature(sign(pem, None, b"ssh-key-ed25519".to_vec(), 0).expect("Ed25519 signing"));
        assert_eq!(signed.algorithm, "ssh-ed25519");
        UnparsedPublicKey::new(&ED25519, public_bytes)
            .verify(b"ssh-key-ed25519", &signed.signature)
            .expect("AWS-LC must independently verify the signature");

        let described = SshKeyDescription::decode(
            describe(
                SshKeyType::Ed25519,
                material.private_key.clone(),
                material.public_key.clone(),
            )
            .expect("description")
            .as_slice(),
        )
        .expect("description payload");
        assert!(described.private_fingerprint.starts_with("SHA256:"));
        assert!(described.private_fingerprint.ends_with('='));
        assert!(described.public_fingerprint.ends_with('='));
    }

    #[test]
    fn deterministic_ed25519_encoding_matches_the_existing_openssh_fixture() {
        let seed: [u8; 32] =
            decode_hex("66c9549f97399d21e3d8ae3b725e03aedfba1384f54bfd643c732eac3f94e4fc")
                .try_into()
                .expect("32-byte Ed25519 seed");
        let material =
            encode_ed25519_material(&seed, 0x03b5_b4fe).expect("deterministic Ed25519 encoding");

        assert_eq!(
            Base64::encode_string(&material.private_key),
            concat!(
                "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQyNTUxOQ",
                "AAACC44rmmfQf58bmD/3xQqefZyT90tEccY5NVWANfBS11PwAAAIgDtbT+A7W0/gAAAAtzc2gtZWQ",
                "yNTUxOQAAACC44rmmfQf58bmD/3xQqefZyT90tEccY5NVWANfBS11PwAAAEBmyVSflzmdIePYrjty",
                "XgOu37oThPVL/WQ8cy6sP5Tk/LjiuaZ9B/nxuYP/fFCp59nJP3S0Rxxjk1VYA18FLXU/AAAAAAECAwQF",
            ),
        );
        assert_eq!(
            Base64::encode_string(&material.public_key),
            "AAAAC3NzaC1lZDI1NTE5AAAAILjiuaZ9B/nxuYP/fFCp59nJP3S0Rxxjk1VYA18FLXU/",
        );
    }

    #[test]
    fn generated_rsa_1024_retains_exponent_size_and_flag_precedence() {
        let material = material(generate(SshKeyType::Rsa, 1024).expect("RSA-1024 generation"));
        assert_eq!(private_key_rsa_bits(material.private_key.clone()), 1024);
        let public = PublicKey::from_bytes(&material.public_key).expect("RSA public key");
        let rsa = public.key_data().rsa().expect("RSA public components");
        assert_eq!(rsa.e.as_positive_bytes(), Some(&[0x01, 0x00, 0x01][..]));

        let pem = format_private_key_text(SshKeyType::Rsa, &material.private_key)
            .expect("RSA private PEM");
        assert!(pem.starts_with("-----BEGIN RSA PRIVATE KEY-----\n"));
        assert!(pem.ends_with("-----END RSA PRIVATE KEY-----\n"));
        assert!(
            pem.lines()
                .skip(1)
                .take_while(|line| !line.starts_with("-----END"))
                .all(|line| line.len() <= 64)
        );
        let verifier = RsaPublicKeyComponents {
            n: rsa.n.as_positive_bytes().expect("positive modulus"),
            e: rsa.e.as_positive_bytes().expect("positive exponent"),
        };
        let cases = [
            (0, "ssh-rsa", &RSA_PKCS1_1024_8192_SHA1_FOR_LEGACY_USE_ONLY),
            (
                0x02,
                "rsa-sha2-256",
                &RSA_PKCS1_1024_8192_SHA256_FOR_LEGACY_USE_ONLY,
            ),
            (
                0x04,
                "rsa-sha2-512",
                &RSA_PKCS1_1024_8192_SHA512_FOR_LEGACY_USE_ONLY,
            ),
            (
                0x06,
                "rsa-sha2-512",
                &RSA_PKCS1_1024_8192_SHA512_FOR_LEGACY_USE_ONLY,
            ),
        ];
        for (flags, algorithm, parameters) in cases {
            let signed = signature(
                sign(pem.clone(), None, b"ssh-key-rsa".to_vec(), flags).expect("RSA signing"),
            );
            assert_eq!(signed.algorithm, algorithm);
            assert_eq!(signed.signature.len(), 128);
            verifier
                .verify(parameters, b"ssh-key-rsa", &signed.signature)
                .expect("AWS-LC must independently verify the signature");
        }
    }

    #[test]
    fn incomplete_pkcs8_rsa_recovers_the_public_exponent_before_sensitive_operations() {
        let complete = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let incomplete = incomplete_pkcs8_rsa(&complete.private_key);
        let info = PrivateKeyInfo::from_der(&incomplete).expect("incomplete PKCS#8 parses");
        assert!(
            RsaPrivateKey::from_der(info.private_key).is_ok(),
            "incomplete PKCS#1 parses: {:?}",
            RsaPrivateKey::from_der(info.private_key).err(),
        );
        let pem =
            format_private_key_text(SshKeyType::Rsa, &incomplete).expect("incomplete PKCS#8 PEM");
        let public_text =
            format_public_key_text(SshKeyType::Rsa, &complete.public_key).expect("RSA public text");

        assert!(pem.starts_with("-----BEGIN PRIVATE KEY-----\n"));
        assert_eq!(private_key_rsa_bits(incomplete.clone()), 1024);
        assert!(parse(pem.clone(), public_text.clone()).is_ok());
        assert_eq!(
            sign(pem.clone(), None, b"incomplete-rsa".to_vec(), 0x02),
            Err(PrimitiveError::InvalidArgument),
        );

        let signed = signature(
            sign(
                pem.clone(),
                Some(public_text),
                b"incomplete-rsa".to_vec(),
                0x02,
            )
            .expect("n/e/d-only RSA signing"),
        );
        let public = PublicKey::from_bytes(&complete.public_key).expect("RSA public key");
        let rsa = public.key_data().rsa().expect("RSA public components");
        RsaPublicKeyComponents {
            n: rsa.n.as_positive_bytes().expect("positive modulus"),
            e: rsa.e.as_positive_bytes().expect("positive exponent"),
        }
        .verify(
            &RSA_PKCS1_1024_8192_SHA256_FOR_LEGACY_USE_ONLY,
            b"incomplete-rsa",
            &signed.signature,
        )
        .expect("reconstructed key signature must verify");

        let other = material(generate(SshKeyType::Rsa, 1024).expect("other RSA generation"));
        let other_public = format_public_key_text(SshKeyType::Rsa, &other.public_key)
            .expect("other RSA public text");
        assert_eq!(
            sign(pem, Some(other_public), b"incomplete-rsa".to_vec(), 0x02,),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn ed25519_pkcs8_requires_the_exact_rfc8410_private_key_shape() {
        let seed = [0x42_u8; 32];
        let valid = ed25519_pkcs8(&seed, None, None);
        assert!(is_ed25519_pkcs8(&valid));
        assert!(validate_private_key(&valid).is_ok());

        // Persisted public/private types were historically classified
        // independently, so a valid PKCS#8 document keeps the generic label.
        assert!(
            format_private_key_text(SshKeyType::Rsa, &valid)
                .expect("compatibility formatting")
                .starts_with("-----BEGIN PRIVATE KEY-----\n"),
        );

        let raw_seed_without_nested_octet_string = PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: ED25519_OID,
                parameters: None,
            },
            &seed,
        )
        .to_der()
        .expect("malformed Ed25519 PKCS#8 encoding");
        assert!(!is_ed25519_pkcs8(&raw_seed_without_nested_octet_string));

        let short_seed = [0x42_u8; 31];
        assert!(!is_ed25519_pkcs8(&ed25519_pkcs8(&short_seed, None, None,)));

        let mut inner_with_trailing = OctetStringRef::new(&seed)
            .expect("nested OCTET STRING")
            .to_der()
            .expect("nested OCTET STRING DER");
        inner_with_trailing.push(0);
        let trailing = PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: ED25519_OID,
                parameters: None,
            },
            &inner_with_trailing,
        )
        .to_der()
        .expect("trailing-inner PKCS#8 encoding");
        assert!(!is_ed25519_pkcs8(&trailing));

        assert!(!is_ed25519_pkcs8(&ed25519_pkcs8(
            &seed,
            Some(AnyRef::NULL),
            None,
        )));
        assert!(!is_ed25519_pkcs8(&ed25519_pkcs8(
            &seed,
            None,
            Some(&[0x24; 31]),
        )));
    }

    #[test]
    fn persisted_parse_preserves_independently_valid_mismatched_inputs() {
        let first = material(generate(SshKeyType::Ed25519, 0).expect("first Ed25519 key"));
        let second = material(generate(SshKeyType::Ed25519, 0).expect("second Ed25519 key"));
        let private_pem =
            format_private_key_text(SshKeyType::Ed25519, &first.private_key).expect("private PEM");
        let public_text =
            format_public_key_text(SshKeyType::Ed25519, &second.public_key).expect("public text");

        let parsed = material(parse(private_pem, public_text).expect("compatibility parse"));
        assert_eq!(parsed.private_key, first.private_key);
        assert_eq!(parsed.public_key, second.public_key);
    }

    #[test]
    fn persisted_parsing_rejects_trailing_documents_and_unrelated_pem_labels() {
        let ed = material(generate(SshKeyType::Ed25519, 0).expect("Ed25519 generation"));
        let ed_public = format_public_key_text(SshKeyType::Ed25519, &ed.public_key)
            .expect("Ed25519 public text");
        let mut trailing_private = ed.private_key.clone();
        trailing_private.push(0);
        let trailing_pem = format_private_key_text(SshKeyType::Ed25519, &trailing_private)
            .expect("trailing private PEM");
        assert_eq!(
            parse(trailing_pem, ed_public.clone()),
            Err(PrimitiveError::InvalidArgument),
        );

        let mut trailing_public = ed.public_key.clone();
        trailing_public.push(0);
        let trailing_public_text = format_public_key_text(SshKeyType::Ed25519, &trailing_public)
            .expect("trailing public text");
        let ed_pem = format_private_key_text(SshKeyType::Ed25519, &ed.private_key)
            .expect("Ed25519 private PEM");
        assert_eq!(
            parse(ed_pem.clone(), trailing_public_text),
            Err(PrimitiveError::InvalidArgument),
        );

        let mislabeled = ed_pem.replace("OPENSSH PRIVATE KEY", "CERTIFICATE");
        assert_eq!(
            parse(mislabeled, ed_public),
            Err(PrimitiveError::InvalidArgument),
        );

        let rsa = material(generate(SshKeyType::Rsa, 1024).expect("RSA generation"));
        let mut trailing_der = rsa.private_key.clone();
        trailing_der.push(0);
        assert_eq!(private_key_rsa_bits(trailing_der.clone()), 0);
        assert_eq!(
            validate_private_key(&trailing_der),
            Err(PrimitiveError::InvalidArgument),
        );
    }

    #[test]
    fn malformed_and_oversized_inputs_fail_before_crypto_work() {
        assert_eq!(
            parse("not a key".to_owned(), "not a key".to_owned()),
            Err(PrimitiveError::InvalidArgument),
        );
        assert_eq!(
            sign(
                "not a key".to_owned(),
                None,
                vec![0_u8; MAX_SIGN_DATA_BYTES + 1],
                0,
            ),
            Err(PrimitiveError::ResourceLimit),
        );
        assert_eq!(private_key_rsa_bits(vec![0xa5; MAX_KEY_BYTES + 1]), 0);
    }

    fn material(payload: Vec<u8>) -> SshKeyMaterial {
        SshKeyMaterial::decode(payload.as_slice()).expect("SSH key material payload")
    }

    fn signature(payload: Vec<u8>) -> SshSignature {
        SshSignature::decode(payload.as_slice()).expect("SSH signature payload")
    }

    fn incomplete_pkcs8_rsa(complete_pkcs1: &[u8]) -> Vec<u8> {
        let complete = RsaPrivateKey::from_der(complete_pkcs1).expect("complete PKCS#1 key");
        let zero_bytes = [0_u8];
        let zero = UintRef::new(&zero_bytes).expect("zero INTEGER");
        let incomplete = RsaPrivateKey {
            modulus: complete.modulus,
            public_exponent: zero,
            private_exponent: complete.private_exponent,
            prime1: zero,
            prime2: zero,
            exponent1: zero,
            exponent2: zero,
            coefficient: zero,
            other_prime_infos: None,
        }
        .to_der()
        .expect("incomplete PKCS#1 document");
        PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: RSA_ENCRYPTION_OID,
                parameters: Some(AnyRef::NULL),
            },
            &incomplete,
        )
        .to_der()
        .expect("incomplete PKCS#8 document")
    }

    fn ed25519_pkcs8(
        seed: &[u8],
        parameters: Option<AnyRef<'_>>,
        public_key: Option<&[u8]>,
    ) -> Vec<u8> {
        let inner = OctetStringRef::new(seed)
            .expect("nested OCTET STRING")
            .to_der()
            .expect("nested OCTET STRING DER");
        let mut info = PrivateKeyInfo::new(
            AlgorithmIdentifierRef {
                oid: ED25519_OID,
                parameters,
            },
            &inner,
        );
        info.public_key = public_key;
        info.to_der().expect("Ed25519 PKCS#8 document")
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        value
            .as_bytes()
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| {
                let pair = std::str::from_utf8(pair).expect("test hex is UTF-8");
                u8::from_str_radix(pair, 16).expect("test hex decodes")
            })
            .collect()
    }
}
