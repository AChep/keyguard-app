//! Erasing bcrypt-PBKDF compatibility for encrypted OpenSSH private keys.
//!
//! OpenSSH's construction is a PBKDF2-shaped loop whose PRF hashes its input
//! with SHA-512 before running the EksBlowfish `bcrypt_hash` operation. The
//! upstream `bcrypt-pbkdf` implementation keeps RustCrypto SHA-512 state and
//! generated blocks in ordinary stack/heap storage. This adapter instead uses
//! the explicitly-cleansed AWS-LC digest owner and zeroizing Rust buffers.

use blowfish::Blowfish;
use zeroize::{Zeroize, Zeroizing};

use super::{DigestAlgorithm, DigestContext, SensitiveBackendError};

const BCRYPT_HASH_BYTES: usize = 32;
const SHA512_BYTES: usize = 64;
const MAX_OUTPUT_BYTES: usize = BCRYPT_HASH_BYTES * BCRYPT_HASH_BYTES;
const BCRYPT_HASH_WORDS: usize = BCRYPT_HASH_BYTES / 4;
const BCRYPT_HASH_SEED: &[u8; BCRYPT_HASH_BYTES] = b"OxychromaticBlowfishSwatDynamite";

/// Derives an OpenSSH bcrypt-PBKDF output while explicitly erasing owned
/// digest, Blowfish, and intermediate block state.
///
/// This matches OpenSSH and `bcrypt-pbkdf` 0.10.0 input limits. Callers own
/// the CPU-work policy for `rounds`; the native SSH import boundary applies
/// its reviewed upper bound before calling this function.
pub fn bcrypt_pbkdf(
    passphrase: &[u8],
    salt: &[u8],
    rounds: u32,
    output: &mut [u8],
) -> Result<(), SensitiveBackendError> {
    if passphrase.is_empty()
        || salt.is_empty()
        || rounds == 0
        || output.is_empty()
        || output.len() > MAX_OUTPUT_BYTES
    {
        output.zeroize();
        return Err(SensitiveBackendError::InvalidOutputSize);
    }

    let result = (|| {
        let stride = output.len().div_ceil(BCRYPT_HASH_BYTES);
        let mut generated = Zeroizing::new(vec![0_u8; stride * BCRYPT_HASH_BYTES]);
        let mut hashed_passphrase = Zeroizing::new([0_u8; SHA512_BYTES]);
        sha512_into(&[passphrase], &mut hashed_passphrase)?;

        let mut hashed_salt = Zeroizing::new([0_u8; SHA512_BYTES]);
        let mut current = Zeroizing::new([0_u8; BCRYPT_HASH_BYTES]);
        let mut accumulator = Zeroizing::new([0_u8; BCRYPT_HASH_BYTES]);

        for (index, chunk) in generated.chunks_exact_mut(BCRYPT_HASH_BYTES).enumerate() {
            // `stride` is at most 32 under MAX_OUTPUT_BYTES, so this conversion
            // cannot fail. Keep it checked to preserve the local proof if the
            // output policy changes later.
            let block =
                u32::try_from(index + 1).map_err(|_| SensitiveBackendError::InvalidOutputSize)?;
            sha512_into(&[salt, &block.to_be_bytes()], &mut hashed_salt)?;
            bcrypt_hash(&hashed_passphrase, &hashed_salt, &mut current);
            accumulator.copy_from_slice(&*current);

            for _ in 1..rounds {
                sha512_into(&[&*current], &mut hashed_salt)?;
                bcrypt_hash(&hashed_passphrase, &hashed_salt, &mut current);
                for (accumulated, value) in accumulator.iter_mut().zip(current.iter()) {
                    *accumulated ^= value;
                }
            }

            chunk.copy_from_slice(&*accumulator);
        }

        // bcrypt-PBKDF interleaves generated blocks instead of concatenating
        // them. This is the OpenSSH-compatible non-linear transformation.
        for (index, byte) in output.iter_mut().enumerate() {
            let chunk = index % stride;
            let offset = index / stride;
            *byte = generated[chunk * BCRYPT_HASH_BYTES + offset];
        }
        Ok(())
    })();

    if result.is_err() {
        output.zeroize();
    }
    result
}

