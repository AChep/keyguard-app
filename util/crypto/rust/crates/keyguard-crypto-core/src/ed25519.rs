//! RFC 8032 Ed25519 key generation, signing, and strict verification.
//!
//! A private key is the 32-byte seed and a public key is the 32-byte encoded
//! point. Signing is deterministic and does not branch on secret data.
//! Verification uses `verify_strict`: it rejects small-order public keys and
//! `R` components as well as non-canonical scalars, so signatures are not
//! malleable.

use aws_lc_rs::rand::{SecureRandom, SystemRandom};
use ed25519_dalek::{
    PUBLIC_KEY_LENGTH, SECRET_KEY_LENGTH, Signature, Signer as _, SigningKey, VerifyingKey,
};
use prost::Message;
use zeroize::Zeroizing;

use crate::{
    primitives::PrimitiveError,
    protocol::{Ed25519KeyGenerateResult, Ed25519SignResult, Ed25519VerifyResult},
};

pub(crate) fn generate() -> Result<Vec<u8>, PrimitiveError> {
    let mut seed = Zeroizing::new([0_u8; SECRET_KEY_LENGTH]);
    SystemRandom::new()
        .fill(seed.as_mut())
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    let public_key = SigningKey::from_bytes(&seed).verifying_key().to_bytes();
    Ok(Ed25519KeyGenerateResult {
        private_key: seed.to_vec(),
        public_key: public_key.to_vec(),
    }
    .encode_to_vec())
}

pub(crate) fn sign(private_key: Vec<u8>, data: Vec<u8>) -> Result<Vec<u8>, PrimitiveError> {
    // The dispatcher moves both secrets out of the protobuf request, so the
    // locals must own them under a zeroizing guard before any early return.
    let private_key = Zeroizing::new(private_key);
    let data = Zeroizing::new(data);
    let seed: &[u8; SECRET_KEY_LENGTH] = private_key
        .as_slice()
        .try_into()
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let signature = SigningKey::from_bytes(seed).sign(data.as_slice());
    Ok(Ed25519SignResult {
        signature: signature.to_bytes().to_vec(),
    }
    .encode_to_vec())
}

pub(crate) fn verify(
    public_key: Vec<u8>,
    data: Vec<u8>,
    signature: Vec<u8>,
) -> Result<Vec<u8>, PrimitiveError> {
    let data = Zeroizing::new(data);
    let public_key: &[u8; PUBLIC_KEY_LENGTH] = public_key
        .as_slice()
        .try_into()
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let valid = verify_strict(public_key, data.as_slice(), &signature);
    Ok(Ed25519VerifyResult { valid }.encode_to_vec())
}

