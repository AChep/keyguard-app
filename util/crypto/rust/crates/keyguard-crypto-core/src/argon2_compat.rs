//! Reviewed local Argon2 v1.0/v1.3 implementation with legacy normalization.
//!
//! Every Argon2 request uses this RustCrypto 0.5.3-equivalent path so
//! its BLAKE2 contexts receive guaranteed erasure. Standard inputs remain
//! locked to the upstream implementation by differential tests. Keyguard's
//! legacy compatibility contract also accepts salts shorter than eight bytes
//! and declared memory below `8 * parallelism`; this implementation hashes the
//! declared value into `H0` while allocating the algorithmic minimum number of
//! blocks. Fixed repository goldens lock those non-standard accepted inputs.
//!
//! The fill, compression, and long-hash structure is adapted from RustCrypto
//! `argon2` 0.5.3 (`src/lib.rs`, `src/block.rs`, and `src/blake2b_long.rs`),
//! copyright the RustCrypto Developers and licensed MIT OR Apache-2.0. The
//! memory geometry and version rules follow the public Argon2 algorithm. Keep
//! the equivalence and compatibility tests below paired with the pinned
//! RustCrypto version when updating this implementation.

use argon2::{Algorithm, Block, Version};
use keyguard_crypto_sensitive::{Blake2bContext, SensitiveBackendError};
use std::{mem::size_of, num::Wrapping};
use zeroize::Zeroizing;

use crate::primitives::PrimitiveError;

const SYNC_POINTS: usize = 4;
const ADDRESSES_IN_BLOCK: usize = 128;
const MIN_BLOCKS_PER_LANE: u32 = 2 * SYNC_POINTS as u32;
const TRUNCATED_WORD_MASK: u64 = u32::MAX as u64;
/// Computes Argon2 v1.0 or v1.3 while preserving Keyguard's legacy inputs.
#[allow(clippy::too_many_arguments)]
pub(super) fn hash_password_into(
    algorithm: Algorithm,
    version: Version,
    password: &[u8],
    salt: &[u8],
    secret: &[u8],
    associated_data: &[u8],
    declared_memory_kib: u32,
    iterations: u32,
    parallelism: u32,
    output: &mut [u8],
) -> Result<(), PrimitiveError> {
    let minimum_blocks = parallelism
        .checked_mul(MIN_BLOCKS_PER_LANE)
        .ok_or(PrimitiveError::ResourceLimit)?;
    let normalized_blocks = declared_memory_kib.max(minimum_blocks);
    let alignment = parallelism
        .checked_mul(SYNC_POINTS as u32)
        .ok_or(PrimitiveError::ResourceLimit)?;
    let segment_length = normalized_blocks / alignment;
    let lane_length = segment_length
        .checked_mul(SYNC_POINTS as u32)
        .ok_or(PrimitiveError::ResourceLimit)?;
    let block_count = parallelism
        .checked_mul(lane_length)
        .ok_or(PrimitiveError::ResourceLimit)?;

    let lanes = usize::try_from(parallelism).map_err(|_| PrimitiveError::ResourceLimit)?;
    let iterations = usize::try_from(iterations).map_err(|_| PrimitiveError::ResourceLimit)?;
    let segment_length =
        usize::try_from(segment_length).map_err(|_| PrimitiveError::ResourceLimit)?;
    let lane_length = usize::try_from(lane_length).map_err(|_| PrimitiveError::ResourceLimit)?;
    let block_count = usize::try_from(block_count).map_err(|_| PrimitiveError::ResourceLimit)?;

    let mut memory = allocate_blocks(block_count)?;
    let initial_hash = initial_hash(
        algorithm,
        version,
        password,
        salt,
        secret,
        associated_data,
        declared_memory_kib,
        iterations,
        parallelism,
        output.len(),
    )?;
    fill_blocks(
        algorithm,
        version,
        iterations,
        lanes,
        segment_length,
        lane_length,
        &mut memory,
        initial_hash,
    )?;
    finalize(&memory, lanes, lane_length, output)
}

pub(super) fn allocate_blocks(block_count: usize) -> Result<Zeroizing<Vec<Block>>, PrimitiveError> {
    let byte_count = block_count
        .checked_mul(size_of::<Block>())
        .ok_or(PrimitiveError::ResourceLimit)?;
    if byte_count > isize::MAX as usize {
        return Err(PrimitiveError::ResourceLimit);
    }

    let mut memory = Vec::new();
    memory
        .try_reserve_exact(block_count)
        .map_err(|_| PrimitiveError::ResourceLimit)?;
    memory.resize(block_count, Block::new());
    Ok(Zeroizing::new(memory))
}

