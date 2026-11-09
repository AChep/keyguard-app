//! QR-initiated FIDO hybrid transport (CTAP 2.2, section 11.5).

use aws_lc_rs::aead::AES_256_GCM;
use p256::{PublicKey, SecretKey, elliptic_curve::sec1::ToEncodedPoint};
use zeroize::Zeroizing;

use crate::{
    passkeys::random_p256_secret_key,
    primitives::{self, CipherOperation, PrimitiveError},
    protocol::{CipherDirection, HashAlgorithm, HybridHandshakeRequest, HybridTrafficRequest},
};

const MAX_FRAME: usize = 65_536;
const POINT_BYTES: usize = 65;
const TAG_BYTES: usize = 16;

pub(crate) fn handshake(request: &HybridHandshakeRequest) -> Result<Vec<u8>, PrimitiveError> {
    if request.psk.len() != 32 {
        return Err(PrimitiveError::InvalidArgument);
    }
    let secret = random_p256_secret_key()?;
    respond(
        &request.peer_identity,
        &request.psk,
        &request.message,
        &secret,
    )
}

fn respond(
    identity: &[u8],
    psk: &[u8],
    message: &[u8],
    secret: &SecretKey,
) -> Result<Vec<u8>, PrimitiveError> {
    if identity.len() != 33 || message.len() != POINT_BYTES + TAG_BYTES {
        return Err(PrimitiveError::InvalidArgument);
    }
    let identity =
        PublicKey::from_sec1_bytes(identity).map_err(|_| PrimitiveError::InvalidArgument)?;
    let peer = PublicKey::from_sec1_bytes(&message[..POINT_BYTES])
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let mut state = Noise::new(identity.to_encoded_point(false).as_bytes(), psk)?;
    state.mix_hash(&message[..POINT_BYTES])?;
    state.mix_key(&message[..POINT_BYTES])?;
    let plaintext = state.decrypt_and_hash(&message[POINT_BYTES..])?;
    if !plaintext.is_empty() {
        return Err(PrimitiveError::AuthenticationFailed);
    }

    let public = secret.public_key().to_encoded_point(false);
    state.mix_hash(public.as_bytes())?;
    state.mix_key(public.as_bytes())?;
    for public in [&peer, &identity] {
        let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), public.as_affine());
        state.mix_key(shared.raw_secret_bytes())?;
    }
    let tag = state.encrypt_and_hash(&[])?;
    // Noise Split: initiator-to-responder first, responder-to-initiator second.
    let keys = derive(&state.ck, &[], 64)?;
    let mut reply = Zeroizing::new(Vec::with_capacity(POINT_BYTES + TAG_BYTES + keys.len()));
    reply.extend_from_slice(public.as_bytes());
    reply.extend_from_slice(&tag);
    reply.extend_from_slice(&keys);
    Ok(std::mem::take(&mut *reply))
}

struct Noise {
    ck: Zeroizing<Vec<u8>>,
    hash: Vec<u8>,
    key: Zeroizing<Vec<u8>>,
}

impl Noise {
    fn new(identity: &[u8], psk: &[u8]) -> Result<Self, PrimitiveError> {
        let name = b"Noise_KNpsk0_P256_AESGCM_SHA256";
        let mut hash = vec![0; 32];
        hash[..name.len()].copy_from_slice(name);
        let mut state = Self {
            ck: Zeroizing::new(hash.clone()),
            hash,
            key: Zeroizing::new(Vec::with_capacity(32)),
        };
        state.mix_hash(&[1])?;
        state.mix_hash(identity)?;
        let derived = derive(&state.ck, psk, 96)?;
        state.ck.copy_from_slice(&derived[..32]);
        state.mix_hash(&derived[32..64])?;
        state.key.extend_from_slice(&derived[64..]);
        Ok(state)
    }

    fn mix_hash(&mut self, bytes: &[u8]) -> Result<(), PrimitiveError> {
        let mut input = self.hash.clone();
        input.extend_from_slice(bytes);
        self.hash = primitives::digest(HashAlgorithm::Sha256, input)?;
        Ok(())
    }

