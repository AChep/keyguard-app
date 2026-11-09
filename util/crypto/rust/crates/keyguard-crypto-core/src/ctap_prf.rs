//! CTAP 2.2 sections 6.5.6, 6.5.7 and 12.7. No PIN/token operations are exposed.

use aes::Aes256;
use aws_lc_rs::constant_time;
use cbc::{Decryptor, Encryptor};
use cipher::{BlockDecryptMut, BlockEncryptMut, KeyIvInit, block_padding::NoPadding};
use p256::{PublicKey, SecretKey, elliptic_curve::sec1::ToEncodedPoint};
use zeroize::Zeroizing;

use crate::{
    passkeys::random_p256_secret_key,
    primitives::{self, PrimitiveError},
    protocol::{CtapPrfOutputSealRequest, CtapPrfSaltsOpenRequest, HashAlgorithm},
};

pub(crate) fn generate() -> Result<Vec<u8>, PrimitiveError> {
    let secret = random_p256_secret_key()?;
    let scalar = Zeroizing::new(secret.to_bytes());
    let public = secret.public_key().to_encoded_point(false);
    // Exact capacity: growing a buffer would free an unwiped copy of the scalar.
    let mut output = Zeroizing::new(Vec::with_capacity(scalar.len() + public.len()));
    output.extend_from_slice(&scalar);
    output.extend_from_slice(public.as_bytes());
    Ok(std::mem::take(&mut *output))
}

/// HMAC key then AES key, with room for `extra` bytes so appending never reallocates.
fn keys(
    request: &CtapPrfSaltsOpenRequest,
    extra: usize,
) -> Result<Zeroizing<Vec<u8>>, PrimitiveError> {
    if request.private_key.len() != 32 || request.peer_public_key.len() != 65 {
        return Err(PrimitiveError::InvalidArgument);
    }
    let private =
        SecretKey::from_slice(&request.private_key).map_err(|_| PrimitiveError::InvalidArgument)?;
    let public = PublicKey::from_sec1_bytes(&request.peer_public_key)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let shared = p256::ecdh::diffie_hellman(private.to_nonzero_scalar(), public.as_affine());
    let mut result = Zeroizing::new(Vec::with_capacity(64 + extra));
    match request.protocol {
        1 => {
            let key = Zeroizing::new(primitives::digest(
                HashAlgorithm::Sha256,
                shared.raw_secret_bytes().to_vec(),
            )?);
            result.extend_from_slice(&key);
            result.extend_from_slice(&key);
        }
        2 => {
            for label in [b"CTAP2 HMAC key".as_slice(), b"CTAP2 AES key".as_slice()] {
                let key = Zeroizing::new(primitives::hkdf_sha256(
                    shared.raw_secret_bytes().to_vec(),
                    Some(vec![0; 32]),
                    Some(label.to_vec()),
                    32,
                )?);
                result.extend_from_slice(&key);
            }
        }
        _ => return Err(PrimitiveError::InvalidArgument),
    }
    Ok(result)
}

pub(crate) fn open(request: &CtapPrfSaltsOpenRequest) -> Result<Vec<u8>, PrimitiveError> {
    let tag_size = match request.protocol {
        1 if matches!(request.salt_enc.len(), 32 | 64) => 16,
        2 if matches!(request.salt_enc.len(), 48 | 80) => 32,
        _ => return Err(PrimitiveError::InvalidArgument),
    };
    if request.salt_auth.len() != tag_size {
        return Err(PrimitiveError::AuthenticationFailed);
    }
    let mut keys = keys(request, request.salt_enc.len())?;
    let tag = Zeroizing::new(primitives::hmac(
        HashAlgorithm::Sha256,
        keys[..32].to_vec(),
        request.salt_enc.clone(),
    )?);
    constant_time::verify_slices_are_equal(&tag[..tag_size], &request.salt_auth)
        .map_err(|_| PrimitiveError::AuthenticationFailed)?;
    let (iv, encrypted) = if request.protocol == 1 {
        (&[0_u8; 16][..], request.salt_enc.as_slice())
    } else {
        request.salt_enc.split_at(16)
    };
    // Authenticate before exposing any decrypted salt.
    let mut salt = Zeroizing::new(encrypted.to_vec());
    Decryptor::<Aes256>::new_from_slices(&keys[32..], iv)
        .map_err(|_| PrimitiveError::InvalidArgument)?
        .decrypt_padded_mut::<NoPadding>(&mut salt)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    keys.extend_from_slice(&salt);
    Ok(std::mem::take(&mut *keys))
}

