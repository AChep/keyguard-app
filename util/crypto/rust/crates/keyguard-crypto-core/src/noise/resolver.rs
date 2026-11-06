//! snow's primitives, provided by the reviewed backends of this crate.
//!
//! - ChaCha20-Poly1305 runs through [`primitives::chacha20_poly1305_in_place`],
//!   the AWS-LC AEAD the SSH agent transport uses. Each cipher keeps its key in
//!   a zeroizing buffer and builds the AWS-LC key per message.
//! - SHA-256 and HMAC-SHA256 run in the heap-resident AWS-LC contexts of
//!   `keyguard-crypto-sensitive`, which are cleansed when they are dropped.
//!   HMAC and HKDF replace snow's generic versions, which leave the padded key
//!   and the HKDF temporary key on snow's stack.
//! - X25519 comes from `x25519-dalek`, whose secrets zeroize on drop.
//! - Random bytes come from AWS-LC.
//!
//! What stays out of reach is snow's own state: the chaining key and the
//! handshake hash of its symmetric state, and the copies snow makes while it
//! works.
//!
//! snow's traits cannot report a backend failure from encryption, hashing or
//! HMAC. Those failures panic, as snow's own primitives do. Session steps
//! contain the panic and discard their state; the bridge contains construction
//! panics.

use std::sync::{Mutex, PoisonError};

use aws_lc_rs::rand::{SecureRandom, SystemRandom};
use keyguard_crypto_sensitive::{DigestAlgorithm, DigestContext, HmacContext};
use snow::{
    Error,
    params::{CipherChoice, DHChoice, HashChoice},
    resolvers::CryptoResolver,
    types::{Cipher, Dh, Hash, Random},
};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

use super::{KEY_BYTES, TAG_BYTES};
use crate::primitives::{self, CipherOperation, HMAC_SHA256_BYTES as HASH_BYTES};

/// Length of a SHA-256 block.
const HASH_BLOCK_BYTES: usize = 64;
/// Length of a ChaCha20-Poly1305 nonce.
const NONCE_BYTES: usize = 12;

/// Resolves the primitives of `Noise_*_25519_ChaChaPoly_SHA256`, and nothing else.
pub(super) struct Resolver;

impl CryptoResolver for Resolver {
    fn resolve_rng(&self) -> Option<Box<dyn Random>> {
        Some(Box::new(Rng))
    }

    fn resolve_dh(&self, choice: &DHChoice) -> Option<Box<dyn Dh>> {
        match choice {
            DHChoice::Curve25519 => Some(Box::new(X25519::default())),
            _ => None,
        }
    }

    fn resolve_hash(&self, choice: &HashChoice) -> Option<Box<dyn Hash>> {
        match choice {
            HashChoice::SHA256 => Some(Box::new(Sha256::new().ok()?)),
            _ => None,
        }
    }

    fn resolve_cipher(&self, choice: &CipherChoice) -> Option<Box<dyn Cipher>> {
        match choice {
            CipherChoice::ChaChaPoly => Some(Box::new(ChaChaPoly::new())),
            _ => None,
        }
    }
}

/// The operating-system random source, through AWS-LC.
struct Rng;

impl Random for Rng {
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), Error> {
        SystemRandom::new().fill(dest).map_err(|_| Error::Rng)
    }
}

/// An X25519 key pair. The secret zeroizes when it is replaced or dropped.
#[derive(Default)]
struct X25519 {
    secret: Option<StaticSecret>,
    public: [u8; KEY_BYTES],
}

