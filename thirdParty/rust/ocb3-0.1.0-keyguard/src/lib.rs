#![no_std]
#![cfg_attr(docsrs, feature(doc_cfg))]
#![doc = include_str!("../README.md")]
#![doc(
    html_logo_url = "https://raw.githubusercontent.com/RustCrypto/meta/master/logo.svg",
    html_favicon_url = "https://raw.githubusercontent.com/RustCrypto/meta/master/logo.svg"
)]
#![deny(unsafe_code)]
#![warn(missing_docs, rust_2018_idioms)]

/// Constants used, reexported for convenience.
pub mod consts {
    pub use cipher::consts::{U0, U12, U15, U16, U6};
}

mod util;

pub use aead::{
    self, generic_array::GenericArray, AeadCore, AeadInPlace, Error, KeyInit, KeySizeUser,
};

use crate::util::{double, inplace_xor, ntz, Block};
use cipher::{
    consts::{U0, U12, U16},
    BlockDecrypt, BlockEncrypt, BlockSizeUser,
};
use core::marker::PhantomData;
use subtle::ConstantTimeEq;
#[cfg(feature = "zeroize")]
use zeroize::Zeroize;

/// Number of L values to be precomputed. Precomputing m values, allows
/// processing inputs of length up to 2^m blocks (2^m * 16 bytes) without
/// needing to calculate L values at runtime.
///
/// By setting this to 32, we can process inputs of length up to 1 terabyte.
#[cfg(target_pointer_width = "64")]
const L_TABLE_SIZE: usize = 32;

/// Number of L values to be precomputed. Precomputing m values, allows
/// processing inputs of length up to 2^m blocks (2^m * 16 bytes) without
/// needing to calculate L values at runtime.
#[cfg(target_pointer_width = "32")]
const L_TABLE_SIZE: usize = 16;

/// Max associated data.
pub const A_MAX: usize = 1 << (L_TABLE_SIZE + 4);
/// Max plaintext.
pub const P_MAX: usize = 1 << (L_TABLE_SIZE + 4);
/// Max ciphertext.
pub const C_MAX: usize = 1 << (L_TABLE_SIZE + 4);

/// OCB3 nonce
pub type Nonce<NonceSize> = GenericArray<u8, NonceSize>;

/// OCB3 tag
pub type Tag<TagSize> = GenericArray<u8, TagSize>;

mod sealed {
    use aead::generic_array::{
        typenum::{GrEq, IsGreaterOrEqual, IsLessOrEqual, LeEq, NonZero, U15, U16, U6},
        ArrayLength,
    };

    /// Sealed trait for nonce sizes in the range of `6..=15` bytes.
    pub trait NonceSizes: ArrayLength<u8> {}

    impl<T> NonceSizes for T
    where
        T: ArrayLength<u8> + IsGreaterOrEqual<U6> + IsLessOrEqual<U15>,
        GrEq<T, U6>: NonZero,
        LeEq<T, U15>: NonZero,
    {
    }

    /// Sealed trait for tag sizes in the range of `1..=16` bytes.
    pub trait TagSizes: ArrayLength<u8> {}

    impl<T> TagSizes for T
    where
        T: ArrayLength<u8> + NonZero + IsLessOrEqual<U16>,
        LeEq<T, U16>: NonZero,
    {
    }
}

