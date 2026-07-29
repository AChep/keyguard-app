//! WebAuthn passkey key generation, PKCS#8 validation, and ES256 signing.

use aws_lc_rs::rand::{SecureRandom, SystemRandom};
use p256::{
    PublicKey, SecretKey,
    ecdsa::{Signature, SigningKey, signature::Signer as _},
    elliptic_curve::sec1::ToEncodedPoint as _,
    pkcs8::{EncodePrivateKey as _, EncodePublicKey as _},
};
use pkcs8::{
    ObjectIdentifier, PrivateKeyInfo,
    der::{Decode as _, asn1::ObjectIdentifier as DerObjectIdentifier},
};
use prost::Message;
use sec1::{EcParameters, EcPrivateKey};
use zeroize::Zeroizing;

use crate::{
    primitives::PrimitiveError,
    protocol::{
        PasskeyAlgorithm, PasskeyKeyError, PasskeyKeyInspection, PasskeyKeyMaterial,
        PasskeyKeyProfile, PasskeySignResult, PasskeySignature,
    },
};

pub(crate) const MAX_PRIVATE_KEY_BYTES: usize = 4 * 1024;
pub(crate) const MAX_SIGN_DATA_BYTES: usize = 64 * 1024;

const PRIVATE_SCALAR_BYTES: usize = 32;
const EC_PUBLIC_KEY_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.10045.2.1");
const P256_OID: ObjectIdentifier = ObjectIdentifier::new_unwrap("1.2.840.10045.3.1.7");

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum KeyIssue {
    Malformed,
    Unsupported,
    ResourceLimit,
}

pub(crate) fn generate(algorithm: i32) -> Result<Vec<u8>, PrimitiveError> {
    require_es256(algorithm)?;

    let rng = SystemRandom::new();
    let mut scalar = Zeroizing::new([0_u8; PRIVATE_SCALAR_BYTES]);
    let secret_key = loop {
        rng.fill(scalar.as_mut())
            .map_err(|_| PrimitiveError::CryptoFailure)?;
        if let Ok(secret_key) = SecretKey::from_slice(scalar.as_ref()) {
            break secret_key;
        }
    };

    encode_material(&secret_key).map(|material| material.encode_to_vec())
}

pub(crate) fn inspect(private_key_pkcs8: Vec<u8>) -> Result<Vec<u8>, PrimitiveError> {
    let private_key_pkcs8 = Zeroizing::new(private_key_pkcs8);
    let result = match parse_private_key(&private_key_pkcs8) {
        Ok(secret_key) => PasskeyKeyInspection {
            key_material: Some(encode_material(&secret_key)?),
            error: PasskeyKeyError::Unspecified as i32,
        },
        Err(issue) => PasskeyKeyInspection {
            key_material: None,
            error: issue.to_proto() as i32,
        },
    };
    Ok(result.encode_to_vec())
}

pub(crate) fn sign(
    algorithm: i32,
    private_key_pkcs8: Vec<u8>,
    data: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    // The dispatcher transfers ownership of both secrets out of the protobuf
    // request, so the local copies must retain zeroizing ownership on every
    // early-return path.
    let private_key_pkcs8 = Zeroizing::new(private_key_pkcs8);
    let data = Zeroizing::new(data);
    require_es256(algorithm)?;
    if data.len() > MAX_SIGN_DATA_BYTES {
        return Err(PrimitiveError::ResourceLimit);
    }

    let result = match parse_private_key(&private_key_pkcs8) {
        Ok(secret_key) => {
            let signing_key = SigningKey::from(secret_key);
            let signature: Signature = signing_key.sign(data.as_ref());
            PasskeySignResult {
                signature: Some(PasskeySignature {
                    algorithm: PasskeyAlgorithm::Es256 as i32,
                    signature_der: signature.to_der().as_bytes().to_vec(),
                }),
                error: PasskeyKeyError::Unspecified as i32,
            }
        }
        Err(issue) => PasskeySignResult {
            signature: None,
            error: issue.to_proto() as i32,
        },
    };
    Ok(result.encode_to_vec())
}

fn require_es256(algorithm: i32) -> Result<(), PrimitiveError> {
    match PasskeyAlgorithm::try_from(algorithm) {
        Ok(PasskeyAlgorithm::Es256) => Ok(()),
        _ => Err(PrimitiveError::InvalidArgument),
    }
}

