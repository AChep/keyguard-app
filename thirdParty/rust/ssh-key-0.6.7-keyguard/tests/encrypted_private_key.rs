//! Encrypted SSH private key tests.

#![cfg(feature = "alloc")]

use hex_literal::hex;
use ssh_key::{Algorithm, Cipher, Kdf, KdfAlg, PrivateKey};

/// Unencrypted Ed25519 OpenSSH-formatted private key.
#[cfg(feature = "encryption")]
const OPENSSH_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519");

/// AES128-CBC encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
#[cfg(feature = "encryption")]
const OPENSSH_AES128_CBC_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.aes128-cbc.enc");

/// AES192-CBC encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
#[cfg(feature = "encryption")]
const OPENSSH_AES192_CBC_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.aes192-cbc.enc");

/// AES256-CBC encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
#[cfg(feature = "encryption")]
const OPENSSH_AES256_CBC_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.aes256-cbc.enc");

/// AES128-CTR encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
#[cfg(feature = "encryption")]
const OPENSSH_AES128_CTR_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.aes128-ctr.enc");

/// AES192-CTR encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
#[cfg(feature = "encryption")]
const OPENSSH_AES192_CTR_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.aes192-ctr.enc");

/// AES256-CTR encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
const OPENSSH_AES256_CTR_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.aes256-ctr.enc");

/// AES256-GCM encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
#[cfg(feature = "encryption")]
const OPENSSH_AES128_GCM_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.aes128-gcm.enc");

/// AES256-GCM encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
const OPENSSH_AES256_GCM_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.aes256-gcm.enc");

/// ChaCha20-Poly1305 encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
const OPENSSH_CHACHA20_POLY1305_ED25519_EXAMPLE: &str =
    include_str!("examples/id_ed25519.chacha20-poly1305.enc");

/// TripleDES-CBC encrypted Ed25519 OpenSSH-formatted private key.
///
/// Plaintext is `OPENSSH_ED25519_EXAMPLE`.
const OPENSSH_3DES_CBC_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.3des-cbc.enc");

/// Bad password; don't actually use outside tests!
#[cfg(feature = "encryption")]
const PASSWORD: &[u8] = b"hunter42";

#[test]
fn decode_openssh_aes256_ctr() {
    let key = PrivateKey::from_openssh(OPENSSH_AES256_CTR_ED25519_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Ed25519, key.algorithm());
    assert_eq!(Cipher::Aes256Ctr, key.cipher());
    assert_eq!(KdfAlg::Bcrypt, key.kdf().algorithm());

    match key.kdf() {
        Kdf::Bcrypt { salt, rounds } => {
            assert_eq!(salt, &hex!("4a1fdeae8d6ba607afd69d334f8d379a"));
            assert_eq!(*rounds, 16);
        }
        other => panic!("unexpected KDF algorithm: {:?}", other),
    }

    assert_eq!(
        &hex!("b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62"),
        key.public_key().key_data().ed25519().unwrap().as_ref(),
    );
}

#[test]
fn decode_openssh_aes256_gcm() {
    let key = PrivateKey::from_openssh(OPENSSH_AES256_GCM_ED25519_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Ed25519, key.algorithm());
    assert_eq!(Cipher::Aes256Gcm, key.cipher());
    assert_eq!(KdfAlg::Bcrypt, key.kdf().algorithm());

    match key.kdf() {
        Kdf::Bcrypt { salt, rounds } => {
            assert_eq!(salt, &hex!("11bdc133ef64644115b176917e47cbaf"));
            assert_eq!(*rounds, 16);
        }
        other => panic!("unexpected KDF algorithm: {:?}", other),
    }

    assert_eq!(
        &hex!("b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62"),
        key.public_key().key_data().ed25519().unwrap().as_ref(),
    );
}

#[test]
fn decode_openssh_chacha20_poly1305() {
    let key = PrivateKey::from_openssh(OPENSSH_CHACHA20_POLY1305_ED25519_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Ed25519, key.algorithm());
    assert_eq!(Cipher::ChaCha20Poly1305, key.cipher());
    assert_eq!(KdfAlg::Bcrypt, key.kdf().algorithm());

    match key.kdf() {
        Kdf::Bcrypt { salt, rounds } => {
            assert_eq!(salt, &hex!("f651ca3efb15904d05c216a5041ea89a"));
            assert_eq!(*rounds, 16);
        }
        other => panic!("unexpected KDF algorithm: {:?}", other),
    }

    assert_eq!(
        &hex!("b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62"),
        key.public_key().key_data().ed25519().unwrap().as_ref(),
    );
}

