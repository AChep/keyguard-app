//! Narrow unsafe adapters for cryptographic contexts which require guaranteed
//! erasure of backend-owned state.
//!
//! The safe core depends on this crate instead of handling pointers or flat
//! type erasure itself. AWS-LC owns digest and HMAC contexts on its heap and
//! provides explicit cleansing APIs. The pinned RustCrypto BLAKE2 state and
//! block buffer are flat, heap-resident values erased with volatile writes.

#![deny(unsafe_op_in_unsafe_fn)]

mod bcrypt_pbkdf;
pub mod rsa;

pub use bcrypt_pbkdf::bcrypt_pbkdf;
pub use rsa::{
    RsaCrtComponents, RsaPrimeComponents, RsaPrivateComponents, RsaSignatureHash,
    SensitiveRsaError, complete_rsa_pkcs1_der, decrypt_rsa_pkcs1_v1_5, decrypt_rsa_raw,
    generate_rsa_pkcs1_der, sign_rsa_pkcs1_v1_5, sign_rsa_pkcs1_v1_5_digest,
};

use std::{ffi::c_void, ptr::NonNull};

use aws_lc_sys as aws_lc;
use blake2::Blake2bVarCore;
use block_buffer::BlockBuffer;
use digest::{
    Output,
    core_api::{BlockSizeUser, BufferKindUser, UpdateCore, VariableOutputCore},
};
use zeroize::{Zeroize, Zeroizing, zeroize_flat_type};

type Blake2bBuffer = BlockBuffer<
    <Blake2bVarCore as BlockSizeUser>::BlockSize,
    <Blake2bVarCore as BufferKindUser>::BufferKind,
>;

/// Digest algorithms admitted by the sensitive backend.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DigestAlgorithm {
    /// SHA-1 retained for serialized-format compatibility.
    Sha1,
    /// SHA-224.
    Sha224,
    /// SHA-256.
    Sha256,
    /// SHA-384.
    Sha384,
    /// SHA-512.
    Sha512,
    /// SHA-512/224.
    Sha512_224,
    /// SHA-512/256.
    Sha512_256,
    /// MD5 retained for serialized-format compatibility.
    Md5,
}

impl DigestAlgorithm {
    /// Returns the exact digest output size.
    #[must_use]
    pub const fn output_size(self) -> usize {
        match self {
            Self::Sha1 => 20,
            Self::Sha224 | Self::Sha512_224 => 28,
            Self::Sha256 => 32,
            Self::Sha384 => 48,
            Self::Sha512 => 64,
            Self::Sha512_256 => 32,
            Self::Md5 => 16,
        }
    }

    fn evp(self) -> *const aws_lc::EVP_MD {
        // SAFETY: each function returns a process-lifetime algorithm
        // descriptor and accepts no arguments.
        unsafe {
            match self {
                Self::Sha1 => aws_lc::EVP_sha1(),
                Self::Sha224 => aws_lc::EVP_sha224(),
                Self::Sha256 => aws_lc::EVP_sha256(),
                Self::Sha384 => aws_lc::EVP_sha384(),
                Self::Sha512 => aws_lc::EVP_sha512(),
                Self::Sha512_224 => aws_lc::EVP_sha512_224(),
                Self::Sha512_256 => aws_lc::EVP_sha512_256(),
                Self::Md5 => aws_lc::EVP_md5(),
            }
        }
    }
}

/// Failure from a sensitive backend context.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SensitiveBackendError {
    /// The backend could not allocate a context.
    AllocationFailure,
    /// The backend rejected an initialization, update, or finalization call.
    BackendFailure,
    /// The requested or supplied digest output size is invalid.
    InvalidOutputSize,
}

/// Heap-resident AWS-LC digest context.
pub struct DigestContext {
    context: NonNull<aws_lc::EVP_MD_CTX>,
    algorithm: DigestAlgorithm,
}

// SAFETY: the pointer is uniquely owned, AWS-LC permits contexts to be moved
// between threads, and the safe API never permits concurrent access.
unsafe impl Send for DigestContext {}

impl DigestContext {
    /// Allocates and initializes a digest context.
    pub fn new(algorithm: DigestAlgorithm) -> Result<Self, SensitiveBackendError> {
        // Idempotent, and required if AWS-LC is ever built without its static
        // initializer.
        aws_lc::init();
        // SAFETY: no arguments; a non-null result is uniquely allocated and
        // released by this type's Drop implementation.
        let context = unsafe { aws_lc::EVP_MD_CTX_new() };
        let context = NonNull::new(context).ok_or(SensitiveBackendError::AllocationFailure)?;
        let instance = Self { context, algorithm };

        let descriptor = algorithm.evp();
        if descriptor.is_null() {
            return Err(SensitiveBackendError::BackendFailure);
        }
        // SAFETY: both pointers are valid and uniquely accessed here.
        if unsafe { aws_lc::EVP_DigestInit(instance.context.as_ptr(), descriptor) } != 1 {
            return Err(SensitiveBackendError::BackendFailure);
        }
        Ok(instance)
    }

