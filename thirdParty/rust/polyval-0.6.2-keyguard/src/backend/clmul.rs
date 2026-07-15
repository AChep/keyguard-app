//! Intel `CLMUL`-accelerated implementation for modern x86/x86_64 CPUs
//! (i.e. Intel Sandy Bridge-compatible or newer)

#[cfg(target_arch = "x86")]
use core::arch::x86::*;
#[cfg(target_arch = "x86_64")]
use core::arch::x86_64::*;

use universal_hash::{
    consts::{U1, U16},
    crypto_common::{BlockSizeUser, KeySizeUser, ParBlocksSizeUser},
    KeyInit, Reset, UhfBackend,
};

use crate::{Block, Key, Tag};

/// **POLYVAL**: GHASH-like universal hash over GF(2^128).
#[derive(Clone)]
pub struct Polyval {
    h: __m128i,
    y: __m128i,
}

impl KeySizeUser for Polyval {
    type KeySize = U16;
}

impl Polyval {
    /// Initialize POLYVAL with the given `H` field element and initial block
    pub fn new_with_init_block(h: &Key, init_block: u128) -> Self {
        unsafe {
            // `_mm_loadu_si128` performs an unaligned load
            #[allow(clippy::cast_ptr_alignment)]
            Self {
                h: _mm_loadu_si128(h.as_ptr() as *const __m128i),
                y: _mm_loadu_si128(&init_block.to_be_bytes()[..] as *const _ as *const __m128i),
            }
        }
    }
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
        unsafe {
            self.mul(x);
        }
    }
}

impl Polyval {
    /// Return the current tag without consuming this backend.
    pub(crate) fn finalize_tag(&self) -> Tag {
        unsafe { core::mem::transmute(self.y) }
    }
}

impl Polyval {
    #[inline]
    #[target_feature(enable = "pclmulqdq")]
    unsafe fn mul(&mut self, x: &Block) {
        let h = self.h;

        // `_mm_loadu_si128` performs an unaligned load
        #[allow(clippy::cast_ptr_alignment)]
        let x = _mm_loadu_si128(x.as_ptr() as *const __m128i);
        let y = _mm_xor_si128(self.y, x);

        let h0 = h;
        let h1 = _mm_shuffle_epi32(h, 0x0E);
        let h2 = _mm_xor_si128(h0, h1);
        let y0 = y;

        // Multiply values partitioned to 64-bit parts
        let y1 = _mm_shuffle_epi32(y, 0x0E);
        let y2 = _mm_xor_si128(y0, y1);
        let t0 = _mm_clmulepi64_si128(y0, h0, 0x00);
        let t1 = _mm_clmulepi64_si128(y, h, 0x11);
        let t2 = _mm_clmulepi64_si128(y2, h2, 0x00);
        let t2 = _mm_xor_si128(t2, _mm_xor_si128(t0, t1));
        let v0 = t0;
        let v1 = _mm_xor_si128(_mm_shuffle_epi32(t0, 0x0E), t2);
        let v2 = _mm_xor_si128(t1, _mm_shuffle_epi32(t2, 0x0E));
        let v3 = _mm_shuffle_epi32(t1, 0x0E);

        // Polynomial reduction
        let v2 = xor5(
            v2,
            v0,
            _mm_srli_epi64(v0, 1),
            _mm_srli_epi64(v0, 2),
            _mm_srli_epi64(v0, 7),
        );

        let v1 = xor4(
            v1,
            _mm_slli_epi64(v0, 63),
            _mm_slli_epi64(v0, 62),
            _mm_slli_epi64(v0, 57),
        );

        let v3 = xor5(
            v3,
            v1,
            _mm_srli_epi64(v1, 1),
            _mm_srli_epi64(v1, 2),
            _mm_srli_epi64(v1, 7),
        );

        let v2 = xor4(
            v2,
            _mm_slli_epi64(v1, 63),
            _mm_slli_epi64(v1, 62),
            _mm_slli_epi64(v1, 57),
        );

        self.y = _mm_unpacklo_epi64(v2, v3);
    }
}

impl Reset for Polyval {
    fn reset(&mut self) {
        unsafe {
            self.y = _mm_setzero_si128();
        }
    }
}

#[cfg(feature = "zeroize")]
impl Drop for Polyval {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        self.h.zeroize();
        self.y.zeroize();
    }
}

#[inline(always)]
unsafe fn xor4(e1: __m128i, e2: __m128i, e3: __m128i, e4: __m128i) -> __m128i {
    _mm_xor_si128(_mm_xor_si128(e1, e2), _mm_xor_si128(e3, e4))
}

#[inline(always)]
unsafe fn xor5(e1: __m128i, e2: __m128i, e3: __m128i, e4: __m128i, e5: __m128i) -> __m128i {
    _mm_xor_si128(
        e1,
        _mm_xor_si128(_mm_xor_si128(e2, e3), _mm_xor_si128(e4, e5)),
    )
}