/// OCB3: generic over a block cipher implementation, nonce size, and tag size.
///
/// - `NonceSize`: max of 15-bytes, default and recommended size of 12-bytes (96-bits).
///   We further restrict the minimum nonce size to 6-bytes to prevent an attack described in
///   the following paper: <https://eprint.iacr.org/2023/326.pdf>.
/// - `TagSize`: non-zero, max of 16-bytes, default and recommended size of 16-bytes.
///
/// Compilation will fail if the size conditions are not satisfied:
///
/// ```rust,compile_fail
/// # use aes::Aes128;
/// # use ocb3::{aead::{consts::U5, KeyInit}, Ocb3};
/// # let key = [42; 16].into();
/// // Invalid nonce size equal to 5 bytes
/// let cipher = Ocb3::<Aes128, U5>::new(&key);
/// ```
///
/// ```rust,compile_fail
/// # use aes::Aes128;
/// # use ocb3::{aead::{consts::U16, KeyInit}, Ocb3};
/// # let key = [42; 16].into();
/// // Invalid nonce size equal to 16 bytes
/// let cipher = Ocb3::<Aes128, U16>::new(&key);
/// ```
///
/// ```rust,compile_fail
/// # use aes::Aes128;
/// # use ocb3::{aead::{consts::{U12, U0}, KeyInit}, Ocb3};
/// # let key = [42; 16].into();
/// // Invalid tag size equal to 0 bytes
/// let cipher = Ocb3::<Aes128, U12, U0>::new(&key);
/// ```
///
/// ```rust,compile_fail
/// # use aes::Aes128;
/// # use ocb3::{aead::{consts::{U12, U20}, KeyInit}, Ocb3};
/// # let key = [42; 16].into();
/// // Invalid tag size equal to 20 bytes
/// let cipher = Ocb3::<Aes128, U12, U20>::new(&key);
/// ```
#[derive(Clone)]
pub struct Ocb3<Cipher, NonceSize = U12, TagSize = U16>
where
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    cipher: Cipher,
    nonce_size: PhantomData<NonceSize>,
    tag_size: PhantomData<TagSize>,
    // precomputed key-dependent variables
    ll_star: Block,
    ll_dollar: Block,
    // list of pre-computed L values
    ll: [Block; L_TABLE_SIZE],
}

#[cfg(feature = "zeroize")]
impl<Cipher, NonceSize, TagSize> Drop for Ocb3<Cipher, NonceSize, TagSize>
where
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    fn drop(&mut self) {
        self.ll_star.as_mut_slice().zeroize();
        self.ll_dollar.as_mut_slice().zeroize();
        for block in &mut self.ll {
            block.as_mut_slice().zeroize();
        }
    }
}

/// Output of the HASH function defined in https://www.rfc-editor.org/rfc/rfc7253.html#section-4.1
type SumSize = U16;
type Sum = GenericArray<u8, SumSize>;

impl<Cipher, NonceSize, TagSize> KeySizeUser for Ocb3<Cipher, NonceSize, TagSize>
where
    Cipher: KeySizeUser,
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    type KeySize = Cipher::KeySize;
}

impl<Cipher, NonceSize, TagSize> KeyInit for Ocb3<Cipher, NonceSize, TagSize>
where
    Cipher: BlockSizeUser<BlockSize = U16> + BlockEncrypt + KeyInit + BlockDecrypt,
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    fn new(key: &aead::Key<Self>) -> Self {
        Cipher::new(key).into()
    }
}

impl<Cipher, NonceSize, TagSize> AeadCore for Ocb3<Cipher, NonceSize, TagSize>
where
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    type NonceSize = NonceSize;
    type TagSize = TagSize;
    type CiphertextOverhead = U0;
}

impl<Cipher, NonceSize, TagSize> From<Cipher> for Ocb3<Cipher, NonceSize, TagSize>
where
    Cipher: BlockSizeUser<BlockSize = U16> + BlockEncrypt + BlockDecrypt,
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    fn from(cipher: Cipher) -> Self {
        let (ll_star, ll_dollar, ll) = key_dependent_variables(&cipher);

        Self {
            cipher,
            nonce_size: PhantomData,
            tag_size: PhantomData,
            ll_star,
            ll_dollar,
            ll,
        }
    }
}

/// Computes key-dependent variables defined in
/// https://www.rfc-editor.org/rfc/rfc7253.html#section-4.1
fn key_dependent_variables<Cipher: BlockSizeUser<BlockSize = U16> + BlockEncrypt>(
    cipher: &Cipher,
) -> (Block, Block, [Block; L_TABLE_SIZE]) {
    let mut zeros = [0u8; 16];
    let ll_star = Block::from_mut_slice(&mut zeros);
    cipher.encrypt_block(ll_star);
    let ll_dollar = double(ll_star);

    let mut ll = [Block::default(); L_TABLE_SIZE];
    let mut ll_i = ll_dollar;
    #[allow(clippy::needless_range_loop)]
    for i in 0..L_TABLE_SIZE {
        ll_i = double(&ll_i);
        ll[i] = ll_i
    }
    (*ll_star, ll_dollar, ll)
}