    /// Returns the exact final output size.
    #[must_use]
    pub const fn output_size(&self) -> usize {
        self.algorithm.output_size()
    }

    /// Adds message bytes to the context.
    pub fn update(&mut self, input: &[u8]) -> Result<(), SensitiveBackendError> {
        // SAFETY: the context is valid and uniquely borrowed; the slice
        // pointer is valid for exactly `input.len()` bytes, including zero.
        let result = unsafe {
            aws_lc::EVP_DigestUpdate(
                self.context.as_ptr(),
                input.as_ptr().cast::<c_void>(),
                input.len(),
            )
        };
        if result == 1 {
            Ok(())
        } else {
            Err(SensitiveBackendError::BackendFailure)
        }
    }

    /// Finalizes into the caller-owned, wipeable output buffer.
    pub fn finalize_into(&mut self, output: &mut [u8]) -> Result<(), SensitiveBackendError> {
        if output.len() != self.algorithm.output_size() {
            return Err(SensitiveBackendError::InvalidOutputSize);
        }
        let mut output_size = 0_u32;
        // SAFETY: the initialized context is uniquely borrowed and the output
        // slice has the exact size required by the selected algorithm.
        let result = unsafe {
            aws_lc::EVP_DigestFinal_ex(self.context.as_ptr(), output.as_mut_ptr(), &mut output_size)
        };
        if result == 1 && output_size as usize == self.algorithm.output_size() {
            Ok(())
        } else {
            output.zeroize();
            Err(SensitiveBackendError::BackendFailure)
        }
    }
}

impl Drop for DigestContext {
    fn drop(&mut self) {
        // SAFETY: this type uniquely owns a live AWS-LC context. Cleanse uses
        // AWS-LC's non-elidable erasure before free releases the allocation.
        unsafe {
            aws_lc::EVP_MD_CTX_cleanse(self.context.as_ptr());
            aws_lc::EVP_MD_CTX_free(self.context.as_ptr());
        }
    }
}

/// Heap-resident AWS-LC HMAC context.
pub struct HmacContext {
    context: NonNull<aws_lc::HMAC_CTX>,
    algorithm: DigestAlgorithm,
}

// SAFETY: the pointer is uniquely owned, AWS-LC permits contexts to be moved
// between threads, and the safe API never permits concurrent access.
unsafe impl Send for HmacContext {}

impl HmacContext {
    /// Allocates and initializes an HMAC context.
    pub fn new(algorithm: DigestAlgorithm, key: &[u8]) -> Result<Self, SensitiveBackendError> {
        // Idempotent, and required if AWS-LC is ever built without its static
        // initializer.
        aws_lc::init();
        // SAFETY: no arguments; a non-null result is uniquely allocated and
        // released by this type's Drop implementation.
        let context = unsafe { aws_lc::HMAC_CTX_new() };
        let context = NonNull::new(context).ok_or(SensitiveBackendError::AllocationFailure)?;
        let instance = Self { context, algorithm };

        let descriptor = algorithm.evp();
        if descriptor.is_null() {
            return Err(SensitiveBackendError::BackendFailure);
        }
        // SAFETY: the context is uniquely owned, the key pointer is valid for
        // `key.len()` bytes, and the engine pointer is intentionally null.
        let result = unsafe {
            aws_lc::HMAC_Init_ex(
                instance.context.as_ptr(),
                key.as_ptr().cast::<c_void>(),
                key.len(),
                descriptor,
                std::ptr::null_mut(),
            )
        };
        if result != 1 {
            return Err(SensitiveBackendError::BackendFailure);
        }
        Ok(instance)
    }

    /// Returns the exact final output size.
    #[must_use]
    pub const fn output_size(&self) -> usize {
        self.algorithm.output_size()
    }

    /// Adds message bytes to the context.
    pub fn update(&mut self, input: &[u8]) -> Result<(), SensitiveBackendError> {
        // SAFETY: the context is valid and uniquely borrowed; the slice
        // pointer is valid for exactly `input.len()` bytes, including zero.
        let result =
            unsafe { aws_lc::HMAC_Update(self.context.as_ptr(), input.as_ptr(), input.len()) };
        if result == 1 {
            Ok(())
        } else {
            Err(SensitiveBackendError::BackendFailure)
        }
    }

