#![allow(non_snake_case)]

use aead::{
    consts::{U12, U8},
    AeadInPlace, KeyInit,
};
use aes::{Aes128, Aes192, Aes256};
use hex_literal::hex;
use ocb3::{GenericArray, Ocb3};

// Test vectors from https://www.rfc-editor.org/rfc/rfc7253.html#appendix-A
aead::new_test!(rfc7253_ocb_aes, "rfc7253_ocb_aes", Aes128Ocb3);

fn num2str96(num: usize) -> [u8; 12] {
    let num: u32 = num.try_into().unwrap();
    let mut out = [0u8; 12];
    out[8..12].copy_from_slice(&num.to_be_bytes());
    out
}

/// Test vectors from Page 18 of https://www.rfc-editor.org/rfc/rfc7253.html#appendix-A
macro_rules! rfc7253_wider_variety {
    ($ocb:tt, $keylen:tt, $taglen:expr, $expected:expr) => {
        let mut key_bytes = vec![0u8; $keylen];
        key_bytes[$keylen - 1] = 8 * $taglen; // taglen in bytes

        let key = GenericArray::from_slice(key_bytes.as_slice());
        let ocb = $ocb::new(key);

        let mut ciphertext = Vec::new();

        for i in 0..128 {
            // S = zeros(8i)
            let S = vec![0u8; i];

            // N = num2str(3i+1,96)
            // C = C || OCB-ENCRYPT(K,N,S,S)
            let N = num2str96(3 * i + 1);
            let mut buffer = S.clone();
            let tag = ocb
                .encrypt_in_place_detached(N.as_slice().into(), &S, &mut buffer)
                .unwrap();
            ciphertext.append(&mut buffer);
            ciphertext.append(&mut tag.as_slice().to_vec());

            // N = num2str(3i+2,96)
            // C = C || OCB-ENCRYPT(K,N,<empty string>,S)
            let N = num2str96(3 * i + 2);
            let mut buffer = S.clone();
            let tag = ocb
                .encrypt_in_place_detached(N.as_slice().into(), &[], &mut buffer)
                .unwrap();
            ciphertext.append(&mut buffer);
            ciphertext.append(&mut tag.as_slice().to_vec());

            // N = num2str(3i+3,96)
            // C = C || OCB-ENCRYPT(K,N,S,<empty string>)
            let N = num2str96(3 * i + 3);
            let tag = ocb
                .encrypt_in_place_detached(N.as_slice().into(), &S, &mut [])
                .unwrap();
            ciphertext.append(&mut tag.as_slice().to_vec());
        }
        if $taglen == 16 {
            assert_eq!(ciphertext.len(), 22_400);
        } else if $taglen == 12 {
            assert_eq!(ciphertext.len(), 20_864);
        } else if $taglen == 8 {
            assert_eq!(ciphertext.len(), 19_328);
        } else {
            unreachable!();
        }

        // N = num2str(385,96)
        // Output : OCB-ENCRYPT(K,N,C,<empty string>)
        let N = num2str96(385);
        let tag = ocb
            .encrypt_in_place_detached(N.as_slice().into(), &ciphertext, &mut [])
            .unwrap();

        assert_eq!(tag.as_slice(), hex!($expected))
    };
}

// More types for testing
type Aes192Ocb3 = Ocb3<Aes192, U12>;
type Aes128Ocb3Tag96 = Ocb3<Aes128, U12, U12>;
type Aes192Ocb3Tag96 = Ocb3<Aes192, U12, U12>;
type Aes256Ocb3Tag96 = Ocb3<Aes256, U12, U12>;
type Aes128Ocb3Tag64 = Ocb3<Aes128, U12, U8>;
type Aes192Ocb3Tag64 = Ocb3<Aes192, U12, U8>;
type Aes256Ocb3Tag64 = Ocb3<Aes256, U12, U8>;
type Aes128Ocb3 = Ocb3<aes::Aes128, U12>;
type Aes256Ocb3 = Ocb3<aes::Aes256, U12>;

/// Test vectors from Page 18 of https://www.rfc-editor.org/rfc/rfc7253.html#appendix-A
#[test]
fn rfc7253_more_sample_results() {
    rfc7253_wider_variety!(Aes128Ocb3, 16, 16, "67E944D23256C5E0B6C61FA22FDF1EA2");
    rfc7253_wider_variety!(Aes192Ocb3, 24, 16, "F673F2C3E7174AAE7BAE986CA9F29E17");
    rfc7253_wider_variety!(Aes256Ocb3, 32, 16, "D90EB8E9C977C88B79DD793D7FFA161C");
    rfc7253_wider_variety!(Aes128Ocb3Tag96, 16, 12, "77A3D8E73589158D25D01209");
    rfc7253_wider_variety!(Aes192Ocb3Tag96, 24, 12, "05D56EAD2752C86BE6932C5E");
    rfc7253_wider_variety!(Aes256Ocb3Tag96, 32, 12, "5458359AC23B0CBA9E6330DD");
    rfc7253_wider_variety!(Aes128Ocb3Tag64, 16, 8, "192C9B7BD90BA06A");
    rfc7253_wider_variety!(Aes192Ocb3Tag64, 24, 8, "0066BC6E0EF34E24");
    rfc7253_wider_variety!(Aes256Ocb3Tag64, 32, 8, "7D4EA5D445501CBE");
}
