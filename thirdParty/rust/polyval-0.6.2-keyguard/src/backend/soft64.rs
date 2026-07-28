//! Constant-time software implementation of POLYVAL for 64-bit architectures.
//! Adapted from BearSSL's `ghash_ctmul64.c`:
//!
//! <https://bearssl.org/gitweb/?p=BearSSL;a=blob;f=src/hash/ghash_ctmul64.c;hb=4b6046412>
//!
//! Copyright (c) 2016 Thomas Pornin <pornin@bolet.org>

use core::{
    num::Wrapping,
    ops::{Add, Mul},
};

use universal_hash::{
    consts::{U1, U16},
    crypto_common::{BlockSizeUser, KeySizeUser, ParBlocksSizeUser},
    KeyInit, Reset, UhfBackend, UniversalHash,
};

#[cfg(feature = "zeroize")]
use zeroize::Zeroize;

use crate::{Block, Key, Tag};

/// **POLYVAL**: GHASH-like universal hash over GF(2^128).
#[derive(Clone)]
pub struct Polyval {
    /// GF(2^128) field element input blocks are multiplied by
    h: U64x2,

    /// Field element representing the computed universal hash
    s: U64x2,
}

impl Polyval {
    /// Initialize POLYVAL with the given `H` field element and initial block
    pub fn new_with_init_block(h: &Key, init_block: u128) -> Self {
        Self {
            h: h.into(),
            s: init_block.into(),
        }
    }

    /// Return the current tag without consuming the backend.
    ///
    /// The autodetect wrapper uses this so its `Drop` can erase the original
    /// active union member after finalization instead of moving out a copy.
    pub(crate) fn finalize_tag(&self) -> Tag {
        let mut block = Block::default();

        for (chunk, i) in block.chunks_mut(8).zip(&[self.s.0, self.s.1]) {
            chunk.copy_from_slice(&i.to_le_bytes());
        }

        block
    }
}

impl KeySizeUser for Polyval {
    type KeySize = U16;
}

impl KeyInit for Polyval {
    /// Initialize POLYVAL with the given `H` field element
    fn new(h: &Key) -> Self {
        Self::new_with_init_block(h, 0)
    }
}

impl BlockSizeUser for Polyval {
    type BlockSize = U16;
}

impl ParBlocksSizeUser for Polyval {
    type ParBlocksSize = U1;
}

impl UhfBackend for Polyval {
    fn proc_block(&mut self, x: &Block) {
        let x = U64x2::from(x);
        self.s = (self.s + x) * self.h;
    }
}

impl UniversalHash for Polyval {
    fn update_with_backend(
        &mut self,
        f: impl universal_hash::UhfClosure<BlockSize = Self::BlockSize>,
    ) {
        f.call(self);
    }

    /// Get POLYVAL result (i.e. computed `S` field element)
    fn finalize(self) -> Tag {
        self.finalize_tag()
    }
}

impl Reset for Polyval {
    fn reset(&mut self) {
        self.s = U64x2::default();
    }
}

#[cfg(feature = "zeroize")]
impl Drop for Polyval {
    fn drop(&mut self) {
        self.h.zeroize();
        self.s.zeroize();
    }
}

/// 2 x `u64` values
#[derive(Copy, Clone, Debug, Default, Eq, PartialEq)]
struct U64x2(u64, u64);

impl From<&Block> for U64x2 {
    fn from(bytes: &Block) -> U64x2 {
        U64x2(
            u64::from_le_bytes(bytes[..8].try_into().unwrap()),
            u64::from_le_bytes(bytes[8..].try_into().unwrap()),
        )
    }
}

impl From<u128> for U64x2 {
    fn from(x: u128) -> Self {
        U64x2((x >> 64) as u64, (x) as u64)
    }
}

#[allow(clippy::suspicious_arithmetic_impl)]
impl Add for U64x2 {
    type Output = Self;

    /// Adds two POLYVAL field elements.
    fn add(self, rhs: Self) -> Self::Output {
        U64x2(self.0 ^ rhs.0, self.1 ^ rhs.1)
    }
}

#[allow(clippy::suspicious_arithmetic_impl)]
impl Mul for U64x2 {
    type Output = Self;