impl<Cipher, NonceSize, TagSize> AeadInPlace for Ocb3<Cipher, NonceSize, TagSize>
where
    Cipher: BlockSizeUser<BlockSize = U16> + BlockEncrypt + BlockDecrypt,
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    fn encrypt_in_place_detached(
        &self,
        nonce: &Nonce<NonceSize>,
        associated_data: &[u8],
        buffer: &mut [u8],
    ) -> aead::Result<aead::Tag<Self>> {
        if (buffer.len() > P_MAX) || (associated_data.len() > A_MAX) {
            unimplemented!()
        }

        // First, try to process many blocks at once.
        let (processed_bytes, mut offset_i, mut checksum_i) = self.wide_encrypt(nonce, buffer);

        let mut i = (processed_bytes / 16) + 1;

        // Then, process the remaining blocks.
        for p_i in buffer[processed_bytes..].chunks_exact_mut(16) {
            let p_i = Block::from_mut_slice(p_i);
            // offset_i = offset_{i-1} xor L_{ntz(i)}
            inplace_xor(&mut offset_i, &self.ll[ntz(i)]);
            // checksum_i = checksum_{i-1} xor p_i
            inplace_xor(&mut checksum_i, p_i);
            // c_i = offset_i xor ENCIPHER(K, p_i xor offset_i)
            let c_i = p_i;
            inplace_xor(c_i, &offset_i);
            self.cipher.encrypt_block(c_i);
            inplace_xor(c_i, &offset_i);

            i += 1;
        }

        // Process any partial blocks.
        if (buffer.len() % 16) != 0 {
            let processed_bytes = (i - 1) * 16;
            let remaining_bytes = buffer.len() - processed_bytes;

            // offset_* = offset_m xor L_*
            inplace_xor(&mut offset_i, &self.ll_star);
            // Pad = ENCIPHER(K, offset_*)
            let mut pad = Block::default();
            inplace_xor(&mut pad, &offset_i);
            self.cipher.encrypt_block(&mut pad);
            // checksum_* = checksum_m xor (P_* || 1 || zeros(127-bitlen(P_*)))
            let checksum_rhs = &mut [0u8; 16];
            checksum_rhs[..remaining_bytes].copy_from_slice(&buffer[processed_bytes..]);
            checksum_rhs[remaining_bytes] = 0b1000_0000;
            inplace_xor(&mut checksum_i, Block::from_slice(checksum_rhs));
            // C_* = P_* xor Pad[1..bitlen(P_*)]
            let p_star = &mut buffer[processed_bytes..];
            let pad = &mut pad[..p_star.len()];
            for (aa, bb) in p_star.iter_mut().zip(pad) {
                *aa ^= *bb;
            }
        }

        let tag = self.compute_tag(associated_data, &mut checksum_i, &offset_i);

        Ok(tag)
    }

    fn decrypt_in_place_detached(
        &self,
        nonce: &Nonce<NonceSize>,
        associated_data: &[u8],
        buffer: &mut [u8],
        tag: &aead::Tag<Self>,
    ) -> aead::Result<()> {
        let expected_tag = self.decrypt_in_place_return_tag(nonce, associated_data, buffer);
        if expected_tag.ct_eq(tag).into() {
            Ok(())
        } else {
            Err(Error)
        }
    }
}

