//! Decrypt-only compatibility for historical OpenSSL PEM encryption.
//!
//! This module reproduces Bouncy Castle's `PEMUtilities.crypt(false, ...)`
//! behavior for private-key import. These weak algorithms are deliberately not
//! exposed for encryption or generation.

use aes::{Aes128, Aes192, Aes256};
use blowfish::Blowfish;
use cipher::{Block, BlockCipher, BlockDecrypt, BlockEncrypt, KeyInit};
use des::{Des, TdesEde3};
use keyguard_crypto_sensitive::{DigestAlgorithm, DigestContext};
use rc2::Rc2;
use thiserror::Error;
use zeroize::{Zeroize, Zeroizing};

const MAX_ALGORITHM_NAME_BYTES: usize = 64;
const MAX_PASSPHRASE_BYTES: usize = 64 * 1024;
const MAX_IV_BYTES: usize = 16;
const MIN_IV_BYTES: usize = 8;
const MAX_CIPHERTEXT_BYTES: usize = crate::MAX_CONTROL_ENVELOPE_BYTES - 1024;
const MD5_BYTES: usize = 16;

/// Failure classification for legacy OpenSSL PEM decryption.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub(crate) enum LegacyPemError {
    /// The DEK algorithm is syntactically valid but is not in the frozen
    /// decrypt-only compatibility matrix.
    #[error("unsupported legacy PEM algorithm")]
    UnsupportedAlgorithm,
    /// A header field, IV, or ciphertext violates the legacy PEM structure.
    #[error("malformed legacy PEM input")]
    Malformed,
    /// CBC/ECB padding rejected the supplied passphrase or ciphertext.
    #[error("legacy PEM authentication failed")]
    InvalidPassphrase,
    /// An explicit input bound was exceeded.
    #[error("legacy PEM resource limit exceeded")]
    ResourceLimit,
    /// A reviewed cryptographic backend rejected an otherwise valid request.
    #[error("legacy PEM backend failure")]
    Backend,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum LegacyAlgorithm {
    Aes128,
    Aes192,
    Aes256,
    Blowfish,
    Des,
    Rc2_40,
    Rc2_64,
    Rc2_128,
    TripleDes2,
    TripleDes3,
}

impl LegacyAlgorithm {
    const fn block_size(self) -> usize {
        match self {
            Self::Aes128 | Self::Aes192 | Self::Aes256 => 16,
            Self::Blowfish
            | Self::Des
            | Self::Rc2_40
            | Self::Rc2_64
            | Self::Rc2_128
            | Self::TripleDes2
            | Self::TripleDes3 => 8,
        }
    }

    const fn key_size(self) -> usize {
        match self {
            Self::Aes128 | Self::Blowfish | Self::Rc2_128 => 16,
            Self::Aes192 => 24,
            Self::Aes256 => 32,
            Self::Des => 8,
            Self::Rc2_40 => 5,
            Self::Rc2_64 => 8,
            Self::TripleDes2 | Self::TripleDes3 => 24,
        }
    }