fn verify_strict(public_key: &[u8; PUBLIC_KEY_LENGTH], data: &[u8], signature: &[u8]) -> bool {
    let Ok(signature) = Signature::from_slice(signature) else {
        return false;
    };
    let Ok(verifying_key) = VerifyingKey::from_bytes(public_key) else {
        return false;
    };
    verifying_key.verify_strict(data, &signature).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        PROTOCOL_VERSION, call,
        protocol::{
            self, Ed25519KeyGenerateRequest, Ed25519SignRequest, Ed25519VerifyRequest,
            NativeErrorCode, NativeRequest, NativeResponse, native_request, native_response,
        },
    };

    struct Vector {
        private_key: &'static str,
        public_key: &'static str,
        message: &'static str,
        signature: &'static str,
    }

    /// RFC 8032 section 7.1, TEST 1 to TEST 3.
    const RFC_8032_VECTORS: [Vector; 3] = [
        Vector {
            private_key: "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            public_key: "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            message: "",
            signature: "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155\
                        5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        },
        Vector {
            private_key: "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            public_key: "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            message: "72",
            signature: "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da\
                        085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        },
        Vector {
            private_key: "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            public_key: "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            message: "af82",
            signature: "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac\
                        18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        },
    ];

    /// The group order L = 2^252 + 27742317777372353535851937790883648493, little endian.
    const GROUP_ORDER: [u8; 32] = [
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde,
        0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x10,
    ];

    fn hex(value: &str) -> Vec<u8> {
        assert_eq!(value.len() % 2, 0, "test hex must have an even length");
        (0..value.len())
            .step_by(2)
            .map(|index| u8::from_str_radix(&value[index..index + 2], 16).expect("test hex"))
            .collect()
    }

    fn signature_of(private_key: Vec<u8>, data: Vec<u8>) -> Vec<u8> {
        let encoded = sign(private_key, data).expect("sign");
        Ed25519SignResult::decode(encoded.as_slice())
            .expect("sign result")
            .signature
    }

    fn is_valid(public_key: Vec<u8>, data: Vec<u8>, signature: Vec<u8>) -> bool {
        let encoded = verify(public_key, data, signature).expect("verify");
        Ed25519VerifyResult::decode(encoded.as_slice())
            .expect("verify result")
            .valid
    }

    fn invoke(operation: native_request::Operation) -> NativeResponse {
        let request = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(operation),
        };
        NativeResponse::decode(call(&request.encode_to_vec()).as_slice())
            .expect("native response must decode")
    }

    fn status(response: &NativeResponse) -> (i32, &str) {
        let status = response.status.as_ref().expect("status");
        (status.code, status.operation.as_str())
    }

    fn bytes_value(response: NativeResponse) -> Vec<u8> {
        match response.result {
            Some(native_response::Result::BytesValue(bytes)) => bytes,
            _ => panic!("response must carry bytes"),
        }
    }

    #[test]
    fn rfc_8032_vectors_derive_sign_and_verify() {
        for vector in RFC_8032_VECTORS {
            let seed: [u8; SECRET_KEY_LENGTH] =
                hex(vector.private_key).try_into().expect("32-byte seed");
            assert_eq!(
                SigningKey::from_bytes(&seed)
                    .verifying_key()
                    .to_bytes()
                    .to_vec(),
                hex(vector.public_key),
            );
            assert_eq!(
                signature_of(hex(vector.private_key), hex(vector.message)),
                hex(vector.signature),
            );
            assert!(is_valid(
                hex(vector.public_key),
                hex(vector.message),
                hex(vector.signature),
            ));
        }
    }

    #[test]
    fn rejects_tampered_signatures_messages_and_keys() {
        let [first, second, _] = RFC_8032_VECTORS;
        for bit in [0, 7, 255, 256, 511] {
            let mut signature = hex(second.signature);
            signature[bit / 8] ^= 1 << (bit % 8);
            assert!(!is_valid(
                hex(second.public_key),
                hex(second.message),
                signature
            ));
        }
        assert!(!is_valid(
            hex(second.public_key),
            b"s".to_vec(),
            hex(second.signature),
        ));
        assert!(!is_valid(
            hex(first.public_key),
            hex(second.message),
            hex(second.signature),
        ));
    }

    #[test]
    fn rejects_a_non_canonical_scalar() {
        let vector = &RFC_8032_VECTORS[1];
        let mut signature = hex(vector.signature);
        let mut carry = 0_u16;
        for (byte, order) in signature[32..].iter_mut().zip(GROUP_ORDER) {
            let sum = u16::from(*byte) + u16::from(order) + carry;
            *byte = sum as u8;
            carry = sum >> 8;
        }
        assert_eq!(carry, 0, "S + L must still fit in 32 bytes");

        assert!(!is_valid(
            hex(vector.public_key),
            hex(vector.message),
            signature
        ));
    }

    #[test]
    fn rejects_a_small_order_key_that_lenient_verification_accepts() {
        // With the identity point as both A and R and S = 0, the cofactorless
        // equation [S]B = R + [k]A holds for every message.
        let mut identity = [0_u8; PUBLIC_KEY_LENGTH];
        identity[0] = 1;
        let mut signature = [0_u8; 64];
        signature[0] = 1;
        let message = b"any message";
        let key = VerifyingKey::from_bytes(&identity).expect("identity decodes");
        assert!(
            ed25519_dalek::Verifier::verify(&key, message, &Signature::from_bytes(&signature))
                .is_ok(),
            "the fixture must be accepted by lenient verification",
        );

        assert!(!is_valid(
            identity.to_vec(),
            message.to_vec(),
            signature.to_vec(),
        ));
    }

    #[test]
    fn length_errors_are_invalid_arguments_or_invalid_signatures() {
        let vector = &RFC_8032_VECTORS[1];
        assert_eq!(
            sign(vec![0; SECRET_KEY_LENGTH - 1], Vec::new()),
            Err(PrimitiveError::InvalidArgument),
        );
        assert_eq!(
            sign(vec![0; SECRET_KEY_LENGTH + 1], Vec::new()),
            Err(PrimitiveError::InvalidArgument),
        );
        assert_eq!(
            verify(
                hex(vector.public_key)[1..].to_vec(),
                hex(vector.message),
                hex(vector.signature),
            ),
            Err(PrimitiveError::InvalidArgument),
        );
        let mut long_signature = hex(vector.signature);
        long_signature.push(0);
        assert!(!is_valid(
            hex(vector.public_key),
            hex(vector.message),
            long_signature,
        ));
        assert!(!is_valid(
            hex(vector.public_key),
            hex(vector.message),
            hex(vector.signature)[1..].to_vec(),
        ));
        // Not every 32-byte string is the encoding of a curve point.
        let mut off_curve = [0_u8; PUBLIC_KEY_LENGTH];
        off_curve[0] = 2;
        assert!(VerifyingKey::from_bytes(&off_curve).is_err());
        assert!(!is_valid(
            off_curve.to_vec(),
            hex(vector.message),
            hex(vector.signature),
        ));
    }

    #[test]
    fn dispatched_generated_keys_sign_and_verify() {
        let first = bytes_value(invoke(native_request::Operation::Ed25519KeyGenerate(
            Ed25519KeyGenerateRequest {},
        )));
        let first = Ed25519KeyGenerateResult::decode(first.as_slice()).expect("key pair");
        let second = bytes_value(invoke(native_request::Operation::Ed25519KeyGenerate(
            Ed25519KeyGenerateRequest {},
        )));
        let second = Ed25519KeyGenerateResult::decode(second.as_slice()).expect("key pair");
        assert_eq!(first.private_key.len(), SECRET_KEY_LENGTH);
        assert_eq!(first.public_key.len(), PUBLIC_KEY_LENGTH);
        assert_ne!(first.private_key, second.private_key);
        assert_ne!(first.public_key, second.public_key);

        let message = b"roster entry".to_vec();
        let response = invoke(native_request::Operation::Ed25519Sign(Ed25519SignRequest {
            private_key: first.private_key.clone(),
            data: message.clone(),
        }));
        assert_eq!(
            status(&response),
            (NativeErrorCode::Ok as i32, "ed25519_sign")
        );
        let signature = Ed25519SignResult::decode(bytes_value(response).as_slice())
            .expect("signature")
            .signature;
        assert_eq!(signature.len(), 64);

        for (public_key, expected) in [
            (first.public_key.clone(), true),
            (second.public_key.clone(), false),
        ] {
            let response = invoke(native_request::Operation::Ed25519Verify(
                Ed25519VerifyRequest {
                    public_key,
                    data: message.clone(),
                    signature: signature.clone(),
                },
            ));
            assert_eq!(
                status(&response),
                (NativeErrorCode::Ok as i32, "ed25519_verify")
            );
            let result = Ed25519VerifyResult::decode(bytes_value(response).as_slice())
                .expect("verify result");
            assert_eq!(result.valid, expected);
        }
    }

    #[test]
    fn dispatched_invalid_lengths_report_invalid_argument() {
        let response = invoke(native_request::Operation::Ed25519Sign(Ed25519SignRequest {
            private_key: vec![0; 64],
            data: b"data".to_vec(),
        }));
        assert_eq!(
            status(&response),
            (NativeErrorCode::InvalidArgument as i32, "ed25519_sign"),
        );
        let response = invoke(native_request::Operation::Ed25519Verify(
            Ed25519VerifyRequest {
                public_key: vec![0; 33],
                data: b"data".to_vec(),
                signature: vec![0; 64],
            },
        ));
        assert_eq!(
            status(&response),
            (NativeErrorCode::InvalidArgument as i32, "ed25519_verify"),
        );
    }

    #[test]
    fn decoded_secret_request_zeroizes_on_invalid_argument() {
        let encoded = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_request::Operation::Ed25519Sign(Ed25519SignRequest {
                private_key: b"short secret private key".to_vec(),
                data: b"secret payload".to_vec(),
            })),
        }
        .encode_to_vec();
        protocol::reset_zeroized_secret_request_drops();

        let response = NativeResponse::decode(call(&encoded).as_slice()).expect("response");

        assert_eq!(
            status(&response),
            (NativeErrorCode::InvalidArgument as i32, "ed25519_sign"),
        );
        assert_eq!(protocol::zeroized_secret_request_drops(), 1);
    }

    #[test]
    fn generated_private_key_zeroizes_after_response_encoding() {
        protocol::reset_zeroized_secret_output_drops();
        let response = invoke(native_request::Operation::Ed25519KeyGenerate(
            Ed25519KeyGenerateRequest {},
        ));
        assert_eq!(
            status(&response),
            (NativeErrorCode::Ok as i32, "ed25519_key_generate"),
        );
        assert_eq!(protocol::zeroized_secret_output_drops(), 1);
    }
}
