use aead::generic_array::{typenum::U16, ArrayLength, GenericArray};

const BLOCK_SIZE: usize = 16;
pub(crate) type Block = GenericArray<u8, U16>;

#[inline]
pub(crate) fn inplace_xor<T, U>(a: &mut GenericArray<T, U>, b: &GenericArray<T, U>)
where
    U: ArrayLength<T>,
    T: core::ops::BitXor<Output = T> + Copy,
{
    for (aa, bb) in a.as_mut_slice().iter_mut().zip(b.as_slice()) {
        *aa = *aa ^ *bb;
    }
}

/// Doubles a block, in GF(2^128).
///
/// Adapted from https://github.com/RustCrypto/universal-hashes/blob/9b0ac5d1/polyval/src/mulx.rs#L5-L18
#[inline]
pub(crate) fn double(block: &Block) -> Block {
    let mut v = u128::from_be_bytes((*block).into());
    let v_hi = v >> 127;

    // If v_hi = 0, return (v << 1)
    // If v_hi = 1, return (v << 1) xor (0b0...010000111)
    v <<= 1;
    v ^= v_hi ^ (v_hi << 1) ^ (v_hi << 2) ^ (v_hi << 7);
    v.to_be_bytes().into()
}

/// Counts the number of non-trailing zeros in the binary representation.
///
/// Defined in https://www.rfc-editor.org/rfc/rfc7253.html#section-2
#[inline]
pub(crate) fn ntz(n: usize) -> usize {
    n.trailing_zeros().try_into().unwrap()
}

#[inline]
pub(crate) fn split_into_two_blocks(two_blocks: &mut [u8]) -> [&mut Block; 2] {
    let (b0, b1) = two_blocks.split_at_mut(BLOCK_SIZE);
    [b0.into(), b1.into()]
}
