use super::CryptoError;
use aes::cipher::{BlockDecryptMut, BlockEncryptMut, KeyIvInit};
use hmac::Mac;
use p256::elliptic_curve::sec1::FromEncodedPoint;
use rand_core::RngCore;
use sha2::Digest;
use std::convert::TryInto;

pub type Result<T> = std::result::Result<T, CryptoError>;

fn cose_key_to_public(peer: &super::COSEEC2Key) -> Result<p256::PublicKey> {
    // SEC 1 encoded uncompressed point
    let peer = p256::EncodedPoint::from_affine_coordinates(
        peer.x
            .as_slice()
            .try_into()
            .map_err(|_| CryptoError::MalformedInput)?,
        peer.y
            .as_slice()
            .try_into()
            .map_err(|_| CryptoError::MalformedInput)?,
        false,
    );
    p256::PublicKey::from_encoded_point(&peer)
        .into_option()
        .ok_or(CryptoError::LibraryFailure)
}

/// Ephemeral ECDH over P256. Generates an ephemeral P256 key pair. Returns
///  1) the x coordinate of the shared point, and
///  2) the uncompressed SEC 1 encoding of the ephemeral public key.
pub fn ecdhe_p256_raw(peer: &super::COSEEC2Key) -> Result<(Vec<u8>, Vec<u8>)> {
    let peer_public = cose_key_to_public(peer)?;

    let internal_private = p256::ecdh::EphemeralSecret::random(&mut rand_core::OsRng);
    let internal_public = internal_private.public_key().to_sec1_bytes().into_vec();

    let shared_point = internal_private.diffie_hellman(&peer_public);

    Ok((shared_point.raw_secret_bytes().to_vec(), internal_public))
}

type Aes256CbcEnc = cbc::Encryptor<aes::Aes256>;
type Aes256CbcDec = cbc::Decryptor<aes::Aes256>;

const AES_BLOCK_SIZE: usize = 16;

pub fn encrypt_aes_256_cbc_no_pad(key: &[u8], iv: Option<&[u8]>, data: &[u8]) -> Result<Vec<u8>> {
    let key: [u8; 32] = match key.try_into() {
        Ok(key) => key,
        Err(_) => return Err(CryptoError::LibraryFailure),
    };

    let iv = iv.unwrap_or(&[0u8; AES_BLOCK_SIZE]);
    let iv = match iv.try_into() {
        Ok(iv) => iv,
        Err(_) => return Err(CryptoError::LibraryFailure),
    };

    // Validate that the data is an exact multiple of the block size since we have no
    // padding available.
    let blocks = data.chunks_exact(AES_BLOCK_SIZE);
    if !blocks.remainder().is_empty() {
        return Err(CryptoError::LibraryFailure);
    }

    let mut encryptor = Aes256CbcEnc::new(&key.into(), iv);

    // Since we now know that `data` is a multiple of `AES_BLOCK_SIZE`, so this will always have the
    // same number of blocks as it.
    let mut ciphertext = vec![0u8; data.len()];
    // XXX: `slice::as_chunks` would be better but it requires an MSRV of 1.88.
    for (input_block, output_block) in blocks
        .into_iter()
        .zip(ciphertext.chunks_exact_mut(AES_BLOCK_SIZE))
    {
        let input: &[u8; AES_BLOCK_SIZE] = input_block.try_into().unwrap();
        let output: &mut [u8; AES_BLOCK_SIZE] = output_block.try_into().unwrap();

        encryptor.encrypt_block_b2b_mut(input.into(), output.into());
        debug_assert_ne!(output, &[0u8; AES_BLOCK_SIZE]);
    }

    Ok(ciphertext)
}

pub fn decrypt_aes_256_cbc_no_pad(key: &[u8], iv: Option<&[u8]>, data: &[u8]) -> Result<Vec<u8>> {
    let key: [u8; 32] = match key.try_into() {
        Ok(key) => key,
        Err(_) => return Err(CryptoError::LibraryFailure),
    };

    let iv = iv.unwrap_or(&[0u8; AES_BLOCK_SIZE]);
    let iv = match iv.try_into() {
        Ok(iv) => iv,
        Err(_) => return Err(CryptoError::LibraryFailure),
    };

    // See comments in `encrypt_aes_256_cbc_no_pad` for rationale.
    let blocks = data.chunks_exact(AES_BLOCK_SIZE);
    if !blocks.remainder().is_empty() {
        return Err(CryptoError::LibraryFailure);
    }

    let mut decryptor = Aes256CbcDec::new(&key.into(), iv);
    let mut plaintext = vec![0u8; data.len()];
    for (input_block, output_block) in blocks
        .into_iter()
        .zip(plaintext.chunks_exact_mut(AES_BLOCK_SIZE))
    {
        let input: &[u8; AES_BLOCK_SIZE] = input_block.try_into().unwrap();
        let output: &mut [u8; AES_BLOCK_SIZE] = output_block.try_into().unwrap();

        decryptor.decrypt_block_b2b_mut(input.into(), output.into());
        debug_assert_ne!(output, &[0u8; AES_BLOCK_SIZE]);
    }

    Ok(plaintext)
}

type HmacSha256 = hmac::Hmac<sha2::Sha256>;

pub fn hmac_sha256(key: &[u8], data: &[u8]) -> Result<Vec<u8>> {
    let mut key = HmacSha256::new_from_slice(key)
        .map_err(|_| CryptoError::Backend(String::from("InvalidLength")))?;

    key.update(data);
    Ok(key.finalize().into_bytes().to_vec())
}

pub fn sha256(data: &[u8]) -> Result<Vec<u8>> {
    let digest = sha2::Sha256::digest(data);
    Ok(digest.to_vec())
}

pub fn random_bytes(count: usize) -> Result<Vec<u8>> {
    let mut rng = rand_core::OsRng;
    let mut out = vec![0u8; count];
    rng.try_fill_bytes(out.as_mut_slice())
        .map_err(|_| CryptoError::LibraryFailure)?;
    Ok(out)
}

#[cfg(test)]
pub fn test_ecdh_p256_raw(
    peer: &super::COSEEC2Key,
    _client_public_x: &[u8],
    _client_public_y: &[u8],
    client_private: &[u8],
) -> Result<Vec<u8>> {
    let peer_public = cose_key_to_public(peer)?;

    let client_private = p256::SecretKey::from_slice(client_private).unwrap();
    let shared_point =
        p256::ecdh::diffie_hellman(client_private.to_nonzero_scalar(), peer_public.as_affine());

    Ok(shared_point.raw_secret_bytes().to_vec())
}

pub fn gen_p256() -> Result<(Vec<u8>, Vec<u8>)> {
    unimplemented!()
}

pub fn ecdsa_p256_sha256_sign_raw(_private: &[u8], _data: &[u8]) -> Result<Vec<u8>> {
    unimplemented!()
}

#[allow(dead_code)]
#[cfg(test)]
pub fn test_ecdsa_p256_sha256_verify_raw(
    _public: &[u8],
    _signature: &[u8],
    _data: &[u8],
) -> Result<()> {
    unimplemented!()
}