    fn mix_key(&mut self, bytes: &[u8]) -> Result<(), PrimitiveError> {
        let derived = derive(&self.ck, bytes, 64)?;
        self.ck.copy_from_slice(&derived[..32]);
        self.key.copy_from_slice(&derived[32..]);
        Ok(())
    }

    fn decrypt_and_hash(&mut self, bytes: &[u8]) -> Result<Zeroizing<Vec<u8>>, PrimitiveError> {
        let result = crypt(
            CipherOperation::Decrypt,
            &self.key,
            [0; 12],
            &self.hash,
            buffer(bytes, 0),
        )?;
        self.mix_hash(bytes)?;
        Ok(Zeroizing::new(result))
    }

    fn encrypt_and_hash(&mut self, bytes: &[u8]) -> Result<Vec<u8>, PrimitiveError> {
        let result = crypt(
            CipherOperation::Encrypt,
            &self.key,
            [0; 12],
            &self.hash,
            buffer(bytes, TAG_BYTES),
        )?;
        self.mix_hash(&result)?;
        Ok(result)
    }
}

fn derive(salt: &[u8], ikm: &[u8], size: u32) -> Result<Zeroizing<Vec<u8>>, PrimitiveError> {
    primitives::hkdf_sha256(ikm.to_vec(), Some(salt.to_vec()), Some(Vec::new()), size)
        .map(Zeroizing::new)
}

/// A secret copy of `input` with room for `extra` bytes, so sealing never reallocates.
fn buffer(input: &[u8], extra: usize) -> Zeroizing<Vec<u8>> {
    let mut buffer = Zeroizing::new(Vec::with_capacity(input.len() + extra));
    buffer.extend_from_slice(input);
    buffer
}

fn crypt(
    operation: CipherOperation,
    key: &[u8],
    nonce: [u8; 12],
    aad: &[u8],
    mut buffer: Zeroizing<Vec<u8>>,
) -> Result<Vec<u8>, PrimitiveError> {
    primitives::aead_in_place(&AES_256_GCM, operation, key, &nonce, aad, &mut buffer)?;
    Ok(std::mem::take(&mut *buffer))
}