#[allow(clippy::too_many_arguments)]
fn initial_hash(
    algorithm: Algorithm,
    version: Version,
    password: &[u8],
    salt: &[u8],
    secret: &[u8],
    associated_data: &[u8],
    declared_memory_kib: u32,
    iterations: usize,
    parallelism: u32,
    output_length: usize,
) -> Result<Zeroizing<[u8; 64]>, PrimitiveError> {
    let output_length = u32::try_from(output_length).map_err(|_| PrimitiveError::ResourceLimit)?;
    let iterations = u32::try_from(iterations).map_err(|_| PrimitiveError::ResourceLimit)?;
    let password_length =
        u32::try_from(password.len()).map_err(|_| PrimitiveError::ResourceLimit)?;
    let salt_length = u32::try_from(salt.len()).map_err(|_| PrimitiveError::ResourceLimit)?;
    let secret_length = u32::try_from(secret.len()).map_err(|_| PrimitiveError::ResourceLimit)?;
    let associated_data_length =
        u32::try_from(associated_data.len()).map_err(|_| PrimitiveError::ResourceLimit)?;

    let mut digest = Blake2bContext::new(64).map_err(blake2b_error)?;
    digest.update(&parallelism.to_le_bytes());
    digest.update(&output_length.to_le_bytes());
    digest.update(&declared_memory_kib.to_le_bytes());
    digest.update(&iterations.to_le_bytes());
    digest.update(&(version as u32).to_le_bytes());
    digest.update(&algorithm_id(algorithm).to_le_bytes());
    digest.update(&password_length.to_le_bytes());
    digest.update(password);
    digest.update(&salt_length.to_le_bytes());
    digest.update(salt);
    digest.update(&secret_length.to_le_bytes());
    digest.update(secret);
    digest.update(&associated_data_length.to_le_bytes());
    digest.update(associated_data);
    let mut output = Zeroizing::new([0_u8; 64]);
    digest.finalize_into(&mut *output).map_err(blake2b_error)?;
    Ok(output)
}

#[allow(clippy::too_many_arguments)]
fn fill_blocks(
    algorithm: Algorithm,
    version: Version,
    iterations: usize,
    lanes: usize,
    segment_length: usize,
    lane_length: usize,
    memory: &mut [Block],
    initial_hash: Zeroizing<[u8; 64]>,
) -> Result<(), PrimitiveError> {
    for (lane_index, lane) in memory.chunks_exact_mut(lane_length).enumerate() {
        for (block_index, block) in lane[..2].iter_mut().enumerate() {
            let block_index = u32::try_from(block_index)
                .map_err(|_| PrimitiveError::ResourceLimit)?
                .to_le_bytes();
            let lane_index = u32::try_from(lane_index)
                .map_err(|_| PrimitiveError::ResourceLimit)?
                .to_le_bytes();
            let mut hash = Zeroizing::new([0_u8; Block::SIZE]);
            blake2b_long(&[&initial_hash[..], &block_index, &lane_index], &mut *hash)?;
            load_block(block, &hash);
        }
    }

    for pass in 0..iterations {
        for slice in 0..SYNC_POINTS {
            let data_independent_addressing = algorithm == Algorithm::Argon2i
                || (algorithm == Algorithm::Argon2id && pass == 0 && slice < SYNC_POINTS / 2);

            for lane in 0..lanes {
                let mut address_block = Zeroizing::new(Block::new());
                let mut input_block = Zeroizing::new(Block::new());
                let zero_block = Block::new();

                if data_independent_addressing {
                    input_block.as_mut()[..6].copy_from_slice(&[
                        pass as u64,
                        lane as u64,
                        slice as u64,
                        memory.len() as u64,
                        iterations as u64,
                        u64::from(algorithm_id(algorithm)),
                    ]);
                }

                let first_block = if pass == 0 && slice == 0 {
                    if data_independent_addressing {
                        update_address_block(&mut address_block, &mut input_block, &zero_block);
                    }
                    2
                } else {
                    0
                };

                let first_index = lane * lane_length + slice * segment_length + first_block;
                let mut previous_index = if slice == 0 && first_block == 0 {
                    first_index + lane_length - 1
                } else {
                    first_index - 1
                };

                for (current_index, block) in (first_index..).zip(first_block..segment_length) {
                    let random = if data_independent_addressing {
                        let address_index = block % ADDRESSES_IN_BLOCK;
                        if address_index == 0 {
                            update_address_block(&mut address_block, &mut input_block, &zero_block);
                        }
                        address_block.as_ref()[address_index]
                    } else {
                        memory[previous_index].as_ref()[0]
                    };

                    let reference_lane = if pass == 0 && slice == 0 {
                        lane
                    } else {
                        (random >> 32) as usize % lanes
                    };
                    let reference_area_size = if pass == 0 {
                        if slice == 0 {
                            block - 1
                        } else if reference_lane == lane {
                            slice * segment_length + block - 1
                        } else {
                            slice * segment_length - usize::from(block == 0)
                        }
                    } else if reference_lane == lane {
                        lane_length - segment_length + block - 1
                    } else {
                        lane_length - segment_length - usize::from(block == 0)
                    };

                    let mut mapped = random & u64::from(u32::MAX);
                    mapped = mapped.wrapping_mul(mapped) >> 32;
                    let relative_position = reference_area_size
                        - 1
                        - ((reference_area_size as u64 * mapped) >> 32) as usize;
                    let start_position = if pass != 0 && slice != SYNC_POINTS - 1 {
                        (slice + 1) * segment_length
                    } else {
                        0
                    };
                    let lane_index = (start_position + relative_position) % lane_length;
                    let reference_index = reference_lane * lane_length + lane_index;
                    let result = compress(&memory[previous_index], &memory[reference_index]);

                    // Argon2 v1.0 overwrites memory on every pass. Version 1.3
                    // XORs the previous block value into passes after the first.
                    if pass == 0 || version == Version::V0x10 {
                        memory[current_index] = *result;
                    } else {
                        memory[current_index] ^= &*result;
                    }
                    previous_index = current_index;
                }
            }
        }
    }
    Ok(())
}