impl Dh for X25519 {
    fn name(&self) -> &'static str {
        "25519"
    }

    fn pub_len(&self) -> usize {
        KEY_BYTES
    }

    fn priv_len(&self) -> usize {
        KEY_BYTES
    }

    fn set(&mut self, privkey: &[u8]) {
        // Keyguard validates static keys before building a handshake; generated
        // ephemeral keys also have this exact length. Snow does not check it.
        let bytes = Zeroizing::new(
            <[u8; KEY_BYTES]>::try_from(privkey)
                .expect("X25519 private keys must contain exactly 32 bytes"),
        );
        let secret = StaticSecret::from(*bytes);
        self.public = PublicKey::from(&secret).to_bytes();
        self.secret = Some(secret);
    }

    fn generate(&mut self, rng: &mut dyn Random) -> Result<(), Error> {
        let mut bytes = Zeroizing::new([0_u8; KEY_BYTES]);
        rng.try_fill_bytes(bytes.as_mut())?;
        self.set(bytes.as_ref());
        Ok(())
    }

    fn pubkey(&self) -> &[u8] {
        &self.public
    }

    fn privkey(&self) -> &[u8] {
        self.secret.as_ref().map_or(&[], |secret| secret.as_bytes())
    }

    fn dh(&self, pubkey: &[u8], out: &mut [u8]) -> Result<(), Error> {
        let secret = self.secret.as_ref().ok_or(Error::Dh)?;
        let public: [u8; KEY_BYTES] = pubkey
            .get(..KEY_BYTES)
            .and_then(|key| key.try_into().ok())
            .ok_or(Error::Dh)?;
        let shared = secret.diffie_hellman(&PublicKey::from(public));
        // A low-order peer key yields the all-zero secret. Reject it, as
        // libsodium and WireGuard do; the check is constant time.
        if !shared.was_contributory() {
            return Err(Error::Dh);
        }
        out.get_mut(..KEY_BYTES)
            .ok_or(Error::Dh)?
            .copy_from_slice(shared.as_bytes());
        Ok(())
    }
}

/// ChaCha20-Poly1305 with the Noise nonce: 32 zero bits, then the 64-bit
/// counter in little-endian order.
struct ChaChaPoly {
    key: Zeroizing<[u8; KEY_BYTES]>,
}

impl ChaChaPoly {
    fn new() -> Self {
        Self {
            key: Zeroizing::new([0; KEY_BYTES]),
        }
    }
}

fn noise_nonce(nonce: u64) -> [u8; NONCE_BYTES] {
    let mut bytes = [0_u8; NONCE_BYTES];
    bytes[4..].copy_from_slice(&nonce.to_le_bytes());
    bytes
}

impl Cipher for ChaChaPoly {
    fn name(&self) -> &'static str {
        "ChaChaPoly"
    }

    fn set(&mut self, key: &[u8; KEY_BYTES]) {
        self.key.copy_from_slice(key);
    }

    fn encrypt(&self, nonce: u64, authtext: &[u8], plaintext: &[u8], out: &mut [u8]) -> usize {
        // Room for the tag up front, so appending it never reallocates.
        let mut payload = Zeroizing::new(Vec::with_capacity(plaintext.len() + TAG_BYTES));
        payload.extend_from_slice(plaintext);
        primitives::chacha20_poly1305_in_place(
            CipherOperation::Encrypt,
            self.key.as_ref(),
            &noise_nonce(nonce),
            authtext,
            &mut payload,
        )
        .expect("AWS-LC must seal a ChaCha20-Poly1305 message");
        // snow sizes `out` for the plaintext and the tag.
        out[..payload.len()].copy_from_slice(&payload);
        payload.len()
    }

    fn decrypt(
        &self,
        nonce: u64,
        authtext: &[u8],
        ciphertext: &[u8],
        out: &mut [u8],
    ) -> Result<usize, Error> {
        if ciphertext.len() < TAG_BYTES || out.len() < ciphertext.len() - TAG_BYTES {
            return Err(Error::Decrypt);
        }
        // Opened in a zeroizing scratch buffer, so a forged message never
        // reaches `out`.
        let mut payload = Zeroizing::new(ciphertext.to_vec());
        primitives::chacha20_poly1305_in_place(
            CipherOperation::Decrypt,
            self.key.as_ref(),
            &noise_nonce(nonce),
            authtext,
            &mut payload,
        )
        .map_err(|_| Error::Decrypt)?;
        out[..payload.len()].copy_from_slice(&payload);
        Ok(payload.len())
    }
}