impl<Cipher, NonceSize, TagSize> Ocb3<Cipher, NonceSize, TagSize>
where
    Cipher: BlockSizeUser<BlockSize = U16> + BlockEncrypt + BlockDecrypt,
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    /// Decrypts in place and returns expected tag.
    pub(crate) fn decrypt_in_place_return_tag(
        &self,
        nonce: &Nonce<NonceSize>,
        associated_data: &[u8],
        buffer: &mut [u8],
    ) -> aead::Tag<Self> {
        if (buffer.len() > C_MAX) || (associated_data.len() > A_MAX) {
            unimplemented!()
        }

        // First, try to process many blocks at once.
        let (processed_bytes, mut offset_i, mut checksum_i) = self.wide_decrypt(nonce, buffer);

        let mut i = (processed_bytes / 16) + 1;

        // Then, process the remaining blocks.
        for c_i in buffer[processed_bytes..].chunks_exact_mut(16) {
            let c_i = Block::from_mut_slice(c_i);
            // offset_i = offset_{i-1} xor L_{ntz(i)}
            inplace_xor(&mut offset_i, &self.ll[ntz(i)]);
            // p_i = offset_i xor DECIPHER(K, c_i xor offset_i)
            let p_i = c_i;
            inplace_xor(p_i, &offset_i);
            self.cipher.decrypt_block(p_i);
            inplace_xor(p_i, &offset_i);
            // checksum_i = checksum_{i-1} xor p_i
            inplace_xor(&mut checksum_i, p_i);

            i += 1;
        }

        // Process any partial blocks.
        if (buffer.len() % 16) != 0 {
            let processed_bytes = (i - 1) * 16;
            let remaining_bytes = buffer.len() - processed_bytes;

            // offset_* = offset_m xor L_*
            inplace_xor(&mut offset_i, &self.ll_star);
            // Pad = ENCIPHER(K, offset_*)
            let mut pad = Block::default();
            inplace_xor(&mut pad, &offset_i);
            self.cipher.encrypt_block(&mut pad);
            // P_* = C_* xor Pad[1..bitlen(C_*)]
            let c_star = &mut buffer[processed_bytes..];
            let pad = &mut pad[..c_star.len()];
            for (aa, bb) in c_star.iter_mut().zip(pad) {
                *aa ^= *bb;
            }
            // checksum_* = checksum_m xor (P_* || 1 || zeros(127-bitlen(P_*)))
            let checksum_rhs = &mut [0u8; 16];
            checksum_rhs[..remaining_bytes].copy_from_slice(&buffer[processed_bytes..]);
            checksum_rhs[remaining_bytes] = 0b1000_0000;
            inplace_xor(&mut checksum_i, Block::from_slice(checksum_rhs));
        }

        self.compute_tag(associated_data, &mut checksum_i, &offset_i)
    }

    /// Encrypts plaintext in groups of two.
    ///
    /// Adapted from https://www.cs.ucdavis.edu/~rogaway/ocb/news/code/ocb.c
    fn wide_encrypt(&self, nonce: &Nonce<NonceSize>, buffer: &mut [u8]) -> (usize, Block, Block) {
        const WIDTH: usize = 2;
        let split_into_blocks = crate::util::split_into_two_blocks;

        let mut i = 1;

        let mut offset_i = [Block::default(); WIDTH];
        offset_i[offset_i.len() - 1] = initial_offset(&self.cipher, nonce, TagSize::to_u32());
        let mut checksum_i = Block::default();
        for wide_blocks in buffer.chunks_exact_mut(16 * WIDTH) {
            let p_i = split_into_blocks(wide_blocks);

            // checksum_i = checksum_{i-1} xor p_i
            for p_ij in &p_i {
                inplace_xor(&mut checksum_i, p_ij);
            }

            // offset_i = offset_{i-1} xor L_{ntz(i)}
            offset_i[0] = offset_i[offset_i.len() - 1];
            inplace_xor(&mut offset_i[0], &self.ll[ntz(i)]);
            for j in 1..p_i.len() {
                offset_i[j] = offset_i[j - 1];
                inplace_xor(&mut offset_i[j], &self.ll[ntz(i + j)]);
            }

            // c_i = offset_i xor ENCIPHER(K, p_i xor offset_i)
            for j in 0..p_i.len() {
                inplace_xor(p_i[j], &offset_i[j]);
                self.cipher.encrypt_block(p_i[j]);
                inplace_xor(p_i[j], &offset_i[j])
            }

            i += WIDTH;
        }

        let processed_bytes = (buffer.len() / (WIDTH * 16)) * (WIDTH * 16);

        (processed_bytes, offset_i[offset_i.len() - 1], checksum_i)
    }

    /// Decrypts plaintext in groups of two.
    ///
    /// Adapted from https://www.cs.ucdavis.edu/~rogaway/ocb/news/code/ocb.c
    fn wide_decrypt(&self, nonce: &Nonce<NonceSize>, buffer: &mut [u8]) -> (usize, Block, Block) {
        const WIDTH: usize = 2;
        let split_into_blocks = crate::util::split_into_two_blocks;

        let mut i = 1;

        let mut offset_i = [Block::default(); WIDTH];
        offset_i[offset_i.len() - 1] = initial_offset(&self.cipher, nonce, TagSize::to_u32());
        let mut checksum_i = Block::default();
        for wide_blocks in buffer.chunks_exact_mut(16 * WIDTH) {
            let c_i = split_into_blocks(wide_blocks);

            // offset_i = offset_{i-1} xor L_{ntz(i)}
            offset_i[0] = offset_i[offset_i.len() - 1];
            inplace_xor(&mut offset_i[0], &self.ll[ntz(i)]);
            for j in 1..c_i.len() {
                offset_i[j] = offset_i[j - 1];
                inplace_xor(&mut offset_i[j], &self.ll[ntz(i + j)]);
            }

            // p_i = offset_i xor DECIPHER(K, c_i xor offset_i)
            // checksum_i = checksum_{i-1} xor p_i
            for j in 0..c_i.len() {
                inplace_xor(c_i[j], &offset_i[j]);
                self.cipher.decrypt_block(c_i[j]);
                inplace_xor(c_i[j], &offset_i[j]);
                inplace_xor(&mut checksum_i, c_i[j]);
            }

            i += WIDTH;
        }

        let processed_bytes = (buffer.len() / (WIDTH * 16)) * (WIDTH * 16);

        (processed_bytes, offset_i[offset_i.len() - 1], checksum_i)
    }
}