fn finalize(
    memory: &[Block],
    lanes: usize,
    lane_length: usize,
    output: &mut [u8],
) -> Result<(), PrimitiveError> {
    let mut block_hash = Zeroizing::new(memory[lane_length - 1]);
    for lane in 1..lanes {
        let last_block = lane * lane_length + lane_length - 1;
        *block_hash ^= &memory[last_block];
    }

    let mut block_bytes = Zeroizing::new([0_u8; Block::SIZE]);
    for (chunk, word) in block_bytes.chunks_mut(8).zip(block_hash.as_ref()) {
        chunk.copy_from_slice(&word.to_le_bytes());
    }
    blake2b_long(&[&block_bytes[..]], output)
}

fn update_address_block(address: &mut Block, input: &mut Block, zero: &Block) {
    input.as_mut()[6] += 1;
    *address = *compress(zero, input);
    *address = *compress(zero, address);
}

fn load_block(block: &mut Block, input: &[u8; Block::SIZE]) {
    for (word, chunk) in block.as_mut().iter_mut().zip(input.as_chunks::<8>().0) {
        *word = u64::from_le_bytes(*chunk);
    }
}

#[rustfmt::skip]
macro_rules! permute_step {
    ($a:expr, $b:expr, $c:expr, $d:expr) => {
        $a = (Wrapping($a) + Wrapping($b) + (Wrapping(2) * Wrapping(($a & TRUNCATED_WORD_MASK) * ($b & TRUNCATED_WORD_MASK)))).0;
        $d = ($d ^ $a).rotate_right(32);
        $c = (Wrapping($c) + Wrapping($d) + (Wrapping(2) * Wrapping(($c & TRUNCATED_WORD_MASK) * ($d & TRUNCATED_WORD_MASK)))).0;
        $b = ($b ^ $c).rotate_right(24);

        $a = (Wrapping($a) + Wrapping($b) + (Wrapping(2) * Wrapping(($a & TRUNCATED_WORD_MASK) * ($b & TRUNCATED_WORD_MASK)))).0;
        $d = ($d ^ $a).rotate_right(16);
        $c = (Wrapping($c) + Wrapping($d) + (Wrapping(2) * Wrapping(($c & TRUNCATED_WORD_MASK) * ($d & TRUNCATED_WORD_MASK)))).0;
        $b = ($b ^ $c).rotate_right(63);
    };
}

macro_rules! permute {
    (
        $v0:expr, $v1:expr, $v2:expr, $v3:expr,
        $v4:expr, $v5:expr, $v6:expr, $v7:expr,
        $v8:expr, $v9:expr, $v10:expr, $v11:expr,
        $v12:expr, $v13:expr, $v14:expr, $v15:expr,
    ) => {
        permute_step!($v0, $v4, $v8, $v12);
        permute_step!($v1, $v5, $v9, $v13);
        permute_step!($v2, $v6, $v10, $v14);
        permute_step!($v3, $v7, $v11, $v15);
        permute_step!($v0, $v5, $v10, $v15);
        permute_step!($v1, $v6, $v11, $v12);
        permute_step!($v2, $v7, $v8, $v13);
        permute_step!($v3, $v4, $v9, $v14);
    };
}