/// SHA-256 in a cleansed AWS-LC context.
///
/// The context is `Send` only, and snow requires `Sync`: the mutex provides
/// it. snow takes `&mut self` for every update, so the mutex is never locked.
struct Sha256 {
    context: Mutex<DigestContext>,
}

impl Sha256 {
    fn new() -> Result<Self, keyguard_crypto_sensitive::SensitiveBackendError> {
        Ok(Self {
            context: Mutex::new(DigestContext::new(DigestAlgorithm::Sha256)?),
        })
    }

    fn context(&mut self) -> &mut DigestContext {
        self.context
            .get_mut()
            .unwrap_or_else(PoisonError::into_inner)
    }
}

fn fresh_sha256() -> DigestContext {
    DigestContext::new(DigestAlgorithm::Sha256).expect("AWS-LC must allocate a SHA-256 context")
}

impl Hash for Sha256 {
    fn name(&self) -> &'static str {
        "SHA256"
    }

    fn block_len(&self) -> usize {
        HASH_BLOCK_BYTES
    }

    fn hash_len(&self) -> usize {
        HASH_BYTES
    }

    fn reset(&mut self) {
        // Dropping the old context cleanses it.
        *self.context() = fresh_sha256();
    }

    fn input(&mut self, data: &[u8]) {
        self.context()
            .update(data)
            .expect("AWS-LC must hash the input");
    }

    fn result(&mut self, out: &mut [u8]) {
        self.context()
            .finalize_into(&mut out[..HASH_BYTES])
            .expect("AWS-LC must finish the digest");
        // As snow's own SHA-256 does, a result leaves a fresh state behind.
        self.reset();
    }

    fn hmac(&mut self, key: &[u8], data: &[u8], out: &mut [u8]) {
        let mut context = HmacContext::new(DigestAlgorithm::Sha256, key)
            .expect("AWS-LC must allocate an HMAC context");
        context.update(data).expect("AWS-LC must MAC the input");
        context
            .finalize_into(&mut out[..HASH_BYTES])
            .expect("AWS-LC must finish the MAC");
    }

    /// HKDF of the Noise specification, section 4.3: RFC 5869 with the
    /// chaining key as the salt and no info, with zeroized intermediates.
    fn hkdf(
        &mut self,
        chaining_key: &[u8],
        input_key_material: &[u8],
        outputs: usize,
        out1: &mut [u8],
        out2: &mut [u8],
        out3: &mut [u8],
    ) {
        let length = u32::try_from(outputs * HASH_BYTES).expect("snow asks for at most 3 outputs");
        let derived = Zeroizing::new(
            primitives::hkdf_sha256(
                input_key_material.to_vec(),
                Some(chaining_key.to_vec()),
                None,
                length,
            )
            .expect("AWS-LC must derive the HKDF outputs"),
        );
        let (blocks, _) = derived.as_chunks::<HASH_BYTES>();
        for (out, block) in [out1, out2, out3].into_iter().zip(blocks) {
            out[..HASH_BYTES].copy_from_slice(block);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(value: &str) -> Vec<u8> {
        (0..value.len())
            .step_by(2)
            .map(|index| u8::from_str_radix(&value[index..index + 2], 16).expect("test hex"))
            .collect()
    }

    #[test]
    fn x25519_refuses_private_keys_of_the_wrong_size() {
        for length in [KEY_BYTES - 1, KEY_BYTES + 1] {
            let result = std::panic::catch_unwind(|| {
                let mut key = X25519::default();
                key.set(&vec![7; length]);
            });
            assert!(
                result.is_err(),
                "a {length}-byte key must not be normalized"
            );
        }
    }

    #[test]
    fn x25519_matches_rfc_7748() {
        // RFC 7748, section 6.1.
        let mut alice = X25519::default();
        alice.set(&hex(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
        ));
        assert_eq!(
            alice.pubkey(),
            hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        );
        let mut shared = [0_u8; KEY_BYTES];
        alice
            .dh(
                &hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"),
                &mut shared,
            )
            .expect("dh");
        assert_eq!(
            shared.as_slice(),
            hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        );
    }

    #[test]
    fn a_low_order_public_key_is_rejected() {
        let mut dh = X25519::default();
        dh.set(&[7; KEY_BYTES]);
        let mut out = [0xff; KEY_BYTES];
        assert_eq!(dh.dh(&[0; KEY_BYTES], &mut out), Err(Error::Dh));
        // u = 1 has order 4; a clamped scalar maps it to zero as well.
        let mut order_four = [0; KEY_BYTES];
        order_four[0] = 1;
        assert_eq!(dh.dh(&order_four, &mut out), Err(Error::Dh));
        assert_eq!(out, [0xff; KEY_BYTES], "a rejected exchange writes nothing");
    }

    #[test]
    fn chachapoly_round_trips_and_rejects_tampering_without_output() {
        // Noise section 5.1: 32 zero bits, then the counter, little endian.
        // The Cacophony vectors in the parent module check the full AEAD.
        assert_eq!(
            noise_nonce(0x0807_0605_0403_0201),
            [0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8]
        );
        let mut cipher = ChaChaPoly::new();
        cipher.set(&[0x42; KEY_BYTES]);
        let plaintext = b"Ladies and Gentlemen of the class of '99";
        let mut sealed = vec![0_u8; plaintext.len() + TAG_BYTES];
        let length = cipher.encrypt(5, b"ad", plaintext, &mut sealed);
        assert_eq!(length, sealed.len());
        let mut opened = vec![0_u8; plaintext.len()];
        assert_eq!(
            cipher.decrypt(5, b"ad", &sealed, &mut opened),
            Ok(plaintext.len())
        );
        assert_eq!(opened, plaintext);

        for (nonce, authtext) in [(6, &b"ad"[..]), (5, &b"da"[..])] {
            let mut out = vec![0xAA; plaintext.len()];
            assert_eq!(
                cipher.decrypt(nonce, authtext, &sealed, &mut out),
                Err(Error::Decrypt)
            );
            assert!(out.iter().all(|&byte| byte == 0xAA), "no byte may leak");
        }
        let mut out = vec![0; 0];
        assert_eq!(
            cipher.decrypt(5, b"ad", &sealed[..TAG_BYTES - 1], &mut out),
            Err(Error::Decrypt)
        );
    }

    #[test]
    fn hmac_and_hkdf_match_rfc_vectors() {
        let mut hash = Sha256::new().expect("sha256");
        // RFC 4231, test case 2.
        let mut out = [0_u8; 64];
        hash.hmac(b"Jefe", b"what do ya want for nothing?", &mut out);
        assert_eq!(
            &out[..HASH_BYTES],
            hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
        );
        assert_eq!(&out[HASH_BYTES..], [0; 32], "only the digest is written");

        // RFC 5869, test case 3: no salt and no info, which is the Noise HKDF
        // with an all-zero chaining key. The 42-byte OKM covers the first
        // output and the start of the second.
        let ikm = [0x0b_u8; 22];
        let (mut out1, mut out2) = ([0_u8; 64], [0_u8; 64]);
        hash.hkdf(&[0; HASH_BYTES], &ikm, 2, &mut out1, &mut out2, &mut []);
        let okm = hex(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
        );
        assert_eq!(&out1[..HASH_BYTES], &okm[..32]);
        assert_eq!(&out2[..10], &okm[32..]);

        // SHA-256 of "abc", FIPS 180-2; a result leaves a fresh state.
        for _ in 0..2 {
            hash.input(b"abc");
            let mut digest = [0_u8; 64];
            hash.result(&mut digest);
            assert_eq!(
                &digest[..HASH_BYTES],
                hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            );
        }
    }
}
