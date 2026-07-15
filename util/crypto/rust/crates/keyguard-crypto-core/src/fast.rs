//! Allocation-free data-plane operations for latency-sensitive native bridges.

use zeroize::Zeroize;

use crate::{primitive_error_code, primitives, protocol::NativeErrorCode};

/// Generates a secure random integer without a protobuf control envelope.
///
/// An `exclusive_upper_bound` of zero requests an unbounded signed integer.
/// Positive bounds preserve `SecureRandom.nextInt(bound)` semantics without
/// modulo bias.
///
/// # Errors
///
/// Returns [`NativeErrorCode::InvalidArgument`] when the bound is greater than
/// `i32::MAX`, and [`NativeErrorCode::CryptoFailure`] when the operating-system
/// random source fails.
pub fn random_int(exclusive_upper_bound: u32) -> Result<i32, NativeErrorCode> {
    primitives::random_int(exclusive_upper_bound != 0, exclusive_upper_bound)
        .map_err(primitive_error_code)
}

/// Encrypts `plaintext` with AES-CBC-PKCS#7 and writes HMAC-SHA256 over
/// `iv || ciphertext` into caller-owned buffers.
///
/// `ciphertext_out` must be exactly one PKCS#7-padded ciphertext long and
/// `mac_out` must be exactly 32 bytes. Both outputs are zeroed if the operation
/// returns an error or unwinds.
///
/// # Errors
///
/// Returns [`NativeErrorCode::InvalidArgument`] for invalid key, IV, or output
/// shapes, [`NativeErrorCode::ResourceLimit`] when padded-length arithmetic is
/// not representable, and a stable backend error for cryptographic failures.
pub fn encrypt_into(
    enc_key: &[u8],
    mac_key: &[u8],
    iv: &[u8],
    plaintext: &[u8],
    ciphertext_out: &mut [u8],
    mac_out: &mut [u8],
) -> Result<usize, NativeErrorCode> {
    let mut outputs = PairOutputGuard::new(ciphertext_out, mac_out);
    let result = {
        let (ciphertext_out, mac_out) = outputs.buffers();
        primitives::aes_cbc_pkcs7_hmac_sha256_encrypt_into(
            enc_key,
            mac_key,
            iv,
            plaintext,
            ciphertext_out,
            mac_out,
        )
    };
    match result {
        Ok(length) => {
            outputs.disarm();
            Ok(length)
        }
        Err(error) => Err(primitive_error_code(error)),
    }
}

/// Verifies HMAC-SHA256 over `iv || ciphertext` before decrypting AES-CBC-
/// PKCS#7 into a caller-owned buffer.
///
/// `plaintext_out` must be exactly `ciphertext.len()` bytes. Any expected MAC
/// length or value mismatch is an authentication failure. The returned length
/// identifies the unpadded plaintext prefix; the remaining output tail is
/// zero. The complete output is zeroed if the operation returns an error or
/// unwinds.
///
/// # Errors
///
/// Returns [`NativeErrorCode::AuthenticationFailed`] for a tag mismatch or
/// invalid authenticated padding, [`NativeErrorCode::InvalidArgument`] for
/// invalid key, IV, ciphertext, MAC, or output shapes, and a stable resource or
/// backend error when applicable.
pub fn decrypt_into(
    enc_key: &[u8],
    mac_key: &[u8],
    iv: &[u8],
    ciphertext: &[u8],
    expected_mac: &[u8],
    plaintext_out: &mut [u8],
) -> Result<usize, NativeErrorCode> {
    let mut output = OutputGuard::new(plaintext_out);
    let result = primitives::aes_cbc_pkcs7_hmac_sha256_decrypt_into(
        enc_key,
        mac_key,
        iv,
        ciphertext,
        expected_mac,
        output.buffer(),
    );
    match result {
        Ok(length) => {
            output.disarm();
            Ok(length)
        }
        Err(error) => Err(primitive_error_code(error)),
    }
}

struct OutputGuard<'a> {
    output: &'a mut [u8],
    armed: bool,
}

impl<'a> OutputGuard<'a> {
    fn new(output: &'a mut [u8]) -> Self {
        Self {
            output,
            armed: true,
        }
    }

    fn buffer(&mut self) -> &mut [u8] {
        self.output
    }

    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for OutputGuard<'_> {
    fn drop(&mut self) {
        if self.armed {
            self.output.zeroize();
        }
    }
}

struct PairOutputGuard<'a> {
    first: &'a mut [u8],
    second: &'a mut [u8],
    armed: bool,
}