    /// Resets a finalized context to the original key and digest.
    pub fn reset(&mut self) -> Result<(), SensitiveBackendError> {
        // SAFETY: the context is valid and uniquely borrowed. Null key and
        // digest pointers instruct AWS-LC to reuse the initialized values.
        let result = unsafe {
            aws_lc::HMAC_Init_ex(
                self.context.as_ptr(),
                std::ptr::null(),
                0,
                std::ptr::null(),
                std::ptr::null_mut(),
            )
        };
        if result == 1 {
            Ok(())
        } else {
            Err(SensitiveBackendError::BackendFailure)
        }
    }

    /// Finalizes into the caller-owned, wipeable output buffer.
    pub fn finalize_into(&mut self, output: &mut [u8]) -> Result<(), SensitiveBackendError> {
        if output.len() != self.algorithm.output_size() {
            return Err(SensitiveBackendError::InvalidOutputSize);
        }
        let mut output_size = 0_u32;
        // SAFETY: the initialized context is uniquely borrowed and the output
        // slice has the exact size required by the selected algorithm.
        let result = unsafe {
            aws_lc::HMAC_Final(self.context.as_ptr(), output.as_mut_ptr(), &mut output_size)
        };
        if result == 1 && output_size as usize == self.algorithm.output_size() {
            Ok(())
        } else {
            output.zeroize();
            Err(SensitiveBackendError::BackendFailure)
        }
    }
}

impl Drop for HmacContext {
    fn drop(&mut self) {
        // SAFETY: this type uniquely owns a live context. AWS-LC documents
        // HMAC_CTX_free as calling HMAC_CTX_cleanup, which zeroizes the full
        // flat context before releasing its allocation.
        unsafe { aws_lc::HMAC_CTX_free(self.context.as_ptr()) }
    }
}

/// Derives PBKDF2 output using an AWS-LC HMAC context whose keyed state is
/// explicitly cleansed on drop. Intermediate U/T blocks live only in
/// zeroizing heap buffers.
pub fn pbkdf2_hmac(
    algorithm: DigestAlgorithm,
    password: &[u8],
    salt: &[u8],
    iterations: u32,
    output: &mut [u8],
) -> Result<(), SensitiveBackendError> {
    if iterations == 0 || output.is_empty() {
        output.zeroize();
        return Err(SensitiveBackendError::InvalidOutputSize);
    }

    let result = (|| {
        let digest_size = algorithm.output_size();
        let block_count = output.len().div_ceil(digest_size);
        let mut context = HmacContext::new(algorithm, password)?;
        let mut u = Zeroizing::new(vec![0_u8; digest_size]);
        let mut accumulator = Zeroizing::new(vec![0_u8; digest_size]);
        let mut offset = 0_usize;

        for block in 1..=block_count {
            let block =
                u32::try_from(block).map_err(|_| SensitiveBackendError::InvalidOutputSize)?;
            context.reset()?;
            context.update(salt)?;
            context.update(&block.to_be_bytes())?;
            context.finalize_into(&mut u)?;
            accumulator.copy_from_slice(&u);

            for _ in 1..iterations {
                context.reset()?;
                context.update(&u)?;
                context.finalize_into(&mut u)?;
                for (accumulator, value) in accumulator.iter_mut().zip(u.iter()) {
                    *accumulator ^= value;
                }
            }

            let take = digest_size.min(output.len() - offset);
            output[offset..offset + take].copy_from_slice(&accumulator[..take]);
            offset += take;
        }
        Ok(())
    })();
    if result.is_err() {
        output.zeroize();
    }
    result
}

struct Blake2bState {
    core: Blake2bVarCore,
    buffer: Blake2bBuffer,
    output_size: usize,
}

// `zeroize_flat_type` requires every field to be flat and have no destructor.
// These assertions guard the destructor part of that audited invariant. The
// exact dependency versions are pinned by the workspace lockfile.
const _: () = assert!(!std::mem::needs_drop::<Blake2bVarCore>());
const _: () = assert!(!std::mem::needs_drop::<Blake2bBuffer>());

impl Drop for Blake2bState {
    fn drop(&mut self) {
        // SAFETY: the pinned Blake2bVarCore contains only integer SIMD lanes
        // and counters; Buffer contains only a GenericArray<u8>, u8 cursor,
        // and PhantomData; output_size is usize. None owns outside storage or
        // has Drop, and all-zero bit patterns are valid for every field.
        unsafe { zeroize_flat_type(self as *mut Self) }
    }
}

#[derive(Default)]
struct Blake2bOutput(Output<Blake2bVarCore>);

impl Drop for Blake2bOutput {
    fn drop(&mut self) {
        self.0.as_mut_slice().zeroize();
    }
}

/// Heap-resident RustCrypto BLAKE2b context with volatile state erasure.
pub struct Blake2bContext {
    state: Box<Blake2bState>,
}