fn sha512_into(
    parts: &[&[u8]],
    output: &mut [u8; SHA512_BYTES],
) -> Result<(), SensitiveBackendError> {
    let mut context = DigestContext::new(DigestAlgorithm::Sha512)?;
    for part in parts {
        context.update(part)?;
    }
    context.finalize_into(output)
}

fn bcrypt_hash(
    hashed_passphrase: &[u8; SHA512_BYTES],
    hashed_salt: &[u8; SHA512_BYTES],
    output: &mut [u8; BCRYPT_HASH_BYTES],
) {
    // The workspace enables Blowfish's `zeroize` feature, so this complete
    // expanded key schedule is erased by its Drop implementation.
    let mut blowfish = Blowfish::bc_init_state();
    blowfish.salted_expand_key(hashed_salt, hashed_passphrase);
    for _ in 0..64 {
        blowfish.bc_expand_key(hashed_salt);
        blowfish.bc_expand_key(hashed_passphrase);
    }

    let mut words = Zeroizing::new([0_u32; BCRYPT_HASH_WORDS]);
    for (word, seed) in words.iter_mut().zip(BCRYPT_HASH_SEED.chunks_exact(4)) {
        *word = u32::from_be_bytes([seed[0], seed[1], seed[2], seed[3]]);
    }

    for _ in 0..64 {
        for pair in words.chunks_exact_mut(2) {
            let encrypted = Zeroizing::new(blowfish.bc_encrypt([pair[0], pair[1]]));
            pair.copy_from_slice(&*encrypted);
        }
    }

    // bcrypt_hash serializes its final words little-endian. Write each byte
    // directly so no additional owned secret byte array outlives this scope.
    for (index, word) in words.iter().copied().enumerate() {
        let offset = index * 4;
        output[offset] = word as u8;
        output[offset + 1] = (word >> 8) as u8;
        output[offset + 2] = (word >> 16) as u8;
        output[offset + 3] = (word >> 24) as u8;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_openbsd_single_block_vector() {
        let mut output = [0_u8; 32];
        bcrypt_pbkdf(b"password", b"salt", 4, &mut output).expect("bcrypt-PBKDF");
        assert_eq!(
            output,
            [
                0x5b, 0xbf, 0x0c, 0xc2, 0x93, 0x58, 0x7f, 0x1c, 0x36, 0x35, 0x55, 0x5c, 0x27, 0x79,
                0x65, 0x98, 0xd4, 0x7e, 0x57, 0x90, 0x71, 0xbf, 0x42, 0x7e, 0x9d, 0x8f, 0xbe, 0x84,
                0x2a, 0xba, 0x34, 0xd9,
            ]
        );
    }

    #[test]
    fn matches_openbsd_interleaved_multi_block_vector() {
        let mut output = [0_u8; 64];
        bcrypt_pbkdf(b"password", b"salt", 8, &mut output).expect("bcrypt-PBKDF");
        assert_eq!(
            output,
            [
                0xe1, 0x36, 0x7e, 0xc5, 0x15, 0x1a, 0x33, 0xfa, 0xac, 0x4c, 0xc1, 0xc1, 0x44, 0xcd,
                0x23, 0xfa, 0x15, 0xd5, 0x54, 0x84, 0x93, 0xec, 0xc9, 0x9b, 0x9b, 0x5d, 0x9c, 0x0d,
                0x3b, 0x27, 0xbe, 0xc7, 0x62, 0x27, 0xea, 0x66, 0x08, 0x8b, 0x84, 0x9b, 0x20, 0xab,
                0x7a, 0xa4, 0x78, 0x01, 0x02, 0x46, 0xe7, 0x4b, 0xba, 0x51, 0x72, 0x3f, 0xef, 0xa9,
                0xf9, 0x47, 0x4d, 0x65, 0x08, 0x84, 0x5e, 0x8d,
            ]
        );
    }

    #[test]
    fn invalid_parameters_erase_output() {
        let mut output = [0xa5_u8; 32];
        assert_eq!(
            bcrypt_pbkdf(b"", b"salt", 4, &mut output),
            Err(SensitiveBackendError::InvalidOutputSize)
        );
        assert_eq!(output, [0_u8; 32]);
    }
}