    /// Computes carryless POLYVAL multiplication over GF(2^128) in constant time.
    ///
    /// Method described at:
    /// <https://www.bearssl.org/constanttime.html#ghash-for-gcm>
    ///
    /// POLYVAL multiplication is effectively the little endian equivalent of
    /// GHASH multiplication, aside from one small detail described here:
    ///
    /// <https://crypto.stackexchange.com/questions/66448/how-does-bearssls-gcm-modular-reduction-work/66462#66462>
    ///
    /// > The product of two bit-reversed 128-bit polynomials yields the
    /// > bit-reversed result over 255 bits, not 256. The BearSSL code ends up
    /// > with a 256-bit result in zw[], and that value is shifted by one bit,
    /// > because of that reversed convention issue. Thus, the code must
    /// > include a shifting step to put it back where it should
    ///
    /// This shift is unnecessary for POLYVAL and has been removed.
    fn mul(self, rhs: Self) -> Self {
        let h0 = self.0;
        let h1 = self.1;
        let h0r = rev64(h0);
        let h1r = rev64(h1);
        let h2 = h0 ^ h1;
        let h2r = h0r ^ h1r;

        let y0 = rhs.0;
        let y1 = rhs.1;
        let y0r = rev64(y0);
        let y1r = rev64(y1);
        let y2 = y0 ^ y1;
        let y2r = y0r ^ y1r;
        let z0 = bmul64(y0, h0);
        let z1 = bmul64(y1, h1);

        let mut z2 = bmul64(y2, h2);
        let mut z0h = bmul64(y0r, h0r);
        let mut z1h = bmul64(y1r, h1r);
        let mut z2h = bmul64(y2r, h2r);

        z2 ^= z0 ^ z1;
        z2h ^= z0h ^ z1h;
        z0h = rev64(z0h) >> 1;
        z1h = rev64(z1h) >> 1;
        z2h = rev64(z2h) >> 1;

        let v0 = z0;
        let mut v1 = z0h ^ z2;
        let mut v2 = z1 ^ z2h;
        let mut v3 = z1h;

        v2 ^= v0 ^ (v0 >> 1) ^ (v0 >> 2) ^ (v0 >> 7);
        v1 ^= (v0 << 63) ^ (v0 << 62) ^ (v0 << 57);
        v3 ^= v1 ^ (v1 >> 1) ^ (v1 >> 2) ^ (v1 >> 7);
        v2 ^= (v1 << 63) ^ (v1 << 62) ^ (v1 << 57);

        U64x2(v2, v3)
    }
}

#[cfg(feature = "zeroize")]
impl Zeroize for U64x2 {
    fn zeroize(&mut self) {
        self.0.zeroize();
        self.1.zeroize();
    }
}

/// Multiplication in GF(2)[X], truncated to the low 64-bits, with “holes”
/// (sequences of zeroes) to avoid carry spilling.
///
/// When carries do occur, they wind up in a "hole" and are subsequently masked
/// out of the result.
fn bmul64(x: u64, y: u64) -> u64 {
    let x0 = Wrapping(x & 0x1111_1111_1111_1111);
    let x1 = Wrapping(x & 0x2222_2222_2222_2222);
    let x2 = Wrapping(x & 0x4444_4444_4444_4444);
    let x3 = Wrapping(x & 0x8888_8888_8888_8888);
    let y0 = Wrapping(y & 0x1111_1111_1111_1111);
    let y1 = Wrapping(y & 0x2222_2222_2222_2222);
    let y2 = Wrapping(y & 0x4444_4444_4444_4444);
    let y3 = Wrapping(y & 0x8888_8888_8888_8888);

    let mut z0 = ((x0 * y0) ^ (x1 * y3) ^ (x2 * y2) ^ (x3 * y1)).0;
    let mut z1 = ((x0 * y1) ^ (x1 * y0) ^ (x2 * y3) ^ (x3 * y2)).0;
    let mut z2 = ((x0 * y2) ^ (x1 * y1) ^ (x2 * y0) ^ (x3 * y3)).0;
    let mut z3 = ((x0 * y3) ^ (x1 * y2) ^ (x2 * y1) ^ (x3 * y0)).0;

    z0 &= 0x1111_1111_1111_1111;
    z1 &= 0x2222_2222_2222_2222;
    z2 &= 0x4444_4444_4444_4444;
    z3 &= 0x8888_8888_8888_8888;

    z0 | z1 | z2 | z3
}

/// Bit-reverse a `u64` in constant time
#[allow(clippy::manual_rotate)] // Preserve the reviewed upstream bitwise formula.
fn rev64(mut x: u64) -> u64 {
    x = ((x & 0x5555_5555_5555_5555) << 1) | ((x >> 1) & 0x5555_5555_5555_5555);
    x = ((x & 0x3333_3333_3333_3333) << 2) | ((x >> 2) & 0x3333_3333_3333_3333);
    x = ((x & 0x0f0f_0f0f_0f0f_0f0f) << 4) | ((x >> 4) & 0x0f0f_0f0f_0f0f_0f0f);
    x = ((x & 0x00ff_00ff_00ff_00ff) << 8) | ((x >> 8) & 0x00ff_00ff_00ff_00ff);
    x = ((x & 0xffff_0000_ffff) << 16) | ((x >> 16) & 0xffff_0000_ffff);
    (x << 32) | (x >> 32)
}