pub(crate) fn seal(request: &CtapPrfOutputSealRequest) -> Result<Vec<u8>, PrimitiveError> {
    if request.shared_secret.len() != 64 || !matches!(request.results.len(), 32 | 64) {
        return Err(PrimitiveError::InvalidArgument);
    }
    let iv = match request.protocol {
        1 => vec![0; 16],
        2 => primitives::random_bytes(16)?,
        _ => return Err(PrimitiveError::InvalidArgument),
    };
    let mut data = Zeroizing::new(request.results.clone());
    let length = data.len();
    Encryptor::<Aes256>::new_from_slices(&request.shared_secret[32..], &iv)
        .map_err(|_| PrimitiveError::InvalidArgument)?
        .encrypt_padded_mut::<NoPadding>(&mut data, length)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let mut output = if request.protocol == 2 {
        iv
    } else {
        Vec::new()
    };
    output.extend_from_slice(&data);
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn hex(value: &str) -> Vec<u8> {
        value
            .as_bytes()
            .as_chunks::<2>()
            .0
            .iter()
            .map(|b| u8::from_str_radix(std::str::from_utf8(b).unwrap(), 16).unwrap())
            .collect()
    }
    fn request(protocol: u32) -> CtapPrfSaltsOpenRequest {
        CtapPrfSaltsOpenRequest {
            protocol,
            private_key: vec![1; 32],
            peer_public_key: Vec::new(),
            salt_enc: Vec::new(),
            salt_auth: Vec::new(),
        }
    }

    /// Opens an independent vector, rejects tampering and an invalid point, then seals outputs.
    fn assert_vector(protocol: u32, salt_enc: &str, salt_auth: &str, expected_keys: &str) {
        let mut request = request(protocol);
        request.peer_public_key = hex(
            "04550f471003f3df97c3df506ac797f6721fb1a1fb7b8f6f83d224498a65c88e24136093d7012e509a73715cbd0b00a3cc0ff4b5c01b3ffa196ab1fb327036b8e6",
        );
        request.salt_enc = hex(salt_enc);
        request.salt_auth = hex(salt_auth);
        let expected_keys = hex(expected_keys);
        let salts = hex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
        );
        assert_eq!(
            open(&request).unwrap(),
            [expected_keys.as_slice(), salts.as_slice()].concat()
        );
        request.salt_auth[0] ^= 1;
        assert_eq!(open(&request), Err(PrimitiveError::AuthenticationFailed));
        request.salt_auth[0] ^= 1;
        request.peer_public_key.fill(0);
        assert_eq!(open(&request), Err(PrimitiveError::InvalidArgument));
        let request = CtapPrfOutputSealRequest {
            protocol,
            shared_secret: expected_keys,
            results: hex(
                "1f8dabf24c4078e5686392adfa224e013b769519f0918ae0d70ccd93b85c97729b94a219a504a4c9aedbc8dccaaa650a83b5ae605120a65c0aabcf3325cbce96",
            ),
        };
        let encrypted = seal(&request).unwrap();
        // Protocol 1 uses a zero IV; protocol 2 prepends a random one.
        let (iv, ciphertext) = if protocol == 1 {
            (&[0_u8; 16][..], encrypted.as_slice())
        } else {
            encrypted.split_at(16)
        };
        let mut decrypted = ciphertext.to_vec();
        Decryptor::<Aes256>::new_from_slices(&request.shared_secret[32..], iv)
            .unwrap()
            .decrypt_padded_mut::<NoPadding>(&mut decrypted)
            .unwrap();
        assert_eq!(decrypted, request.results);
    }

    #[test]
    fn independent_protocol_1_vector_and_tampering() {
        assert_vector(
            1,
            "5f468b3f91d5f68d0182007347ec276da4fdf7d1ea57e733271004b9ccc97a75410fd3fcfe9a16ea03a50cd9b0da842df6765ca94ec9cc34e7b748e75120201a",
            "c38c3b023c10bfb4ec2b35391f45f82d",
            "eea14853f0d2996406327d017b3a083c6a5602e7dd43fe71e50d8200f3cc4dc4eea14853f0d2996406327d017b3a083c6a5602e7dd43fe71e50d8200f3cc4dc4",
        );
    }

    #[test]
    fn independent_protocol_2_vector_and_tampering() {
        assert_vector(
            2,
            "03030303030303030303030303030303636a69320dba2de8d2aae8301833cb8fdb1e033dcb5b6b6ad9b5e47f2cf35dd6088dcbebf2ad072da730b49d1698e9890b89e533c79aa4740b5ea078143a2ba7",
            "219037efbc1b782951b50f8d2bbf8cc69dfddafc3cc6f059b8709ea6fff7ae0d",
            "1697f446d0f999cb5c8412e6860ae8d9b1906a602d2d9a29c1e8a82aa0603ff56d5d9935f17b2b28e5e3d287797094cf27519327f199e2f23f257428f5e559d5",
        );
    }

    #[test]
    fn rejects_invalid_protocol_and_salt_lengths() {
        let mut request = request(3);
        assert_eq!(open(&request), Err(PrimitiveError::InvalidArgument));
        request.protocol = 1;
        request.salt_enc = vec![0; 48];
        assert_eq!(open(&request), Err(PrimitiveError::InvalidArgument));
    }
}
