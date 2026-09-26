//! Pairing and HMAC authentication for the WebSocket path (Safari).
//!
//! The pairing flow establishes a shared secret between the extension and the
//! agent using a 24-character code displayed by the Kotlin desktop app.
//! The shared secret is derived via HKDF.
//!
//! The agent stores the raw shared secret (hex-encoded) in a file with 0600
//! permissions. The extension encrypts it with the user's PIN/password.
//!
//! On each WebSocket connection, the agent sends a random 32-byte challenge.
//! The extension computes HMAC-SHA256(shared_secret, challenge) and sends it
//! back. The agent verifies by computing the HMAC locally and comparing.

use anyhow::{anyhow, Result};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

/// Size of the pairing code in characters.
#[allow(dead_code)]
pub const PAIRING_CODE_LEN: usize = 24;

/// Characters used in the pairing code (unambiguous, no lookalikes).
#[allow(dead_code)]
const CODE_CHARS: &[u8] = b"abcdefghjkmnpqrstuvwxyz23456789";

/// Salt for HKDF pairing key derivation.
#[allow(dead_code)]
const PAIRING_SALT: &[u8] = b"keyguard-pairing-v1";

/// Info for HKDF pairing key derivation.
#[allow(dead_code)]
const PAIRING_INFO: &[u8] = b"shared-secret";

/// Generates a random 24-character pairing code.
#[allow(dead_code)]
pub fn generate_pairing_code() -> String {
    let mut code = String::with_capacity(PAIRING_CODE_LEN);
    let mut rng = OsRng;
    for _ in 0..PAIRING_CODE_LEN {
        let idx = rng.next_u32() as usize % CODE_CHARS.len();
        code.push(CODE_CHARS[idx] as char);
    }
    code
}

/// Derives the shared secret (32 bytes) from a pairing code using HKDF-SHA256.
#[allow(dead_code)]
pub fn derive_shared_secret(code: &str) -> Result<[u8; 32]> {
    let ikm = code.trim().as_bytes();
    let hk = Hkdf::<Sha256>::new(Some(PAIRING_SALT), ikm);
    let mut secret = [0u8; 32];
    hk.expand(PAIRING_INFO, &mut secret)
        .map_err(|e| anyhow!("HKDF expansion failed: {e}"))?;
    Ok(secret)
}

/// Generates a random 32-byte HMAC challenge.
pub fn generate_challenge() -> [u8; 32] {
    let mut challenge = [0u8; 32];
    rand::RngCore::fill_bytes(&mut OsRng, &mut challenge);
    challenge
}

/// Computes HMAC-SHA256(secret, challenge).
pub fn compute_hmac(secret: &[u8; 32], challenge: &[u8; 32]) -> [u8; 32] {
    let mut mac = HmacSha256::new_from_slice(secret)
        .expect("HMAC can take key of any size");
    mac.update(challenge);
    let result = mac.finalize().into_bytes();
    let mut hmac_bytes = [0u8; 32];
    hmac_bytes.copy_from_slice(&result);
    hmac_bytes
}

/// Verifies that `received_hmac` matches HMAC-SHA256(secret, challenge).
#[allow(dead_code)]
pub fn verify_hmac(secret: &[u8; 32], challenge: &[u8; 32], received_hmac: &[u8; 32]) -> bool {
    let expected = compute_hmac(secret, challenge);
    expected == *received_hmac
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_pairing_code_length() {
        let code = generate_pairing_code();
        assert_eq!(code.len(), PAIRING_CODE_LEN);
    }

    #[test]
    fn derive_shared_secret_deterministic() {
        let code = "a3f8k2m9x7b1n4p6q2w5r8t1";
        let s1 = derive_shared_secret(code).unwrap();
        let s2 = derive_shared_secret(code).unwrap();
        assert_eq!(s1, s2);
    }

    #[test]
    fn different_codes_different_secrets() {
        let s1 = derive_shared_secret("a3f8k2m9x7b1n4p6q2w5r8t1").unwrap();
        let s2 = derive_shared_secret("b4g9l3n0y8c2o5q7r3x6s9u2").unwrap();
        assert_ne!(s1, s2);
    }

    #[test]
    fn hmac_roundtrip() {
        let secret = derive_shared_secret("a3f8k2m9x7b1n4p6q2w5r8t1").unwrap();
        let challenge = generate_challenge();

        let hmac = compute_hmac(&secret, &challenge);
        assert!(verify_hmac(&secret, &challenge, &hmac));
    }

    #[test]
    fn hmac_wrong_secret_fails() {
        let secret1 = derive_shared_secret("a3f8k2m9x7b1n4p6q2w5r8t1").unwrap();
        let secret2 = derive_shared_secret("b4g9l3n0y8c2o5q7r3x6s9u2").unwrap();
        let challenge = generate_challenge();

        let hmac = compute_hmac(&secret1, &challenge);
        assert!(!verify_hmac(&secret2, &challenge, &hmac));
    }

    #[test]
    fn hmac_wrong_challenge_fails() {
        let secret = derive_shared_secret("a3f8k2m9x7b1n4p6q2w5r8t1").unwrap();
        let challenge1 = generate_challenge();
        let challenge2 = generate_challenge();

        let hmac = compute_hmac(&secret, &challenge1);
        assert!(!verify_hmac(&secret, &challenge2, &hmac));
    }
}
