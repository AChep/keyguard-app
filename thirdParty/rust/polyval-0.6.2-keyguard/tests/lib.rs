use hex_literal::hex;
use polyval::{
    universal_hash::{KeyInit, UniversalHash},
    Polyval, BLOCK_SIZE,
};

//
// Test vectors for POLYVAL from RFC 8452 Appendix A
// <https://tools.ietf.org/html/rfc8452#appendix-A>
//

const H: [u8; BLOCK_SIZE] = hex!("25629347589242761d31f826ba4b757b");
const X_1: [u8; BLOCK_SIZE] = hex!("4f4f95668c83dfb6401762bb2d01a262");
const X_2: [u8; BLOCK_SIZE] = hex!("d1a24ddd2721d006bbe45f20d3c9f362");

/// POLYVAL(H, X_1, X_2)
const POLYVAL_RESULT: [u8; BLOCK_SIZE] = hex!("f7a3b47b846119fae5b7866cf5e5b77e");

#[test]
fn polyval_test_vector() {
    let mut poly = Polyval::new(&H.into());
    poly.update(&[X_1.into(), X_2.into()]);

    let result = poly.finalize();
    assert_eq!(&POLYVAL_RESULT[..], result.as_slice());
}

#[cfg(feature = "zeroize")]
#[test]
fn finalize_and_drop_preserve_the_rfc8452_result() {
    assert!(core::mem::needs_drop::<Polyval>());

    let mut poly = Polyval::new(&H.into());
    poly.update(&[X_1.into(), X_2.into()]);
    let clone = poly.clone();

    drop(poly);
    assert_eq!(&POLYVAL_RESULT[..], clone.finalize().as_slice());
}