impl<'a> PairOutputGuard<'a> {
    fn new(first: &'a mut [u8], second: &'a mut [u8]) -> Self {
        Self {
            first,
            second,
            armed: true,
        }
    }

    fn buffers(&mut self) -> (&mut [u8], &mut [u8]) {
        (self.first, self.second)
    }

    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for PairOutputGuard<'_> {
    fn drop(&mut self) {
        if self.armed {
            self.first.zeroize();
            self.second.zeroize();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::MAX_CONTROL_ENVELOPE_BYTES;

    #[test]
    fn fast_random_int_preserves_scalar_contract() {
        assert_eq!(random_int(1), Ok(0));
        for _ in 0..128 {
            let value = random_int(7).expect("valid bounded random integer must succeed");
            assert!((0..7).contains(&value));
        }
        assert!(random_int(0).is_ok());
        assert_eq!(
            random_int(i32::MAX as u32 + 1),
            Err(NativeErrorCode::InvalidArgument)
        );
    }

    #[test]
    fn fast_fused_operation_matches_golden_and_zeroes_plaintext_tail() {
        let enc_key: Vec<u8> = (0_u8..32).collect();
        let mac_key: Vec<u8> = (32_u8..64).collect();
        let iv: Vec<u8> = (64_u8..80).collect();
        let plaintext = b"Bitwarden fused AES-CBC/HMAC test vector";
        let mut ciphertext = vec![0xaa; 48];
        let mut mac = vec![0xaa; 32];

        let encrypted_length = encrypt_into(
            &enc_key,
            &mac_key,
            &iv,
            plaintext,
            &mut ciphertext,
            &mut mac,
        )
        .expect("valid fast encryption must succeed");
        assert_eq!(encrypted_length, ciphertext.len());
        assert_eq!(
            ciphertext,
            decode_hex(concat!(
                "b3ae5dcb9dd806f8266f89d2e9e3489d37964364df9a2b1767d16f3fda8f82ae",
                "088a3c1a342b9b5b72417ed002bc0248"
            ))
        );
        assert_eq!(
            mac,
            decode_hex("6f9cc3bd0c5cd61850923fe87d0edb133fc1f84e7f7a513658b87dd2d35359c8")
        );

        let mut decrypted = vec![0xaa; ciphertext.len()];
        let plaintext_length =
            decrypt_into(&enc_key, &mac_key, &iv, &ciphertext, &mac, &mut decrypted)
                .expect("valid fast decryption must succeed");
        assert_eq!(&decrypted[..plaintext_length], plaintext);
        assert!(decrypted[plaintext_length..].iter().all(|byte| *byte == 0));
    }

    #[test]
    fn fast_fused_operation_zeroes_outputs_on_errors() {
        let enc_key = vec![0x11; 32];
        let mac_key = vec![0x22; 32];
        let iv = vec![0x33; 16];
        let plaintext = b"fast output clearing";
        let mut short_ciphertext = vec![0xaa; 31];
        let mut mac = vec![0xaa; 32];

        assert_eq!(
            encrypt_into(
                &enc_key,
                &mac_key,
                &iv,
                plaintext,
                &mut short_ciphertext,
                &mut mac,
            ),
            Err(NativeErrorCode::InvalidArgument)
        );
        assert!(short_ciphertext.iter().all(|byte| *byte == 0));
        assert!(mac.iter().all(|byte| *byte == 0));

        let malformed_ciphertext = vec![0x44; 15];
        let mut plaintext_out = vec![0xaa; malformed_ciphertext.len()];
        assert_eq!(
            decrypt_into(
                &enc_key,
                &mac_key,
                &iv,
                &malformed_ciphertext,
                &[0x55; 31],
                &mut plaintext_out,
            ),
            Err(NativeErrorCode::AuthenticationFailed)
        );
        assert!(plaintext_out.iter().all(|byte| *byte == 0));
    }

    #[test]
    fn fast_fused_length_is_not_limited_by_the_protobuf_envelope() {
        let plaintext_length = MAX_CONTROL_ENVELOPE_BYTES + 1;
        let padded_length =
            primitives::aes_cbc_pkcs7_hmac_sha256_ciphertext_length(plaintext_length)
                .expect("raw data-plane length must not use the protobuf envelope bound");
        assert_eq!(padded_length, plaintext_length.div_ceil(16) * 16);
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| {
                let pair = std::str::from_utf8(pair).expect("test hex must be UTF-8");
                u8::from_str_radix(pair, 16).expect("test hex must decode")
            })
            .collect()
    }
}
