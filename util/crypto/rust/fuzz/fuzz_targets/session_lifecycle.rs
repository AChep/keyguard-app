#![no_main]

use keyguard_crypto_core::{
    PROTOCOL_VERSION,
    protocol::{
        AesCbcPkcs7HmacSha256DecryptStreamOpenRequest,
        AesCbcPkcs7HmacSha256EncryptStreamOpenRequest, AesCbcPkcs7StreamOpenRequest,
        CipherDirection, DigestStreamOpenRequest, HashAlgorithm, HmacSha256StreamOpenRequest,
        HmacStreamOpenRequest, NativeResponse, NativeStreamOpenRequest,
        TwofishCbcPkcs7StreamOpenRequest, native_response, native_stream_open_request,
    },
};
use libfuzzer_sys::fuzz_target;
use prost::Message;

fuzz_target!(|data: &[u8]| {
    // Exercise malformed/version-skewed stream-open envelopes as well as the
    // valid session lifecycle below. Close a chance-valid raw handle so the
    // process-wide registry cannot fill across fuzz iterations.
    if let Ok(response) = NativeResponse::decode(keyguard_crypto_core::stream_open(data).as_slice())
        && let Some(native_response::Result::Uint64Value(handle)) = response.result
    {
        let _ = keyguard_crypto_core::stream_close(handle);
    }

    let selector = data.first().copied().unwrap_or(0);
    let algorithm = match selector % 4 {
        0 => HashAlgorithm::Sha1,
        1 => HashAlgorithm::Sha256,
        2 => HashAlgorithm::Sha512,
        _ => HashAlgorithm::Md5,
    };
    let mut key = vec![0_u8; 32];
    for (target, source) in key.iter_mut().zip(data.iter().copied().skip(1)) {
        *target = source;
    }
    let mut iv = vec![0_u8; 16];
    for (target, source) in iv.iter_mut().zip(data.iter().copied().skip(33)) {
        *target = source;
    }
    let mac_key = key.clone();
    let operation = match selector % 9 {
        0 => native_stream_open_request::Operation::HmacSha256(HmacSha256StreamOpenRequest { key }),
        1 => native_stream_open_request::Operation::Digest(DigestStreamOpenRequest {
            algorithm: algorithm as i32,
        }),
        2 => native_stream_open_request::Operation::Hmac(HmacStreamOpenRequest {
            algorithm: algorithm as i32,
            key,
        }),
        3 => native_stream_open_request::Operation::AesCbcPkcs7(AesCbcPkcs7StreamOpenRequest {
            direction: CipherDirection::Encrypt as i32,
            key,
            iv,
        }),
        4 => native_stream_open_request::Operation::AesCbcPkcs7(AesCbcPkcs7StreamOpenRequest {
            direction: CipherDirection::Decrypt as i32,
            key,
            iv,
        }),
        5 => native_stream_open_request::Operation::TwofishCbcPkcs7(
            TwofishCbcPkcs7StreamOpenRequest {
                direction: CipherDirection::Encrypt as i32,
                key,
                iv,
            },
        ),
        6 => native_stream_open_request::Operation::TwofishCbcPkcs7(
            TwofishCbcPkcs7StreamOpenRequest {
                direction: CipherDirection::Decrypt as i32,
                key,
                iv,
            },
        ),
        7 => native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Encrypt(
            AesCbcPkcs7HmacSha256EncryptStreamOpenRequest {
                encryption_key: key,
                mac_key,
                iv,
            },
        ),
        _ => native_stream_open_request::Operation::AesCbcPkcs7HmacSha256Decrypt(
            AesCbcPkcs7HmacSha256DecryptStreamOpenRequest {
                encryption_key: key,
                mac_key,
                iv,
                expected_mac: vec![selector; 32],
            },
        ),
    };
    let open = NativeStreamOpenRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    };
    let response = keyguard_crypto_core::stream_open(&open.encode_to_vec());
    let Ok(response) = NativeResponse::decode(response.as_slice()) else {
        return;
    };
    let Some(native_response::Result::Uint64Value(handle)) = response.result else {
        return;
    };

    let chunk_size = usize::from(data.get(1).copied().unwrap_or(0) % 64) + 1;
    for chunk in data.get(2..).unwrap_or_default().chunks(chunk_size) {
        let _ = keyguard_crypto_core::stream_update(handle, chunk);
    }
    if selector & 1 == 0 {
        let _ = keyguard_crypto_core::stream_finish(handle);
        let _ = keyguard_crypto_core::stream_update(handle, b"stale");
    }
    let _ = keyguard_crypto_core::stream_close(handle);
    let _ = keyguard_crypto_core::stream_close(handle);
});
