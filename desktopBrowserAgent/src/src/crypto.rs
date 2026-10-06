//! Cryptographic helpers for the browser agent.
//!
//! Session confidentiality between the browser extension and this agent is
//! provided by ephemeral ECDH (X25519) key agreement followed by AES-256-GCM
//! for every message. The shared secret is derived into a 32-byte AES key with
//! SHA-256 over the X25519 shared secret, a domain separator and the client id.
//!
//! For the HMAC challenge-response (Safari/WS path), see `pairing.rs`.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::rngs::OsRng;
use sha2::{Digest, Sha256};
use x25519_dalek::{EphemeralSecret, PublicKey};



const DOMAIN_SEPARATOR: &[u8] = b"keyguard-browser-agent-v1";
const NONCE_LEN: usize = 12;

/// An X25519 keypair together with its raw public key bytes.
pub struct KeyAgreement {
    secret: EphemeralSecret,
    public: PublicKey,
}

impl KeyAgreement {
    /// Generates a fresh ephemeral X25519 keypair.
    pub fn generate() -> Self {
        let secret = EphemeralSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret);
        Self { secret, public }
    }

    /// Returns the raw 32-byte public key, base64-encoded.
    pub fn public_key_base64(&self) -> String {
        B64.encode(self.public.as_bytes())
    }

    /// Performs ECDH with the peer public key and derives the AES-256 key.
    /// Consumes the agreement since a keypair is used for a single session.
    pub fn derive_key(self, peer_public_base64: &str, client_id: &str) -> Result<[u8; 32]> {
        let peer_bytes = B64
            .decode(peer_public_base64)
            .map_err(|e| anyhow!("Invalid peer public key: {e}"))?;
        let peer_array: [u8; 32] = peer_bytes
            .try_into()
            .map_err(|_| anyhow!("Peer public key must be 32 bytes"))?;
        let peer_public = PublicKey::from(peer_array);
        let shared = self.secret.diffie_hellman(&peer_public);
        let mut hasher = Sha256::new();
        hasher.update(shared.as_bytes());
        hasher.update(DOMAIN_SEPARATOR);
        hasher.update(client_id.as_bytes());
        let mut key = [0u8; 32];
        key.copy_from_slice(&hasher.finalize());
        Ok(key)
    }
}

/// Encrypts `plaintext` with AES-256-GCM and a random 12-byte nonce.
/// Returns `(nonce_b64, ciphertext_b64)`.
pub fn aes_encrypt(key: &[u8; 32], plaintext: &[u8]) -> Result<(String, String)> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| anyhow!("Failed to construct cipher: {e}"))?;
    let mut nonce_bytes = [0u8; NONCE_LEN];
    rand::RngCore::fill_bytes(&mut OsRng, &mut nonce_bytes);
    let ciphertext = cipher
        .encrypt(Nonce::from_slice(&nonce_bytes), Payload { msg: plaintext, aad: &[] })
        .map_err(|e| anyhow!("AES encryption failed: {e}"))?;
    Ok((B64.encode(nonce_bytes), B64.encode(ciphertext)))
}

/// Decrypts an AES-256-GCM ciphertext produced by [`aes_encrypt`].
pub fn aes_decrypt(key: &[u8; 32], nonce_b64: &str, ciphertext_b64: &str) -> Result<Vec<u8>> {
    let nonce_bytes = B64
        .decode(nonce_b64)
        .map_err(|e| anyhow!("Invalid nonce: {e}"))?;
    let ciphertext = B64
        .decode(ciphertext_b64)
        .map_err(|e| anyhow!("Invalid ciphertext: {e}"))?;
    if nonce_bytes.len() != NONCE_LEN {
        return Err(anyhow!("Nonce must be {NONCE_LEN} bytes"));
    }
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| anyhow!("Failed to construct cipher: {e}"))?;
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(&nonce_bytes),
            Payload { msg: &ciphertext, aad: &[] },
        )
        .map_err(|e| anyhow!("AES decryption failed: {e}"))?;
    Ok(plaintext)
}