fn parse_private_key(input: &[u8]) -> Result<SecretKey, KeyIssue> {
    if input.len() > MAX_PRIVATE_KEY_BYTES {
        return Err(KeyIssue::ResourceLimit);
    }

    let private_key_info = PrivateKeyInfo::from_der(input).map_err(|_| KeyIssue::Malformed)?;
    if private_key_info.algorithm.oid != EC_PUBLIC_KEY_OID {
        return Err(KeyIssue::Unsupported);
    }

    let parameters = private_key_info
        .algorithm
        .parameters
        .ok_or(KeyIssue::Unsupported)?;
    let curve_oid = parameters
        .decode_as::<DerObjectIdentifier>()
        .map_err(|_| KeyIssue::Unsupported)?;
    if curve_oid != P256_OID {
        return Err(KeyIssue::Unsupported);
    }

    let ec_private_key =
        EcPrivateKey::from_der(private_key_info.private_key).map_err(|_| KeyIssue::Malformed)?;
    if let Some(parameters) = ec_private_key.parameters {
        match parameters {
            EcParameters::NamedCurve(oid) if oid == P256_OID => (),
            _ => return Err(KeyIssue::Unsupported),
        };
    }
    if ec_private_key.private_key.len() != PRIVATE_SCALAR_BYTES {
        return Err(KeyIssue::Malformed);
    }

    let secret_key =
        SecretKey::from_slice(ec_private_key.private_key).map_err(|_| KeyIssue::Malformed)?;
    if let Some(public_key) = ec_private_key.public_key {
        require_matching_public_key(&secret_key, public_key)?;
    }
    if let Some(public_key) = private_key_info.public_key {
        require_matching_public_key(&secret_key, public_key)?;
    }
    Ok(secret_key)
}

fn require_matching_public_key(
    secret_key: &SecretKey,
    encoded_public_key: &[u8],
) -> Result<(), KeyIssue> {
    let public_key =
        PublicKey::from_sec1_bytes(encoded_public_key).map_err(|_| KeyIssue::Malformed)?;
    if public_key != secret_key.public_key() {
        return Err(KeyIssue::Malformed);
    }
    Ok(())
}

fn encode_material(secret_key: &SecretKey) -> Result<PasskeyKeyMaterial, PrimitiveError> {
    let private_key = secret_key
        .to_pkcs8_der()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let public_key = secret_key.public_key().to_encoded_point(false);
    let public_key_spki = secret_key
        .public_key()
        .to_public_key_der()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let public_key_x = public_key.x().ok_or(PrimitiveError::CryptoFailure)?;
    let public_key_y = public_key.y().ok_or(PrimitiveError::CryptoFailure)?;

    Ok(PasskeyKeyMaterial {
        profile: PasskeyKeyProfile::EcP256 as i32,
        private_key_pkcs8: private_key.as_bytes().to_vec(),
        public_key_x: public_key_x.to_vec(),
        public_key_y: public_key_y.to_vec(),
        public_key_spki: public_key_spki.as_bytes().to_vec(),
    })
}

impl KeyIssue {
    fn to_proto(self) -> PasskeyKeyError {
        match self {
            Self::Malformed => PasskeyKeyError::Malformed,
            Self::Unsupported => PasskeyKeyError::Unsupported,
            Self::ResourceLimit => PasskeyKeyError::ResourceLimit,
        }
    }
}

#[cfg(test)]
mod tests {
    use base64ct::{Base64UrlUnpadded, Encoding as _};
    use p256::ecdsa::{Signature, VerifyingKey, signature::Verifier as _};

    use super::*;

    const CXF_P256_KEY: &str = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgARu_0sCt20EpgVxb4Puq3Ga5VVLpuTY75ngvZlyq3X6hRANCAASmdk1xLsK0oOlhxIPp0d1ZuS0sT9nf6BZtSelhqvLBW0fOL33l_bXgsr_STUHjCLn8l6gcRJwe7OQvbQubZ1dY";

    #[test]
    fn inspects_and_canonicalizes_cxf_p256_key() {
        let input = Base64UrlUnpadded::decode_vec(CXF_P256_KEY).expect("fixture");
        let secret_key = parse_private_key(&input).expect("valid P-256 key");
        let material = encode_material(&secret_key).expect("material");

        assert_eq!(material.profile, PasskeyKeyProfile::EcP256 as i32);
        assert_eq!(material.public_key_x.len(), PRIVATE_SCALAR_BYTES);
        assert_eq!(material.public_key_y.len(), PRIVATE_SCALAR_BYTES);
        assert!(parse_private_key(&material.private_key_pkcs8).is_ok());
    }

    #[test]
    fn rejects_arbitrary_bytes_as_malformed() {
        assert_eq!(
            parse_private_key(&[0, 1, 2, 3, 4, 5]),
            Err(KeyIssue::Malformed),
        );
    }

    #[test]
    fn rejects_trailing_der_and_oversized_inputs() {
        let mut trailing = Base64UrlUnpadded::decode_vec(CXF_P256_KEY).expect("fixture");
        trailing.push(0);

        assert_eq!(parse_private_key(&trailing), Err(KeyIssue::Malformed));
        assert_eq!(
            parse_private_key(&vec![0; MAX_PRIVATE_KEY_BYTES + 1]),
            Err(KeyIssue::ResourceLimit),
        );
    }