/// Computes nonce-dependent variables as defined
/// in https://www.rfc-editor.org/rfc/rfc7253.html#section-4.2
fn nonce_dependent_variables<
    Cipher: BlockSizeUser<BlockSize = U16> + BlockEncrypt,
    NonceSize: sealed::NonceSizes,
>(
    cipher: &Cipher,
    nn: &Nonce<NonceSize>,
    tag_len: u32,
) -> (usize, [u8; 24]) {
    // Nonce = num2str(TAGLEN mod 128,7) || zeros(120-bitlen(N)) || 1 || N
    let mut nonce = [0u8; 16];
    nonce[0] = (((tag_len * 8) % 128) << 1) as u8;

    let start = 16 - NonceSize::to_usize();
    nonce[start..16].copy_from_slice(nn.as_slice());
    nonce[16 - NonceSize::to_usize() - 1] |= 1;

    // Separate the last 6 bits into `bottom`, and the rest into `top`.
    let bottom = nonce[15] & 0b111111;

    let nonce = u128::from_be_bytes(nonce);
    let top = nonce & !0b111111;

    let mut ktop = Block::from(top.to_be_bytes());
    cipher.encrypt_block(&mut ktop);
    let ktop = ktop.as_mut_slice();

    // stretch = Ktop || (Ktop[1..64] xor Ktop[9..72])
    let mut stretch = [0u8; 24];
    stretch[..16].copy_from_slice(ktop);
    for i in 0..8 {
        ktop[i] ^= ktop[i + 1];
    }
    stretch[16..].copy_from_slice(&ktop[..8]);

    (bottom as usize, stretch)
}

/// Computes the initial offset as defined
/// in https://www.rfc-editor.org/rfc/rfc7253.html#section-4.2
fn initial_offset<
    Cipher: BlockSizeUser<BlockSize = U16> + BlockEncrypt,
    NonceSize: sealed::NonceSizes,
>(
    cipher: &Cipher,
    nn: &Nonce<NonceSize>,
    tag_size: u32,
) -> Block {
    let (bottom, stretch) = nonce_dependent_variables(cipher, nn, tag_size);
    let stretch_low = u128::from_be_bytes((&stretch[..16]).try_into().unwrap());
    let stretch_hi = u64::from_be_bytes((&stretch[16..24]).try_into().unwrap());
    let stretch_hi = u128::from(stretch_hi);

    // offset_0 = stretch[1+bottom..128+bottom]
    let offset = (stretch_low << bottom) | (stretch_hi >> (64 - bottom));
    offset.to_be_bytes().into()
}

