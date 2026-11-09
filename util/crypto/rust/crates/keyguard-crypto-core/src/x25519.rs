//! RFC 7748 X25519 key agreement.
//!
//! A private key is a raw 32-byte scalar, as in a Noise key pair; it is
//! clamped when used. A public key is a 32-byte u-coordinate. A low-order
//! public key yields the all-zero shared secret, which is rejected, as the
//! Noise resolver, libsodium and WireGuard do; the check is constant time.

use prost::Message;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::{primitives::PrimitiveError, protocol::X25519AgreeResult};

/// Length of a private key, a public key, and a shared secret.
const KEY_BYTES: usize = 32;

pub(crate) fn agree(private_key: Vec<u8>, public_key: Vec<u8>) -> Result<Vec<u8>, PrimitiveError> {
    // The dispatcher moves the secret out of the protobuf request, so the
    // local must own it under a zeroizing guard before any early return.
    let private_key = Zeroizing::new(private_key);
    let scalar: &[u8; KEY_BYTES] = private_key
        .as_slice()
        .try_into()
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let public_key: [u8; KEY_BYTES] = public_key
        .as_slice()
        .try_into()
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let shared = StaticSecret::from(*scalar).diffie_hellman(&PublicKey::from(public_key));
    if !shared.was_contributory() {
        return Err(PrimitiveError::AuthenticationFailed);
    }
    Ok(X25519AgreeResult {
        shared_secret: shared.as_bytes().to_vec(),
    }
    .encode_to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        PROTOCOL_VERSION, call,
        protocol::{
            self, NativeErrorCode, NativeRequest, NativeResponse, NoiseKeyPairRequest,
            NoiseKeyPairResult, X25519AgreeRequest, native_request, native_response,
        },
    };

    // RFC 7748 section 6.1.
    const ALICE_PRIVATE_KEY: &str =
        "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a";
    const ALICE_PUBLIC_KEY: &str =
        "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
    const BOB_PRIVATE_KEY: &str =
        "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb";
    const BOB_PUBLIC_KEY: &str = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";
    const SHARED_SECRET: &str = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";

    fn hex(value: &str) -> Vec<u8> {
        assert_eq!(value.len() % 2, 0, "test hex must have an even length");
        (0..value.len())
            .step_by(2)
            .map(|index| u8::from_str_radix(&value[index..index + 2], 16).expect("test hex"))
            .collect()
    }

    fn shared_secret(private_key: Vec<u8>, public_key: Vec<u8>) -> Vec<u8> {
        let encoded = agree(private_key, public_key).expect("agree");
        X25519AgreeResult::decode(encoded.as_slice())
            .expect("agree result")
            .shared_secret
            .clone()
    }

    fn invoke(operation: native_request::Operation) -> NativeResponse {
        let request = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(operation),
        };
        NativeResponse::decode(call(&request.encode_to_vec()).as_slice())
            .expect("native response must decode")
    }

    fn invoke_agree(private_key: Vec<u8>, public_key: Vec<u8>) -> NativeResponse {
        invoke(native_request::Operation::X25519Agree(X25519AgreeRequest {
            private_key,
            public_key,
        }))
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

    fn noise_key_pair() -> (Vec<u8>, Vec<u8>) {
        let encoded = bytes_value(invoke(native_request::Operation::NoiseKeyPair(
            NoiseKeyPairRequest {
                private_key: Vec::new(),
            },
        )));
        let result = NoiseKeyPairResult::decode(encoded.as_slice()).expect("key pair");
        (result.private_key.clone(), result.public_key.clone())
    }

    #[test]
    fn rfc_7748_vectors_agree_in_both_directions() {
        assert_eq!(
            shared_secret(hex(ALICE_PRIVATE_KEY), hex(BOB_PUBLIC_KEY)),
            hex(SHARED_SECRET),
        );
        assert_eq!(
            shared_secret(hex(BOB_PRIVATE_KEY), hex(ALICE_PUBLIC_KEY)),
            hex(SHARED_SECRET),
        );
    }

    #[test]
    fn a_low_order_public_key_is_rejected() {
        // u = 0 is the identity; u = 1 has order 4, and a clamped scalar maps
        // it to zero as well.
        let mut order_four = [0_u8; KEY_BYTES];
        order_four[0] = 1;
        for public_key in [[0_u8; KEY_BYTES], order_four] {
            assert_eq!(
                agree(hex(ALICE_PRIVATE_KEY), public_key.to_vec()),
                Err(PrimitiveError::AuthenticationFailed),
            );
        }
    }

    #[test]
    fn keys_of_the_wrong_size_are_invalid_arguments() {
        for length in [0, KEY_BYTES - 1, KEY_BYTES + 1] {
            assert_eq!(
                agree(vec![7; length], hex(BOB_PUBLIC_KEY)),
                Err(PrimitiveError::InvalidArgument),
                "a {length}-byte private key",
            );
            assert_eq!(
                agree(hex(ALICE_PRIVATE_KEY), vec![9; length]),
                Err(PrimitiveError::InvalidArgument),
                "a {length}-byte public key",
            );
        }
    }

    #[test]
    fn dispatched_noise_key_pairs_agree() {
        let (first_private, first_public) = noise_key_pair();
        let (second_private, second_public) = noise_key_pair();

        let response = invoke_agree(first_private, second_public);
        assert_eq!(
            status(&response),
            (NativeErrorCode::Ok as i32, "x25519_agree")
        );
        let first = X25519AgreeResult::decode(bytes_value(response).as_slice())
            .expect("agree result")
            .shared_secret
            .clone();
        let response = invoke_agree(second_private, first_public);
        let second = X25519AgreeResult::decode(bytes_value(response).as_slice())
            .expect("agree result")
            .shared_secret
            .clone();
        assert_eq!(first.len(), KEY_BYTES);
        assert_eq!(first, second);
    }

    #[test]
    fn dispatched_failures_report_their_codes() {
        let response = invoke_agree(vec![0; KEY_BYTES + 1], hex(BOB_PUBLIC_KEY));
        assert_eq!(
            status(&response),
            (NativeErrorCode::InvalidArgument as i32, "x25519_agree"),
        );
        assert!(response.result.is_none());
        let response = invoke_agree(hex(ALICE_PRIVATE_KEY), vec![0; KEY_BYTES]);
        assert_eq!(
            status(&response),
            (NativeErrorCode::AuthenticationFailed as i32, "x25519_agree"),
        );
        assert!(response.result.is_none());
    }

    #[test]
    fn decoded_secret_request_zeroizes_on_invalid_argument() {
        let encoded = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_request::Operation::X25519Agree(X25519AgreeRequest {
                private_key: b"short secret private key".to_vec(),
                public_key: hex(BOB_PUBLIC_KEY),
            })),
        }
        .encode_to_vec();
        protocol::reset_zeroized_secret_request_drops();

        let response = NativeResponse::decode(call(&encoded).as_slice()).expect("response");

        assert_eq!(
            status(&response),
            (NativeErrorCode::InvalidArgument as i32, "x25519_agree"),
        );
        assert_eq!(protocol::zeroized_secret_request_drops(), 1);
    }

    #[test]
    fn shared_secret_zeroizes_after_response_encoding() {
        protocol::reset_zeroized_secret_output_drops();
        let response = invoke_agree(hex(ALICE_PRIVATE_KEY), hex(BOB_PUBLIC_KEY));
        assert_eq!(
            status(&response),
            (NativeErrorCode::Ok as i32, "x25519_agree")
        );
        assert_eq!(protocol::zeroized_secret_output_drops(), 1);
    }
}