#[test]
fn decode_openssh_3des_cbc() {
    let key = PrivateKey::from_openssh(OPENSSH_3DES_CBC_ED25519_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Ed25519, key.algorithm());
    assert_eq!(Cipher::TDesCbc, key.cipher());
    assert_eq!(KdfAlg::Bcrypt, key.kdf().algorithm());

    match key.kdf() {
        Kdf::Bcrypt { salt, rounds } => {
            assert_eq!(salt, &hex!("1afcebea3c598c277e7edc2b78db1e94"));
            assert_eq!(*rounds, 16);
        }
        other => panic!("unexpected KDF algorithm: {:?}", other),
    }

    assert_eq!(
        &hex!("b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62"),
        key.public_key().key_data().ed25519().unwrap().as_ref(),
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_aes128_ctr() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_AES128_CTR_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::Aes128Ctr, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_aes192_ctr() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_AES192_CTR_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::Aes192Ctr, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_aes256_ctr() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_AES256_CTR_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::Aes256Ctr, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_aes128_cbc() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_AES128_CBC_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::Aes128Cbc, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_aes192_cbc() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_AES192_CBC_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::Aes192Cbc, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_aes256_cbc() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_AES256_CBC_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::Aes256Cbc, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_aes128_gcm() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_AES128_GCM_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::Aes128Gcm, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_aes256_gcm() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_AES256_GCM_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::Aes256Gcm, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "encryption")]
#[test]
fn decrypt_openssh_chacha20_poly1305() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_CHACHA20_POLY1305_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::ChaCha20Poly1305, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[cfg(feature = "tdes")]
#[test]
fn decrypt_openssh_3des() {
    let key_enc = PrivateKey::from_openssh(OPENSSH_3DES_CBC_ED25519_EXAMPLE).unwrap();
    assert_eq!(Cipher::TDesCbc, key_enc.cipher());
    let key_dec = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(
        PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap(),
        key_dec
    );
}

#[test]
fn encode_openssh_aes256_ctr() {
    let key = PrivateKey::from_openssh(OPENSSH_AES256_CTR_ED25519_EXAMPLE).unwrap();
    assert_eq!(
        OPENSSH_AES256_CTR_ED25519_EXAMPLE.trim_end(),
        key.to_openssh(Default::default()).unwrap().trim_end()
    );
}

#[test]
fn encode_openssh_aes256_gcm() {
    let key = PrivateKey::from_openssh(OPENSSH_AES256_GCM_ED25519_EXAMPLE).unwrap();
    assert_eq!(
        OPENSSH_AES256_GCM_ED25519_EXAMPLE.trim_end(),
        key.to_openssh(Default::default()).unwrap().trim_end()
    );
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_aes128_cbc() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::Aes128Cbc, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_aes192_cbc() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::Aes192Cbc, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_aes256_cbc() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::Aes256Cbc, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_aes128_ctr() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::Aes128Ctr, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_aes192_ctr() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::Aes192Ctr, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_aes256_ctr() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    let key_enc = key_dec.encrypt(&mut OsRng, PASSWORD).unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_aes128_gcm() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();

    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::Aes128Gcm, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_aes256_gcm() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();

    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::Aes256Gcm, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "encryption", feature = "getrandom"))]
#[test]
fn encrypt_openssh_chacha20_poly1305() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();

    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::ChaCha20Poly1305, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}

#[cfg(all(feature = "tdes", feature = "getrandom"))]
#[test]
fn encrypt_openssh_3des() {
    use rand_core::OsRng;

    let key_dec = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();

    let key_enc = key_dec
        .encrypt_with_cipher(&mut OsRng, Cipher::TDesCbc, PASSWORD)
        .unwrap();

    // Ensure encrypted key round trips through encoder/decoder
    let key_enc_str = key_enc.to_openssh(Default::default()).unwrap();
    let key_enc2 = PrivateKey::from_openssh(&*key_enc_str).unwrap();
    assert_eq!(key_enc, key_enc2);

    // Ensure decrypted key matches the original
    let key_dec2 = key_enc.decrypt(PASSWORD).unwrap();
    assert_eq!(key_dec, key_dec2);
}