fn compress(right: &Block, left: &Block) -> Zeroizing<Block> {
    let mut xor = Zeroizing::new(Block::new());
    for ((output, right), left) in xor
        .as_mut()
        .iter_mut()
        .zip(right.as_ref())
        .zip(left.as_ref())
    {
        *output = right ^ left;
    }

    let mut result = Zeroizing::new(*xor);
    for chunk in result.as_mut().as_chunks_mut::<16>().0 {
        permute!(
            chunk[0], chunk[1], chunk[2], chunk[3], chunk[4], chunk[5], chunk[6], chunk[7],
            chunk[8], chunk[9], chunk[10], chunk[11], chunk[12], chunk[13], chunk[14], chunk[15],
        );
    }
    let words = result.as_mut();
    for index in 0..8 {
        let base = index * 2;
        permute!(
            words[base],
            words[base + 1],
            words[base + 16],
            words[base + 17],
            words[base + 32],
            words[base + 33],
            words[base + 48],
            words[base + 49],
            words[base + 64],
            words[base + 65],
            words[base + 80],
            words[base + 81],
            words[base + 96],
            words[base + 97],
            words[base + 112],
            words[base + 113],
        );
    }
    *result ^= &*xor;
    result
}

fn blake2b_long(inputs: &[&[u8]], output: &mut [u8]) -> Result<(), PrimitiveError> {
    let length_bytes = u32::try_from(output.len())
        .map_err(|_| PrimitiveError::ResourceLimit)?
        .to_le_bytes();
    if output.len() <= 64 {
        let mut digest = Blake2bContext::new(output.len()).map_err(blake2b_error)?;
        digest.update(&length_bytes);
        for input in inputs {
            digest.update(input);
        }
        return digest.finalize_into(output).map_err(blake2b_error);
    }

    let half_hash_length = 32;
    let mut digest = Blake2bContext::new(64).map_err(blake2b_error)?;
    digest.update(&length_bytes);
    for input in inputs {
        digest.update(input);
    }
    let mut previous = Zeroizing::new([0_u8; 64]);
    digest
        .finalize_into(&mut *previous)
        .map_err(blake2b_error)?;
    output[..half_hash_length].copy_from_slice(&previous[..half_hash_length]);

    let mut written = 0;
    let output_length = output.len();
    for chunk in output[half_hash_length..]
        .chunks_exact_mut(half_hash_length)
        .take_while(|_| {
            written += half_hash_length;
            output_length - written > 64
        })
    {
        let mut digest = Blake2bContext::new(64).map_err(blake2b_error)?;
        digest.update(&previous[..]);
        let mut next = Zeroizing::new([0_u8; 64]);
        digest.finalize_into(&mut *next).map_err(blake2b_error)?;
        previous.copy_from_slice(&*next);
        chunk.copy_from_slice(&previous[..half_hash_length]);
    }

    let last_block_size = output.len() - written;
    let mut digest = Blake2bContext::new(last_block_size).map_err(blake2b_error)?;
    digest.update(&previous[..]);
    digest
        .finalize_into(&mut output[written..])
        .map_err(blake2b_error)
}

fn blake2b_error(error: SensitiveBackendError) -> PrimitiveError {
    match error {
        SensitiveBackendError::InvalidOutputSize => PrimitiveError::InvalidArgument,
        SensitiveBackendError::AllocationFailure | SensitiveBackendError::BackendFailure => {
            PrimitiveError::CryptoFailure
        }
    }
}

const fn algorithm_id(algorithm: Algorithm) -> u32 {
    match algorithm {
        Algorithm::Argon2d => 0,
        Algorithm::Argon2i => 1,
        Algorithm::Argon2id => 2,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use argon2::{Argon2, Params, Version};

    #[test]
    fn argon2_memory_capacity_overflow_is_a_resource_error() {
        assert!(matches!(
            allocate_blocks(usize::MAX),
            Err(PrimitiveError::ResourceLimit)
        ));
    }

    #[test]
    fn local_path_matches_rustcrypto_for_standard_inputs() {
        for version in [Version::V0x10, Version::V0x13] {
            for algorithm in [Algorithm::Argon2d, Algorithm::Argon2i, Algorithm::Argon2id] {
                let params = Params::new(64, 2, 1, Some(32)).expect("parameters must be valid");
                let mut expected = [0_u8; 32];
                let mut reviewed_memory = allocate_blocks(64).expect("test memory must allocate");
                Argon2::new(algorithm, version, params)
                    .hash_password_into_with_memory(
                        b"password",
                        b"standard-salt",
                        &mut expected,
                        &mut *reviewed_memory,
                    )
                    .expect("RustCrypto derivation must succeed");

                let mut actual = [0_u8; 32];
                hash_password_into(
                    algorithm,
                    version,
                    b"password",
                    b"standard-salt",
                    &[],
                    &[],
                    64,
                    2,
                    1,
                    &mut actual,
                )
                .expect("compatibility derivation must succeed");

                assert_eq!(actual, expected);
            }
        }
    }
}