    #[test]
    fn rejects_invalid_private_scalar() {
        let mut input = Base64UrlUnpadded::decode_vec(CXF_P256_KEY).expect("fixture");
        let private_key_info = PrivateKeyInfo::from_der(&input).expect("PKCS#8");
        let ec_private_key =
            EcPrivateKey::from_der(private_key_info.private_key).expect("EC private key");
        let private_scalar = ec_private_key.private_key.to_vec();
        let offset = input
            .windows(private_scalar.len())
            .position(|window| window == private_scalar)
            .expect("private scalar");
        input[offset..offset + private_scalar.len()].fill(0);

        assert_eq!(parse_private_key(&input), Err(KeyIssue::Malformed));
    }

    #[test]
    fn rejects_an_unsupported_named_curve() {
        let mut input = Base64UrlUnpadded::decode_vec(CXF_P256_KEY).expect("fixture");
        let curve_oid = [0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07];
        let offset = input
            .windows(curve_oid.len())
            .position(|window| window == curve_oid)
            .expect("curve OID");
        input[offset + curve_oid.len() - 1] = 0x22;

        assert_eq!(parse_private_key(&input), Err(KeyIssue::Unsupported));
    }

    #[test]
    fn rejects_a_mismatched_embedded_public_key() {
        let mut input = Base64UrlUnpadded::decode_vec(CXF_P256_KEY).expect("fixture");
        let other_payload = generate(PasskeyAlgorithm::Es256 as i32).expect("generate");
        let other = PasskeyKeyMaterial::decode(other_payload.as_slice()).expect("material");
        let other_public_key = [
            &[0x04],
            other.public_key_x.as_slice(),
            other.public_key_y.as_slice(),
        ]
        .concat();
        let public_key_offset = input.len() - other_public_key.len();
        input[public_key_offset..].copy_from_slice(&other_public_key);

        assert_eq!(parse_private_key(&input), Err(KeyIssue::Malformed));
    }

    #[test]
    fn generated_key_signs_der_encoded_es256() {
        let payload = generate(PasskeyAlgorithm::Es256 as i32).expect("generate");
        let material = PasskeyKeyMaterial::decode(payload.as_slice()).expect("material");
        let message = b"authenticator data and client data hash".to_vec();
        let signed = sign(
            PasskeyAlgorithm::Es256 as i32,
            material.private_key_pkcs8.clone(),
            message.clone(),
        )
        .expect("sign");
        let result = PasskeySignResult::decode(signed.as_slice()).expect("sign result");
        let signature = result.signature.expect("signature");

        let point = [
            &[0x04],
            material.public_key_x.as_slice(),
            material.public_key_y.as_slice(),
        ]
        .concat();
        let verifying_key = VerifyingKey::from_sec1_bytes(&point).expect("public key");
        let signature = Signature::from_der(&signature.signature_der).expect("DER signature");
        verifying_key
            .verify(&message, &signature)
            .expect("valid signature");
    }

    /// The dispatcher moves the request's secrets into `inspect` and `sign`, so
    /// those locals are their last owner and must be under a zeroizing guard
    /// before any statement can leave the function. Nothing observable at
    /// runtime distinguishes a guarded early return from an unguarded one: the
    /// vectors are already freed once either function comes back, and reading a
    /// freed block needs the unsafe code this crate forbids. Pin the ordering
    /// to the source text instead.
    #[test]
    fn moved_secrets_are_guarded_before_any_early_return() {
        let source = include_str!("passkeys.rs");
        let cases: [(&str, &[&str]); 2] = [
            (
                "pub(crate) fn inspect(",
                &["let private_key_pkcs8 = Zeroizing::new(private_key_pkcs8);"],
            ),
            (
                "pub(crate) fn sign(",
                &[
                    "let private_key_pkcs8 = Zeroizing::new(private_key_pkcs8);",
                    "let data = Zeroizing::new(data);",
                ],
            ),
        ];

        for (signature, guards) in cases {
            let body = function_code(source, signature);
            let early_exit = first_early_exit(&body);
            for guard in guards {
                let guarded = body
                    .find(guard)
                    .unwrap_or_else(|| panic!("`{signature}` must keep `{guard}`"));
                assert!(
                    guarded < early_exit,
                    "`{signature}` must run `{guard}` before its first early return",
                );
            }
        }
    }

    /// Returns the source of the function opening with `signature`, stripped of
    /// comment lines so prose cannot be mistaken for control flow.
    fn function_code(source: &str, signature: &str) -> String {
        let start = source.find(signature).expect("function signature");
        let body = source[start..]
            .split("\n}\n")
            .next()
            .expect("function body up to its closing brace");
        body.lines()
            .filter(|line| !line.trim_start().starts_with("//"))
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// Byte offset of the earliest statement that can leave the function: a `?`
    /// operator or an explicit `return`.
    fn first_early_exit(body: &str) -> usize {
        body.match_indices('?')
            .chain(body.match_indices("return"))
            .map(|(offset, _)| offset)
            .min()
            .expect("early-exit statement")
    }
}