pub(crate) fn traffic(
    direction: CipherDirection,
    request: &HybridTrafficRequest,
) -> Result<Vec<u8>, PrimitiveError> {
    if request.key.len() != 32 || request.counter > 0xff_ffff {
        return Err(PrimitiveError::InvalidArgument);
    }
    if request.data.len() > MAX_FRAME {
        return Err(PrimitiveError::ResourceLimit);
    }
    let mut nonce = [0; 12];
    nonce[8..].copy_from_slice(&request.counter.to_be_bytes());
    if direction == CipherDirection::Encrypt {
        // Reserve room for the padding and authentication tag within MAX_FRAME.
        if request.data.len() > MAX_FRAME - 32 - TAG_BYTES {
            return Err(PrimitiveError::ResourceLimit);
        }
        let padding = 32 - request.data.len() % 32;
        let mut padded = buffer(&request.data, padding + TAG_BYTES);
        padded.resize(request.data.len() + padding, 0);
        let last = padded.last_mut().ok_or(PrimitiveError::Internal)?;
        *last = u8::try_from(padding - 1).map_err(|_| PrimitiveError::Internal)?;
        crypt(CipherOperation::Encrypt, &request.key, nonce, &[], padded)
    } else {
        let mut output = Zeroizing::new(crypt(
            CipherOperation::Decrypt,
            &request.key,
            nonce,
            &[],
            buffer(&request.data, 0),
        )?);
        let padding = usize::from(*output.last().ok_or(PrimitiveError::AuthenticationFailed)?) + 1;
        let length = output
            .len()
            .checked_sub(padding)
            .ok_or(PrimitiveError::AuthenticationFailed)?;
        output.truncate(length);
        Ok(std::mem::take(&mut *output))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn qr_handshake_authenticates_both_sides_and_splits_directional_keys() {
        let identity = SecretKey::from_slice(&[1; 32]).unwrap();
        let browser = SecretKey::from_slice(&[2; 32]).unwrap();
        let phone = SecretKey::from_slice(&[3; 32]).unwrap();
        let psk = [4; 32];
        let public = identity.public_key().to_encoded_point(false);
        let ephemeral = browser.public_key().to_encoded_point(false);
        let mut initiator = Noise::new(public.as_bytes(), &psk).unwrap();
        initiator.mix_hash(ephemeral.as_bytes()).unwrap();
        initiator.mix_key(ephemeral.as_bytes()).unwrap();
        let mut message = ephemeral.as_bytes().to_vec();
        message.extend_from_slice(&initiator.encrypt_and_hash(&[]).unwrap());
        let reply = respond(
            identity.public_key().to_encoded_point(true).as_bytes(),
            &psk,
            &message,
            &phone,
        )
        .unwrap();
        assert_eq!(reply.len(), 145);
        initiator.mix_hash(&reply[..65]).unwrap();
        initiator.mix_key(&reply[..65]).unwrap();
        let peer = PublicKey::from_sec1_bytes(&reply[..65]).unwrap();
        for secret in [&browser, &identity] {
            let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), peer.as_affine());
            initiator.mix_key(shared.raw_secret_bytes()).unwrap();
        }
        assert!(
            initiator
                .decrypt_and_hash(&reply[65..81])
                .unwrap()
                .is_empty()
        );
        assert_eq!(&reply[81..], &*derive(&initiator.ck, &[], 64).unwrap());
        message[80] ^= 1;
        assert!(
            respond(
                identity.public_key().to_encoded_point(true).as_bytes(),
                &psk,
                &message,
                &phone
            )
            .is_err()
        );
        assert!(respond(&[0; 33], &psk, &message, &phone).is_err());
    }

    // Independent Python cryptography/HMAC implementation of CTAP 2.2 section 11.5.
    // Static identity, browser ephemeral, phone ephemeral and PSK are [1], [2], [3], [4].
    #[test]
    fn matches_independent_noise_vector() {
        fn hex(value: &str) -> Vec<u8> {
            value
                .as_bytes()
                .as_chunks::<2>()
                .0
                .iter()
                .map(|b| u8::from_str_radix(std::str::from_utf8(b).unwrap(), 16).unwrap())
                .collect()
        }
        let identity = hex("026ff03b949241ce1dadd43519e6960e0a85b41a69a05c328103aa2bce1594ca16");
        let message = hex(
            "04550f471003f3df97c3df506ac797f6721fb1a1fb7b8f6f83d224498a65c88e24136093d7012e509a73715cbd0b00a3cc0ff4b5c01b3ffa196ab1fb327036b8e6dabb175195350d7bac1c1a654d6b4871",
        );
        let expected = hex(
            "04591ab771ebbcfd6d9cb9094d106528add1a69d44c2c1f627f089ec58b9c61adf9f4e6abf0d045c0c693a3c68ad7c97ca72be64def4a26fecd263dd98a92780f05eacdb22639d93fb37da603d80b83e780674a8c6cbc8134b3459cd638ee7ad2d19fcc51031808e76a392bd7287c976a744465982cad604f2202da07804988b32144aafa690e802d017259b7d2d959fa4",
        );
        let secret = SecretKey::from_slice(&[3; 32]).unwrap();
        assert_eq!(
            respond(&identity, &[4; 32], &message, &secret).unwrap(),
            expected
        );
    }

    #[test]
    fn traffic_rejects_wrong_key_counter_and_padding() {
        let mut request = HybridTrafficRequest {
            direction: CipherDirection::Encrypt as i32,
            key: vec![5; 32],
            data: vec![1, 2, 3],
            counter: 0,
        };
        let encrypted = traffic(CipherDirection::Encrypt, &request).unwrap();
        assert_eq!(encrypted.len(), 48);
        let decrypt = CipherDirection::Decrypt;
        request.data = encrypted;
        assert_eq!(traffic(decrypt, &request).unwrap(), [1, 2, 3]);
        request.counter = 1;
        assert!(traffic(decrypt, &request).is_err());
        request.counter = 0;
        request.key[0] ^= 1;
        assert!(traffic(decrypt, &request).is_err());
        request.key[0] ^= 1;
        request.data = crypt(
            CipherOperation::Encrypt,
            &request.key,
            [0; 12],
            &[],
            buffer(&[255], TAG_BYTES),
        )
        .unwrap();
        assert!(traffic(decrypt, &request).is_err());
        request.counter = 0x100_0000;
        assert!(traffic(decrypt, &request).is_err());
    }
}