impl Blake2bContext {
    /// Creates a BLAKE2b context for the requested output size.
    pub fn new(output_size: usize) -> Result<Self, SensitiveBackendError> {
        let core = Blake2bVarCore::new(output_size)
            .map_err(|_| SensitiveBackendError::InvalidOutputSize)?;
        // The state is placed on the heap before any caller-supplied bytes are
        // processed. Moving this public initial state into Box is non-sensitive.
        Ok(Self {
            state: Box::new(Blake2bState {
                core,
                buffer: Blake2bBuffer::default(),
                output_size,
            }),
        })
    }

    /// Adds message bytes without moving the heap-resident context.
    pub fn update(&mut self, input: &[u8]) {
        let state = &mut *self.state;
        state
            .buffer
            .digest_blocks(input, |blocks| state.core.update_blocks(blocks));
    }

    /// Finalizes into caller-owned output and then erases the context on drop.
    pub fn finalize_into(&mut self, output: &mut [u8]) -> Result<(), SensitiveBackendError> {
        if output.len() != self.state.output_size {
            return Err(SensitiveBackendError::InvalidOutputSize);
        }
        let mut full_output = Blake2bOutput::default();
        self.state
            .core
            .finalize_variable_core(&mut self.state.buffer, &mut full_output.0);
        output.copy_from_slice(&full_output.0[..self.state.output_size]);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aws_lc_md5_matches_known_vector() {
        let mut context =
            DigestContext::new(DigestAlgorithm::Md5).expect("MD5 context must initialize");
        context.update(b"abc").expect("MD5 update must succeed");
        let mut output = [0_u8; 16];
        context
            .finalize_into(&mut output)
            .expect("MD5 finalization must succeed");
        assert_eq!(
            output,
            [
                0x90, 0x01, 0x50, 0x98, 0x3c, 0xd2, 0x4f, 0xb0, 0xd6, 0x96, 0x3f, 0x7d, 0x28, 0xe1,
                0x7f, 0x72,
            ]
        );
    }

    #[test]
    fn aws_lc_hmac_md5_matches_known_vector() {
        let mut context =
            HmacContext::new(DigestAlgorithm::Md5, b"key").expect("HMAC context must initialize");
        context.update(b"abc").expect("HMAC update must succeed");
        let mut output = [0_u8; 16];
        context
            .finalize_into(&mut output)
            .expect("HMAC finalization must succeed");
        assert_eq!(
            output,
            [
                0xd2, 0xfe, 0x98, 0x06, 0x3f, 0x87, 0x6b, 0x03, 0x19, 0x3a, 0xfb, 0x49, 0xb4, 0x97,
                0x95, 0x91,
            ]
        );
    }

    #[test]
    fn aws_lc_hmac_md5_normalizes_a_key_larger_than_one_block() {
        let mut context = HmacContext::new(DigestAlgorithm::Md5, &[0xaa; 80])
            .expect("HMAC context must initialize");
        context
            .update(b"wiping context regression")
            .expect("HMAC update must succeed");
        let mut output = [0_u8; 16];
        context
            .finalize_into(&mut output)
            .expect("HMAC finalization must succeed");
        assert_eq!(
            output,
            [
                0x01, 0xda, 0x90, 0x3f, 0x59, 0xee, 0xfe, 0x7a, 0x42, 0xcb, 0xfa, 0x2d, 0xa2, 0x0a,
                0x0c, 0xdd,
            ]
        );
    }

    #[test]
    fn erasing_pbkdf2_hmac_sha256_matches_known_vector() {
        let mut output = [0_u8; 32];
        pbkdf2_hmac(
            DigestAlgorithm::Sha256,
            b"password",
            b"salt",
            2,
            &mut output,
        )
        .expect("PBKDF2-HMAC-SHA256");
        assert_eq!(
            output,
            [
                0xae, 0x4d, 0x0c, 0x95, 0xaf, 0x6b, 0x46, 0xd3, 0x2d, 0x0a, 0xdf, 0xf9, 0x28, 0xf0,
                0x6d, 0xd0, 0x2a, 0x30, 0x3f, 0x8e, 0xf3, 0xc2, 0x51, 0xdf, 0xd6, 0xe2, 0xd8, 0x5a,
                0x95, 0x47, 0x4c, 0x43,
            ],
        );
    }

    #[test]
    fn blake2b_matches_known_vector() {
        let mut context = Blake2bContext::new(64).expect("BLAKE2b context must initialize");
        context.update(b"abc");
        let mut output = [0_u8; 64];
        context
            .finalize_into(&mut output)
            .expect("BLAKE2b finalization must succeed");
        assert_eq!(
            &output[..8],
            &[0xba, 0x80, 0xa5, 0x3f, 0x98, 0x1c, 0x4d, 0x0d]
        );
    }
}