    const fn uses_aes_salt_rule(self) -> bool {
        matches!(self, Self::Aes128 | Self::Aes192 | Self::Aes256)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum LegacyMode {
    Cbc,
    Cfb,
    Ecb,
    Ofb,
}

impl LegacyMode {
    const fn uses_iv(self) -> bool {
        !matches!(self, Self::Ecb)
    }

    const fn uses_padding(self) -> bool {
        matches!(self, Self::Cbc | Self::Ecb)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct LegacyCipherSpec {
    algorithm: LegacyAlgorithm,
    mode: LegacyMode,
}

/// Decrypts a traditional OpenSSL PEM body using the historical
/// `EVP_BytesToKey` MD5 construction used by Bouncy Castle 1.84.
///
/// `passphrase_utf8` is the UTF-8 representation received over the native ABI.
/// It is converted to the low byte of each UTF-16 code unit, matching BC's
/// `PBEParametersGenerator.PKCS5PasswordToBytes(char[])` behavior.
///
/// # Errors
///
/// Returns [`LegacyPemError`] for unsupported algorithms, malformed or
/// oversized input, invalid PKCS#7 padding, and backend failures.
pub(crate) fn decrypt_legacy_openssl_pem(
    dek_algorithm: &str,
    passphrase_utf8: &[u8],
    iv: &[u8],
    ciphertext: &[u8],
) -> Result<Zeroizing<Vec<u8>>, LegacyPemError> {
    validate_bounds(dek_algorithm, passphrase_utf8, iv, ciphertext)?;
    let spec = parse_cipher_spec(dek_algorithm)?;
    if spec.mode.uses_iv() && iv.len() != spec.algorithm.block_size() {
        return Err(LegacyPemError::Malformed);
    }

    let password = bc_pkcs5_password_bytes(passphrase_utf8)?;
    let salt = if spec.algorithm.uses_aes_salt_rule() && iv.len() > 8 {
        &iv[..8]
    } else {
        iv
    };
    let mut key = evp_bytes_to_key_md5(&password, salt, spec.algorithm.key_size())?;
    if spec.algorithm == LegacyAlgorithm::TripleDes2 {
        let first_component = key[..8].to_vec();
        key[16..24].copy_from_slice(&first_component);
        let mut first_component = first_component;
        first_component.zeroize();
    }

    match spec.algorithm {
        LegacyAlgorithm::Aes128 => decrypt_with::<Aes128>(spec.mode, &key, iv, ciphertext),
        LegacyAlgorithm::Aes192 => decrypt_with::<Aes192>(spec.mode, &key, iv, ciphertext),
        LegacyAlgorithm::Aes256 => decrypt_with::<Aes256>(spec.mode, &key, iv, ciphertext),
        LegacyAlgorithm::Blowfish => decrypt_with::<Blowfish>(spec.mode, &key, iv, ciphertext),
        LegacyAlgorithm::Des => decrypt_with::<Des>(spec.mode, &key, iv, ciphertext),
        LegacyAlgorithm::Rc2_40 | LegacyAlgorithm::Rc2_64 | LegacyAlgorithm::Rc2_128 => {
            decrypt_with::<Rc2>(spec.mode, &key, iv, ciphertext)
        }
        LegacyAlgorithm::TripleDes2 | LegacyAlgorithm::TripleDes3 => {
            decrypt_with::<TdesEde3>(spec.mode, &key, iv, ciphertext)
        }
    }
}

fn validate_bounds(
    dek_algorithm: &str,
    passphrase_utf8: &[u8],
    iv: &[u8],
    ciphertext: &[u8],
) -> Result<(), LegacyPemError> {
    if dek_algorithm.len() > MAX_ALGORITHM_NAME_BYTES
        || passphrase_utf8.len() > MAX_PASSPHRASE_BYTES
        || ciphertext.len() > MAX_CIPHERTEXT_BYTES
    {
        return Err(LegacyPemError::ResourceLimit);
    }
    if !(MIN_IV_BYTES..=MAX_IV_BYTES).contains(&iv.len()) {
        return Err(LegacyPemError::Malformed);
    }
    Ok(())
}

fn parse_cipher_spec(dek_algorithm: &str) -> Result<LegacyCipherSpec, LegacyPemError> {
    // Keep the three independent mode checks and their ordering aligned with
    // BC PEMUtilities. Unrecognized suffixes retain its default CBC mode.
    let mut mode = LegacyMode::Cbc;
    if dek_algorithm.ends_with("-CFB") {
        mode = LegacyMode::Cfb;
    }
    if dek_algorithm.ends_with("-ECB") || dek_algorithm == "DES-EDE" || dek_algorithm == "DES-EDE3"
    {
        mode = LegacyMode::Ecb;
    }
    if dek_algorithm.ends_with("-OFB") {
        mode = LegacyMode::Ofb;
    }

    let algorithm = if dek_algorithm.starts_with("DES-EDE") {
        if dek_algorithm.starts_with("DES-EDE3") {
            LegacyAlgorithm::TripleDes3
        } else {
            LegacyAlgorithm::TripleDes2
        }
    } else if dek_algorithm.starts_with("DES-") {
        LegacyAlgorithm::Des
    } else if dek_algorithm.starts_with("BF-") {
        LegacyAlgorithm::Blowfish
    } else if dek_algorithm.starts_with("RC2-") {
        if dek_algorithm.starts_with("RC2-40-") {
            LegacyAlgorithm::Rc2_40
        } else if dek_algorithm.starts_with("RC2-64-") {
            LegacyAlgorithm::Rc2_64
        } else {
            LegacyAlgorithm::Rc2_128
        }
    } else if dek_algorithm.starts_with("AES-128-") {
        LegacyAlgorithm::Aes128
    } else if dek_algorithm.starts_with("AES-192-") {
        LegacyAlgorithm::Aes192
    } else if dek_algorithm.starts_with("AES-256-") {
        LegacyAlgorithm::Aes256
    } else {
        return Err(LegacyPemError::UnsupportedAlgorithm);
    };

    Ok(LegacyCipherSpec { algorithm, mode })
}

fn bc_pkcs5_password_bytes(passphrase_utf8: &[u8]) -> Result<Zeroizing<Vec<u8>>, LegacyPemError> {
    let passphrase = std::str::from_utf8(passphrase_utf8).map_err(|_| LegacyPemError::Malformed)?;
    let mut output = Zeroizing::new(Vec::with_capacity(passphrase.len()));
    let mut encoded = [0_u16; 2];
    for character in passphrase.chars() {
        for code_unit in character.encode_utf16(&mut encoded).iter().copied() {
            output.push(code_unit as u8);
        }
        encoded.zeroize();
    }
    Ok(output)
}

fn evp_bytes_to_key_md5(
    password: &[u8],
    salt: &[u8],
    key_size: usize,
) -> Result<Zeroizing<Vec<u8>>, LegacyPemError> {
    let mut output = Zeroizing::new(Vec::with_capacity(key_size));
    let mut previous = Zeroizing::new([0_u8; MD5_BYTES]);
    let mut has_previous = false;

    while output.len() < key_size {
        let mut digest =
            DigestContext::new(DigestAlgorithm::Md5).map_err(|_| LegacyPemError::Backend)?;
        if has_previous {
            digest
                .update(&*previous)
                .map_err(|_| LegacyPemError::Backend)?;
        }
        digest
            .update(password)
            .map_err(|_| LegacyPemError::Backend)?;
        digest.update(salt).map_err(|_| LegacyPemError::Backend)?;
        digest
            .finalize_into(&mut *previous)
            .map_err(|_| LegacyPemError::Backend)?;
        has_previous = true;

        let remaining = key_size - output.len();
        output.extend_from_slice(&previous[..remaining.min(MD5_BYTES)]);
    }

    Ok(output)
}

fn decrypt_with<C>(
    mode: LegacyMode,
    key: &[u8],
    iv: &[u8],
    ciphertext: &[u8],
) -> Result<Zeroizing<Vec<u8>>, LegacyPemError>
where
    C: BlockCipher + BlockDecrypt + BlockEncrypt + KeyInit,
{
    let cipher = C::new_from_slice(key).map_err(|_| LegacyPemError::Backend)?;
    let block_size = Block::<C>::default().len();
    if mode.uses_iv() && iv.len() != block_size {
        return Err(LegacyPemError::Malformed);
    }
    if mode.uses_padding()
        && (ciphertext.is_empty() || !ciphertext.len().is_multiple_of(block_size))
    {
        return Err(LegacyPemError::InvalidPassphrase);
    }

    let mut output = Zeroizing::new(ciphertext.to_vec());
    match mode {
        LegacyMode::Cbc => decrypt_cbc(&cipher, iv, &mut output),
        LegacyMode::Cfb => decrypt_cfb(&cipher, iv, &mut output),
        LegacyMode::Ecb => decrypt_ecb(&cipher, &mut output),
        LegacyMode::Ofb => decrypt_ofb(&cipher, iv, &mut output),
    }
    if mode.uses_padding() {
        remove_pkcs7_padding(&mut output, block_size)?;
    }
    Ok(output)
}

fn decrypt_ecb<C>(cipher: &C, output: &mut [u8])
where
    C: BlockCipher + BlockDecrypt,
{
    for chunk in output.chunks_mut(Block::<C>::default().len()) {
        let mut block = Block::<C>::default();
        block.copy_from_slice(chunk);
        cipher.decrypt_block(&mut block);
        chunk.copy_from_slice(&block);
        block.zeroize();
    }
}

fn decrypt_cbc<C>(cipher: &C, iv: &[u8], output: &mut [u8])
where
    C: BlockCipher + BlockDecrypt,
{
    let block_size = Block::<C>::default().len();
    let mut previous = Block::<C>::default();
    previous.copy_from_slice(iv);

    for chunk in output.chunks_mut(block_size) {
        let mut block = Block::<C>::default();
        block.copy_from_slice(chunk);
        let next_previous = block.clone();
        cipher.decrypt_block(&mut block);
        for (value, previous_value) in block.iter_mut().zip(previous.iter()) {
            *value ^= previous_value;
        }
        chunk.copy_from_slice(&block);
        previous = next_previous;
        block.zeroize();
    }
    previous.zeroize();
}

fn decrypt_cfb<C>(cipher: &C, iv: &[u8], output: &mut [u8])
where
    C: BlockCipher + BlockEncrypt,
{
    let block_size = Block::<C>::default().len();
    let mut previous = Block::<C>::default();
    previous.copy_from_slice(iv);

    for chunk in output.chunks_mut(block_size) {
        let mut ciphertext_block = Block::<C>::default();
        ciphertext_block[..chunk.len()].copy_from_slice(chunk);
        let mut keystream = previous.clone();
        cipher.encrypt_block(&mut keystream);
        for (value, key_byte) in chunk.iter_mut().zip(keystream.iter()) {
            *value ^= key_byte;
        }
        if chunk.len() == block_size {
            previous.copy_from_slice(&ciphertext_block);
        }
        ciphertext_block.zeroize();
        keystream.zeroize();
    }
    previous.zeroize();
}

fn decrypt_ofb<C>(cipher: &C, iv: &[u8], output: &mut [u8])
where
    C: BlockCipher + BlockEncrypt,
{
    let block_size = Block::<C>::default().len();
    let mut feedback = Block::<C>::default();
    feedback.copy_from_slice(iv);

    for chunk in output.chunks_mut(block_size) {
        cipher.encrypt_block(&mut feedback);
        for (value, key_byte) in chunk.iter_mut().zip(feedback.iter()) {
            *value ^= key_byte;
        }
    }
    feedback.zeroize();
}

fn remove_pkcs7_padding(
    output: &mut Zeroizing<Vec<u8>>,
    block_size: usize,
) -> Result<(), LegacyPemError> {
    let Some(&padding) = output.last() else {
        return Err(LegacyPemError::InvalidPassphrase);
    };
    let mut mismatch = 0_u8;
    for distance in 1..=block_size {
        let suffix_mask = ((distance <= usize::from(padding)) as u8).wrapping_neg();
        mismatch |= (output[output.len() - distance] ^ padding) & suffix_mask;
    }
    let padding_in_range = (padding.wrapping_sub(1) < block_size as u8) as u8;
    if mismatch | (padding_in_range ^ 1) != 0 {
        return Err(LegacyPemError::InvalidPassphrase);
    }
    let unpadded_length = output.len() - usize::from(padding);
    output.truncate(unpadded_length);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const PASSWORD: &[u8] = b"legacy-passphrase";
    const PLAINTEXT_HEX: &str = "303132333435363738394142434445466b657967756172642d6c6567616379";
    const IV_8_HEX: &str = "0011223344556677";
    const IV_16_HEX: &str = "00112233445566778899aabbccddeeff";

    #[test]
    fn bc_pkcs5_password_conversion_uses_low_utf16_bytes() {
        assert_eq!(
            &*bc_pkcs5_password_bytes("Aé😀".as_bytes()).expect("valid UTF-8"),
            &[0x41, 0xe9, 0x3d, 0x00],
        );
        assert_eq!(
            bc_pkcs5_password_bytes(&[0xff]),
            Err(LegacyPemError::Malformed),
        );
    }

    #[test]
    fn evp_bytes_to_key_md5_matches_openssl_vectors() {
        let iv = decode_hex(IV_16_HEX);
        let password = bc_pkcs5_password_bytes(PASSWORD).expect("ASCII password");
        assert_eq!(
            encode_hex(&evp_bytes_to_key_md5(&password, &iv, 32).expect("MD5 KDF")),
            "e32232a055e4f9e7eb2b7b7a934476483a43a8687bd4f91ebf4ad9c6f4e3f99f",
        );
        assert_eq!(
            encode_hex(&evp_bytes_to_key_md5(&password, &iv[..8], 32).expect("MD5 KDF")),
            "39194467d2ac3914a2a460ab3e2b857e12057398bc04809d64886eadaf68547a",
        );
    }

    #[test]
    fn decrypts_bouncy_castle_184_non_ascii_password_vector() {
        let iv = decode_hex(IV_16_HEX);
        let ciphertext =
            decode_hex("839ffbc68a7c387ec88dd39e5b875e5b698f16d2b92720d35f2806b58801a35a");
        assert_eq!(
            &*decrypt_legacy_openssl_pem("AES-256-CBC", "Aé😀".as_bytes(), &iv, &ciphertext,)
                .expect("BC 1.84 non-ASCII password vector"),
            &decode_hex(PLAINTEXT_HEX),
        );
    }

    #[test]
    fn preserves_bouncy_castle_mode_selection_quirks() {
        assert_eq!(
            parse_cipher_spec("AES-128-UNRECOGNIZED"),
            Ok(LegacyCipherSpec {
                algorithm: LegacyAlgorithm::Aes128,
                mode: LegacyMode::Cbc,
            }),
        );
        assert_eq!(
            parse_cipher_spec("aes-128-cbc"),
            Err(LegacyPemError::UnsupportedAlgorithm),
        );
    }

    #[test]
    fn rejects_unknown_malformed_oversized_and_bad_padding_inputs() {
        let iv = decode_hex(IV_16_HEX);
        assert_eq!(
            decrypt_legacy_openssl_pem("CAMELLIA-256-CBC", PASSWORD, &iv, &[0; 16]),
            Err(LegacyPemError::UnsupportedAlgorithm),
        );
        assert_eq!(
            decrypt_legacy_openssl_pem("aes-256-cbc", PASSWORD, &iv, &[0; 16]),
            Err(LegacyPemError::UnsupportedAlgorithm),
        );
        assert_eq!(
            decrypt_legacy_openssl_pem("AES-256-CBC", PASSWORD, &iv[..8], &[0; 16]),
            Err(LegacyPemError::Malformed),
        );
        assert_eq!(
            decrypt_legacy_openssl_pem("AES-256-CBC", PASSWORD, &iv, &[0; 15]),
            Err(LegacyPemError::InvalidPassphrase),
        );
        assert_eq!(
            decrypt_legacy_openssl_pem("AES-256-CBC", PASSWORD, &iv, &[0; 16]),
            Err(LegacyPemError::InvalidPassphrase),
        );

        let oversized = vec![0_u8; MAX_CIPHERTEXT_BYTES + 1];
        assert_eq!(
            decrypt_legacy_openssl_pem("AES-256-CFB", PASSWORD, &iv, &oversized),
            Err(LegacyPemError::ResourceLimit),
        );
    }

    #[test]
    fn cfb_and_ofb_accept_empty_and_partial_final_blocks() {
        let iv = decode_hex(IV_16_HEX);
        assert!(
            decrypt_legacy_openssl_pem("AES-256-CFB", PASSWORD, &iv, &[])
                .expect("empty CFB")
                .is_empty()
        );
        assert!(
            decrypt_legacy_openssl_pem("AES-256-OFB", PASSWORD, &iv, &[])
                .expect("empty OFB")
                .is_empty()
        );
    }

    // Filled with independent Bouncy Castle 1.84 ciphertexts below. Keeping
    // one table makes omissions from the frozen decrypt-only matrix obvious.
    #[test]
    fn decrypts_bouncy_castle_184_matrix() {
        let plaintext = decode_hex(PLAINTEXT_HEX);
        for (algorithm, ciphertext_hex) in BOUNCY_CASTLE_KATS {
            let iv = if algorithm.starts_with("AES-") {
                decode_hex(IV_16_HEX)
            } else {
                decode_hex(IV_8_HEX)
            };
            let ciphertext = decode_hex(ciphertext_hex);
            assert_eq!(
                &*decrypt_legacy_openssl_pem(algorithm, PASSWORD, &iv, &ciphertext)
                    .unwrap_or_else(|error| panic!("{algorithm} failed: {error}")),
                &plaintext,
                "{algorithm}",
            );
        }
    }

    const BOUNCY_CASTLE_KATS: &[(&str, &str)] = &[
        (
            "DES-EDE-CBC",
            "5247ae32b91276e4dabf4da3610ddc6dcfde4f782deec835278203ee2acb00e2",
        ),
        (
            "DES-EDE-CFB",
            "10c676d64939a39fe6f11bf5903c322b30707517d102eab01d722f50f6c784",
        ),
        (
            "DES-EDE-OFB",
            "10c676d64939a39f4ca3d7d4d50f1a89b4fff7ae51fac6be6c5d206b2aaee2",
        ),
        (
            "DES-EDE-ECB",
            "23123f9168b9a80cadb11ceff664f228064719f26435bbb1b043f6a5e6f8f6cf",
        ),
        (
            "DES-EDE3-CBC",
            "4736b0ee278dd61c9e164f24a8033d836671aeb10a1ea7f0373cd7fdaf74a5cb",
        ),
        (
            "DES-EDE3-CFB",
            "701e44d7b7d8f98eee061591ddcc9ddd231f168efac6ba868f0094f2968fd4",
        ),
        (
            "DES-EDE3-OFB",
            "701e44d7b7d8f98e6f319490a24e4f71e228465105bf9f34221d85d62d6538",
        ),
        (
            "DES-EDE3-ECB",
            "d29313b7169a587c0d530a4b5cb9f541455d9643ed4f9d1032f539d0c9ce5bb0",
        ),
        (
            "DES-CBC",
            "6220a66357e5826908140e6a07891ceac19abc16cbb0b6f83a17f009a7fec0c9",
        ),
        (
            "DES-CFB",
            "f0e20136cb22298fdf107ccb7a06ac0757374029f413d91ac8fe928a46fa19",
        ),
        (
            "DES-OFB",
            "f0e20136cb22298f315889e8ea7a090c85b122fdc740aa3c6ca0ec6ba4797e",
        ),
        (
            "DES-ECB",
            "a145497f2c245245d3a244e4d0382998235aaf2f532538484aafe60bf1d3679c",
        ),
        (
            "BF-CBC",
            "b2fa8245cc1843be0f7917b4be251bf003b799d50b0c491395abe006899774a1",
        ),
        (
            "BF-CFB",
            "922790128c5f49c8dbd5266a1fdbec9bac3ee102747d80f9db320d8e04da90",
        ),
        (
            "BF-OFB",
            "922790128c5f49c88344ad87991c0556a0e1db46e7e6800e5d2cb51641198a",
        ),
        (
            "BF-ECB",
            "864f1e51eddfb9918ffccc25a263804b3de5c738a21b1924a126e3e1d73f1543",
        ),
        (
            "RC2-CBC",
            "ba4c1e2ed211c3eecfd5d6e3849e89ac6eb76b218117baa027adb8535744b97b",
        ),
        (
            "RC2-CFB",
            "2f064d73826df661dc3f629631d973ce390b260e4c9fa840cc4133789e2678",
        ),
        (
            "RC2-OFB",
            "2f064d73826df6615729328e9072b6c07f28da2eb8fd38613995a867954f08",
        ),
        (
            "RC2-ECB",
            "45546273f5fffa0192b79a0e42433569e6d809e247fba583fea81bee33e3653e",
        ),
        (
            "RC2-40-CBC",
            "6d6032bb8e1f8a4b5bb95eda36edab6fc81e31aa4e752b64c6c0cff39388eade",
        ),
        (
            "RC2-40-CFB",
            "fea6d8b9875f723024205386c7e29c81585c3b92800ba03319d97e2cf5d724",
        ),
        (
            "RC2-40-OFB",
            "fea6d8b9875f72306d691de8c2bd491fe83e4d37c751466217ac5a8f5f44b9",
        ),
        (
            "RC2-40-ECB",
            "a472c5b5a76058daf5515812609d1ae99ac0b41c5d14d1563d6ace641f1d2146",
        ),
        (
            "RC2-64-CBC",
            "beac8b1309b8baddb740d79f9aceee09e2a2d8ab93fa68e16e842d2451449e71",
        ),
        (
            "RC2-64-CFB",
            "f430851c47c14a9faac576ef4ef648cc458221bb45d2df78c0aded037757bf",
        ),
        (
            "RC2-64-OFB",
            "f430851c47c14a9feaec53f8f54711a4bbe26a5de420e781d0bc4800955907",
        ),
        (
            "RC2-64-ECB",
            "0e54599e48fab7eb2143f707c08cd1caada52d0e729eb713ff5081cd453dce44",
        ),
        (
            "AES-128-CBC",
            "6c56cf8e07c6282191749a857336287e643ca6dfe489457adea6940730f8c77b",
        ),
        (
            "AES-128-CFB",
            "2c3824abae99e8fe711d3870bfd76a0d41936a832c1cd16f84442a8ec47025",
        ),
        (
            "AES-128-OFB",
            "2c3824abae99e8fe711d3870bfd76a0deaeae65a655282190c308c12fa5bb0",
        ),
        (
            "AES-128-ECB",
            "90fe9b7b67ef6fa0caa9c6bb62eb8f02afaf263fcb3f746ccf183067bf0c8a46",
        ),
        (
            "AES-192-CBC",
            "d17bce3a4d1869f15de7e09b31049baab3047662bc727b19e737f800fe70ed29",
        ),
        (
            "AES-192-CFB",
            "e373c20dc30dafc0581028933fde8c65dc5cf7e3594114175c96c1e7ea4750",
        ),
        (
            "AES-192-OFB",
            "e373c20dc30dafc0581028933fde8c65349b4bb7a019c46c61af5184a24ad4",
        ),
        (
            "AES-192-ECB",
            "e25a4eaf4096799b83280aa2edcead72530b909afd519c88495a8d81097198e0",
        ),
        (
            "AES-256-CBC",
            "eda85a300d67eca57408cdadf46142169ad756ad47938e90d9e3ebfcf62c7d35",
        ),
        (
            "AES-256-CFB",
            "e2164c9f43e042ae74f7c512c29a561157b330a14461c1d7fc371166c5e623",
        ),
        (
            "AES-256-OFB",
            "e2164c9f43e042ae74f7c512c29a5611a14ebe393c9a50184a6d12730853e7",
        ),
        (
            "AES-256-ECB",
            "7ed9f90426825b0b06fad4769afc4a568fa68a35693c85a5fcf7aac9b77301c9",
        ),
        (
            "DES-EDE",
            "23123f9168b9a80cadb11ceff664f228064719f26435bbb1b043f6a5e6f8f6cf",
        ),
        (
            "DES-EDE3",
            "d29313b7169a587c0d530a4b5cb9f541455d9643ed4f9d1032f539d0c9ce5bb0",
        ),
    ];

    fn decode_hex(value: &str) -> Vec<u8> {
        assert!(value.len().is_multiple_of(2), "test hex must have pairs");
        value
            .as_bytes()
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| {
                let high = hex_nibble(pair[0]);
                let low = hex_nibble(pair[1]);
                (high << 4) | low
            })
            .collect()
    }

    fn encode_hex(value: &[u8]) -> String {
        const HEX: &[u8; 16] = b"0123456789abcdef";
        let mut output = String::with_capacity(value.len() * 2);
        for byte in value {
            output.push(HEX[usize::from(byte >> 4)] as char);
            output.push(HEX[usize::from(byte & 0x0f)] as char);
        }
        output
    }

    fn hex_nibble(value: u8) -> u8 {
        match value {
            b'0'..=b'9' => value - b'0',
            b'a'..=b'f' => value - b'a' + 10,
            _ => panic!("invalid test hex"),
        }
    }
}
