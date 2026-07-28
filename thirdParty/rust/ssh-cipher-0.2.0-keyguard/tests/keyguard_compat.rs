use hex_literal::hex;
use ssh_cipher::{Cipher, Tag};

const PLAINTEXT: [u8; 32] =
    hex!("00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f");

fn assert_cipher_bytes(
    cipher: Cipher,
    key: &[u8],
    iv: &[u8],
    expected_ciphertext: [u8; 32],
    expected_tag: Tag,
) {
    let mut ciphertext = PLAINTEXT;
    let tag = cipher
        .encrypt(key, iv, &mut ciphertext)
        .expect("test vector parameters are valid")
        .expect("test vector is authenticated");

    assert_eq!(ciphertext, expected_ciphertext);
    assert_eq!(tag, expected_tag);

    let mut rejected = expected_ciphertext;
    let mut invalid_tag = expected_tag;
    invalid_tag[0] ^= 1;
    assert!(cipher
        .decrypt(key, iv, &mut rejected, Some(invalid_tag))
        .is_err());

    cipher
        .decrypt(key, iv, &mut ciphertext, Some(tag))
        .expect("generated authentication tag verifies");
    assert_eq!(ciphertext, PLAINTEXT);
}

#[test]
fn aes128_gcm_bytes_are_stable() {
    assert_cipher_bytes(
        Cipher::Aes128Gcm,
        &hex!("000102030405060708090a0b0c0d0e0f"),
        &hex!("f0f1f2f3f4f5f6f7f8f9fafb"),
        hex!("110bc4407e8d0303bb65950b1bdd438855ae1177bfae81ca40186f80b85ce6ad"),
        hex!("32b88026c2359b909e37801b0f13d122"),
    );
}

#[test]
fn aes256_gcm_bytes_are_stable() {
    assert_cipher_bytes(
        Cipher::Aes256Gcm,
        &hex!("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
        &hex!("f0f1f2f3f4f5f6f7f8f9fafb"),
        hex!("69176133386fb403176e5d2142978c4900c06b816c62391fceff896b85de2c08"),
        hex!("4ce6bc9c3c36d322c86f8befccca97b4"),
    );
}

#[test]
fn chacha20_poly1305_bytes_are_stable() {
    assert_cipher_bytes(
        Cipher::ChaCha20Poly1305,
        &hex!(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
        ),
        &[],
        hex!("18a96002e9b3c0a69bf8f6da639ea0d8e890c1b6b5c82ddb745146d9f6d88b53"),
        hex!("b315ffeb5364d7c96e11cf5af467d8c9"),
    );
}