impl<Cipher, NonceSize, TagSize> Ocb3<Cipher, NonceSize, TagSize>
where
    Cipher: BlockSizeUser<BlockSize = U16> + BlockEncrypt,
    NonceSize: sealed::NonceSizes,
    TagSize: sealed::TagSizes,
{
    /// Computes HASH function defined in https://www.rfc-editor.org/rfc/rfc7253.html#section-4.1
    fn hash(&self, associated_data: &[u8]) -> Sum {
        let mut offset_i = Block::default();
        let mut sum_i = Block::default();

        let mut i = 1;
        for a_i in associated_data.chunks_exact(16) {
            // offset_i = offset_{i-1} xor L_{ntz(i)}
            inplace_xor(&mut offset_i, &self.ll[ntz(i)]);
            // Sum_i = Sum_{i-1} xor ENCIPHER(K, A_i xor offset_i)
            let mut a_i = *Block::from_slice(a_i);
            inplace_xor(&mut a_i, &offset_i);
            self.cipher.encrypt_block(&mut a_i);
            inplace_xor(&mut sum_i, &a_i);

            i += 1;
        }

        // Process any partial blocks.
        if (associated_data.len() % 16) != 0 {
            let processed_bytes = (i - 1) * 16;
            let remaining_bytes = associated_data.len() - processed_bytes;

            // offset_* = offset_m xor L_*
            inplace_xor(&mut offset_i, &self.ll_star);
            // CipherInput = (A_* || 1 || zeros(127-bitlen(A_*))) xor offset_*
            let cipher_input = &mut [0u8; 16];
            cipher_input[..remaining_bytes].copy_from_slice(&associated_data[processed_bytes..]);
            cipher_input[remaining_bytes] = 0b1000_0000;
            let cipher_input = Block::from_mut_slice(cipher_input);
            inplace_xor(cipher_input, &offset_i);
            // Sum = Sum_m xor ENCIPHER(K, CipherInput)
            self.cipher.encrypt_block(cipher_input);
            inplace_xor(&mut sum_i, cipher_input);
        }

        sum_i
    }

    fn compute_tag(
        &self,
        associated_data: &[u8],
        checksum_m: &mut Block,
        offset_m: &Block,
    ) -> Tag<TagSize> {
        // Tag = ENCIPHER(K, checksum_m xor offset_m xor L_$) xor HASH(K,A)
        let full_tag = checksum_m;
        inplace_xor(full_tag, offset_m);
        inplace_xor(full_tag, &self.ll_dollar);
        self.cipher.encrypt_block(full_tag);
        inplace_xor(full_tag, &self.hash(associated_data));

        // truncate the tag to the required length
        Tag::clone_from_slice(&full_tag[..TagSize::to_usize()])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use hex_literal::hex;

    #[test]
    fn double_basic_test() {
        let zero = Block::from(hex!("00000000000000000000000000000000"));
        assert_eq!(zero, double(&zero));
        let one = Block::from(hex!("00000000000000000000000000000001"));
        let two = Block::from(hex!("00000000000000000000000000000002"));
        assert_eq!(two, double(&one));
    }

    #[test]
    fn rfc7253_key_dependent_constants() {
        // Test vector from page 17 of https://www.rfc-editor.org/rfc/rfc7253.html
        let key = hex!("000102030405060708090A0B0C0D0E0F");
        let expected_ll_star = Block::from(hex!("C6A13B37878F5B826F4F8162A1C8D879"));
        let expected_ll_dollar = Block::from(hex!("8D42766F0F1EB704DE9F02C54391B075"));
        let expected_ll0 = Block::from(hex!("1A84ECDE1E3D6E09BD3E058A8723606D"));
        let expected_ll1 = Block::from(hex!("3509D9BC3C7ADC137A7C0B150E46C0DA"));

        let cipher = aes::Aes128::new(GenericArray::from_slice(&key));
        let (ll_star, ll_dollar, ll) = key_dependent_variables(&cipher);

        assert_eq!(ll_star, expected_ll_star);
        assert_eq!(ll_dollar, expected_ll_dollar);
        assert_eq!(ll[0], expected_ll0);
        assert_eq!(ll[1], expected_ll1);
    }

    #[cfg(feature = "zeroize")]
    #[test]
    fn ocb_owner_has_drop_zeroization() {
        assert!(core::mem::needs_drop::<Ocb3<aes::Aes128>>());
    }

    #[test]
    fn rfc7253_nonce_dependent_constants() {
        // Test vector from page 17 of https://www.rfc-editor.org/rfc/rfc7253.html
        let key = hex!("000102030405060708090A0B0C0D0E0F");
        let nonce = hex!("BBAA9988776655443322110F");
        let expected_bottom = usize::try_from(15).unwrap();
        let expected_stretch = hex!("9862B0FDEE4E2DD56DBA6433F0125AA2FAD24D13A063F8B8");
        let expected_offset_0 = Block::from(hex!("587EF72716EAB6DD3219F8092D517D69"));

        const TAGLEN: u32 = 16;

        let cipher = aes::Aes128::new(GenericArray::from_slice(&key));
        let (bottom, stretch) = nonce_dependent_variables(&cipher, &Nonce::from(nonce), TAGLEN);
        let offset_0 = initial_offset(&cipher, &Nonce::from(nonce), TAGLEN);

        assert_eq!(bottom, expected_bottom, "bottom");
        assert_eq!(stretch, expected_stretch, "stretch");
        assert_eq!(offset_0, expected_offset_0, "offset");
    }
}
