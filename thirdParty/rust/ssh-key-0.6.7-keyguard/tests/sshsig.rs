//! `sshsig` signature tests.

#![cfg(feature = "alloc")]

use hex_literal::hex;
use ssh_key::{Algorithm, HashAlg, LineEnding, PublicKey, SshSig};

#[cfg(any(
    feature = "dsa",
    feature = "ed25519",
    feature = "p256",
    feature = "rsa"
))]
use {encoding::Decode, signature::Verifier, ssh_key::PrivateKey, ssh_key::Signature};

#[cfg(feature = "ed25519")]
use ssh_key::Error;

/// DSA OpenSSH-formatted private key.
#[cfg(feature = "dsa")]
const DSA_PRIVATE_KEY: &str = include_str!("examples/id_dsa_1024");

/// DSA OpenSSH-formatted public key.
#[cfg(feature = "dsa")]
const DSA_PUBLIC_KEY: &str = include_str!("examples/id_dsa_1024.pub");

/// ECDSA/P-256 OpenSSH-formatted private key.
#[cfg(feature = "p256")]
const ECDSA_P256_PRIVATE_KEY: &str = include_str!("examples/id_ecdsa_p256");

/// ECDSA/P-256 OpenSSH-formatted public key.
#[cfg(feature = "p256")]
const ECDSA_P256_PUBLIC_KEY: &str = include_str!("examples/id_ecdsa_p256.pub");

/// Ed25519 OpenSSH-formatted private key.
#[cfg(feature = "ed25519")]
const ED25519_PRIVATE_KEY: &str = include_str!("examples/id_ed25519");

/// Ed25519 OpenSSH-formatted public key.
const ED25519_PUBLIC_KEY: &str = include_str!("examples/id_ed25519.pub");

/// `sshsig`-encoded signature.
const ED25519_SIGNATURE: &str = include_str!("examples/sshsig_ed25519");

/// Bytes of the raw Ed25519 signature.
const ED25519_SIGNATURE_BYTES: [u8; 64] = hex!(
    "4f11abfeb4c18d9e8c7832eccceeb947c9505a8c29fc074900ca2396c0f2a9ac"
    "db06de2e97fafa33fd60928a4fc5a30630aa18020015094af457dc011154150f"
);

/// SkEd25519 OpenSSH-formatted public key.
const SK_ED25519_PUBLIC_KEY: &str = include_str!("examples/id_sk_ed25519_2.pub");

#[cfg(feature = "p256")]
const SK_ECDSA_P256_PUBLIC_KEY: &str = include_str!("examples/id_sk_ecdsa_p256_2.pub");

/// `sshsig`-encoded signature.
const SK_ED25519_SIGNATURE: &str = include_str!("examples/sshsig_sk_ed25519");

/// Bytes of the raw SkEd25519 signature.
const SK_ED25519_SIGNATURE_BYTES: [u8; 69] = hex!(
    "2f5670b6f93465d17423878a74084bf331767031ed240c627c8eb79ab8fa1b93"
    "5a1fd993f52f5a13fec1797f8a434f943a6096246aea8dd5c8aa922cba3d9506"
    "0100000009"
);

/// RSA OpenSSH-formatted private key.
#[cfg(feature = "rsa")]
const RSA_PRIVATE_KEY: &str = include_str!("examples/id_rsa_3072");

/// RSA OpenSSH-formatted public key.
#[cfg(feature = "rsa")]
const RSA_PUBLIC_KEY: &str = include_str!("examples/id_rsa_3072.pub");

/// Example message to be signed/verified.
#[allow(dead_code)]
const MSG_EXAMPLE: &[u8] = b"testing";

/// Example domain/namespace used for the message.
const NAMESPACE_EXAMPLE: &str = "example";

#[cfg(feature = "p256")]
const SK_ECDSA_SIGNATURE_OPENSSH_WIRE: [u8; 120] = hex!(
    "00000022736b2d65636473612d736861322d6e69737470323536406f70656e73"
    "73682e636f6d00000049000000201b35a1c6469a43a3d09d490d6ff8ca1bc248"
    "6a2edeb8aa7d119e4c70b9c1811000000021009724a2a4449a90357485ed1df0"
    "161274d20083342b02756794bc3f068fcdc15e01000000ec"
);

/// An ssh-agent signature response signing MSG_EXAMPLE with DSA_PRIVATE_KEY
#[cfg(feature = "dsa")]
const DSA_SIGNATURE_OPENSSH_WIRE: [u8; 55] = hex!(
    "000000077373682d647373000000282d0c9613d9745c6088ae4d9e8dbf35a557"
    "7bd0e6796acccb22ab4809d569e86ec619510ec48b6950"
);

/// An ssh-agent signature response signing MSG_EXAMPLE with RSA_PRIVATE_KEY
#[cfg(feature = "rsa")]
const RSA_SIGNATURE_OPENSSH_WIRE: [u8; 404] = hex!(
    "0000000c7273612d736861322d353132000001804ebc4f9fe2bfa1badd9f6b80"
    "df806e6f93a31d4af7b15637d4b15e0ac180271467d5ab7a864fc48dedabf6dd"
    "8f318a9f36824f84cba1353f453c23d6a60431aa9cc243c849cc33c9e358418b"
    "9fe833bb8985ac35d6b72a7960097bff5e02263c4076f31eb0e64bf2a02fc85f"
    "d75a569e13e167e29543e101e1f84254e60f0841f7843cf6e461a1ce06d1f590"
    "c9446358ef04dfa25ec98b2c14393c9267684c6a568425bb6245d0a0dd44f9fd"
    "bf352cb70eba53c6b2aaff8890a22d8769fd253b3d4c6a19237d2b7f6ae08557"
    "a7e7cca3e78bef33f3f8a86adbce79713221911c9647c126d5511b0f5c1f9133"
    "0a6015f3bf9a27d5afea84a499e9e4a1c058355c09d2ce5ff441638596b4447c"
    "717db04b6365dff0d6f9a0123e9304b033c404b2f4709446c71adc0acc3c042b"
    "f221ae7446f2371bd40937be31da77c04027c3be1bbd4ec8ac77cd5d453fbca1"
    "c9805d54f4b8348549bf480892cc6430ba13f9483361632b82ae54829bdfa435"
    "4d7ac8daa4f05b03039d140ff4fb88f5e5499ee5"
);

/// An ssh-agent signature response signing MSG_EXAMPLE with ECDSA_P256_PRIVATE_KEY
#[cfg(feature = "p256")]
const ECDSA_P256_SIGNATURE_OPENSSH_WIRE: [u8; 100] = hex!(
    "0000001365636473612d736861322d6e69737470323536000000490000002100"
    "f8291cf8859e0776394431c2a20d9efe80938844decfb7f29617475bc739c832"
    "0000002038228f9c5cde47a7daf510f423ab6b8457fff1907c13af4cabf27a8f"
    "3df7d99c"
);

/// An ssh-agent signature response signing MSG_EXAMPLE with ED25519_PRIVATE_KEY
#[cfg(feature = "ed25519")]
const ED25519_SIGNATURE_OPENSSH_WIRE: [u8; 83] = hex!(
    "0000000b7373682d6564323535313900000040e4d03342608fdb46fb6ab5b0aa"
    "07dfecae8ee2a7ce8514065f580aec85c325795e9f65415d7554ee7929f43b5a"
    "9fc9f13874d8f2e2158c22dfd66d3ab92ede0d"
);

#[test]
fn decode_ed25519() {
    let sshsig = ED25519_SIGNATURE.parse::<SshSig>().unwrap();
    let public_key = ED25519_PUBLIC_KEY.parse::<PublicKey>().unwrap();

    assert_eq!(sshsig.algorithm(), Algorithm::Ed25519);
    assert_eq!(sshsig.version(), 1);
    assert_eq!(sshsig.public_key(), public_key.key_data());
    assert_eq!(sshsig.namespace(), NAMESPACE_EXAMPLE);
    assert_eq!(sshsig.reserved(), &[]);
    assert_eq!(sshsig.hash_alg(), HashAlg::Sha512);
    assert_eq!(sshsig.signature_bytes(), ED25519_SIGNATURE_BYTES);
}

#[test]
fn encode_ed25519() {
    let sshsig = ED25519_SIGNATURE.parse::<SshSig>().unwrap();
    let sshsig_pem = sshsig.to_pem(LineEnding::LF).unwrap();
    assert_eq!(&sshsig_pem, ED25519_SIGNATURE);
}

#[test]
fn decode_sk_ed25519() {
    let sshsig = SK_ED25519_SIGNATURE.parse::<SshSig>().unwrap();
    let public_key = SK_ED25519_PUBLIC_KEY.parse::<PublicKey>().unwrap();

    assert_eq!(sshsig.algorithm(), Algorithm::SkEd25519);
    assert_eq!(sshsig.version(), 1);
    assert_eq!(sshsig.public_key(), public_key.key_data());
    assert_eq!(sshsig.namespace(), NAMESPACE_EXAMPLE);
    assert_eq!(sshsig.reserved(), &[]);
    assert_eq!(sshsig.hash_alg(), HashAlg::Sha512);
    assert_eq!(sshsig.signature_bytes(), SK_ED25519_SIGNATURE_BYTES);
}

#[test]
fn encode_sk_ed25519() {
    let sshsig = SK_ED25519_SIGNATURE.parse::<SshSig>().unwrap();
    let sshsig_pem = sshsig.to_pem(LineEnding::LF).unwrap();
    assert_eq!(&sshsig_pem, SK_ED25519_SIGNATURE);
}

#[test]
#[cfg(feature = "dsa")]
fn sign_dsa() {
    let signing_key = PrivateKey::from_openssh(DSA_PRIVATE_KEY).unwrap();
    let verifying_key = DSA_PUBLIC_KEY.parse::<PublicKey>().unwrap();

    let signature = signing_key
        .sign(NAMESPACE_EXAMPLE, HashAlg::Sha512, MSG_EXAMPLE)
        .unwrap();

    assert_eq!(
        verifying_key.verify(NAMESPACE_EXAMPLE, MSG_EXAMPLE, &signature),
        Ok(())
    );
}

#[test]
#[cfg(feature = "p256")]
fn verify_sk_ecdsa_openssh_wire_format() {
    let signature = Signature::decode(&mut SK_ECDSA_SIGNATURE_OPENSSH_WIRE.as_ref()).unwrap();
    let verifying_key = SK_ECDSA_P256_PUBLIC_KEY.parse::<PublicKey>().unwrap();
    verifying_key
        .key_data()
        .verify(MSG_EXAMPLE, &signature)
        .expect("failed to validate valid signature");

    verifying_key
        .key_data()
        .verify(b"Bogus!", &signature)
        .expect_err("good signature from bogus data");
}

#[test]
#[cfg(feature = "dsa")]
fn verify_dsa_openssh_wire_format() {
    let signature = Signature::decode(&mut DSA_SIGNATURE_OPENSSH_WIRE.as_ref()).unwrap();
    let verifying_key = DSA_PUBLIC_KEY.parse::<PublicKey>().unwrap();
    verifying_key
        .key_data()
        .verify(MSG_EXAMPLE, &signature)
        .unwrap();
}

#[test]
#[cfg(feature = "p256")]
fn sign_ecdsa_p256() {
    let signing_key = PrivateKey::from_openssh(ECDSA_P256_PRIVATE_KEY).unwrap();
    let verifying_key = ECDSA_P256_PUBLIC_KEY.parse::<PublicKey>().unwrap();

    let signature = signing_key
        .sign(NAMESPACE_EXAMPLE, HashAlg::Sha512, MSG_EXAMPLE)
        .unwrap();

    assert_eq!(
        verifying_key.verify(NAMESPACE_EXAMPLE, MSG_EXAMPLE, &signature),
        Ok(())
    );
}

#[test]
#[cfg(feature = "p256")]
fn verify_ecdsa_p256_openssh_wire_format() {
    let signature = Signature::decode(&mut ECDSA_P256_SIGNATURE_OPENSSH_WIRE.as_ref()).unwrap();
    let verifying_key = ECDSA_P256_PUBLIC_KEY.parse::<PublicKey>().unwrap();
    verifying_key
        .key_data()
        .verify(MSG_EXAMPLE, &signature)
        .unwrap();
}

#[test]
#[cfg(feature = "ed25519")]
fn sign_ed25519() {
    let signing_key = PrivateKey::from_openssh(ED25519_PRIVATE_KEY).unwrap();
    let signature = signing_key
        .sign(NAMESPACE_EXAMPLE, HashAlg::Sha512, MSG_EXAMPLE)
        .unwrap();

    assert_eq!(signature, ED25519_SIGNATURE.parse::<SshSig>().unwrap());
}

#[test]
#[cfg(feature = "ed25519")]
fn verify_ed25519_openssh_wire_format() {
    let signature = Signature::decode(&mut ED25519_SIGNATURE_OPENSSH_WIRE.as_ref()).unwrap();
    let verifying_key = ED25519_PUBLIC_KEY.parse::<PublicKey>().unwrap();
    verifying_key
        .key_data()
        .verify(MSG_EXAMPLE, &signature)
        .unwrap();
}

#[test]
#[cfg(feature = "rsa")]
fn sign_rsa() {
    let signing_key = PrivateKey::from_openssh(RSA_PRIVATE_KEY).unwrap();
    let verifying_key = RSA_PUBLIC_KEY.parse::<PublicKey>().unwrap();

    let signature = signing_key
        .sign(NAMESPACE_EXAMPLE, HashAlg::Sha512, MSG_EXAMPLE)
        .unwrap();

    assert_eq!(
        verifying_key.verify(NAMESPACE_EXAMPLE, MSG_EXAMPLE, &signature),
        Ok(())
    );
}

#[test]
#[cfg(feature = "rsa")]
fn verify_rsa_openssh_wire_format() {
    let signature = Signature::decode(&mut RSA_SIGNATURE_OPENSSH_WIRE.as_ref()).unwrap();
    let verifying_key = RSA_PUBLIC_KEY.parse::<PublicKey>().unwrap();
    verifying_key
        .key_data()
        .verify(MSG_EXAMPLE, &signature)
        .unwrap();
}

#[test]
#[cfg(feature = "ed25519")]
fn verify_ed25519() {
    let verifying_key = ED25519_PUBLIC_KEY.parse::<PublicKey>().unwrap();
    let signature = ED25519_SIGNATURE.parse::<SshSig>().unwrap();

    // valid
    assert_eq!(
        verifying_key.verify(NAMESPACE_EXAMPLE, MSG_EXAMPLE, &signature),
        Ok(())
    );

    // bad namespace
    assert_eq!(
        verifying_key.verify("bogus namespace", MSG_EXAMPLE, &signature),
        Err(Error::Namespace)
    );

    // invalid message
    assert_eq!(
        verifying_key.verify(NAMESPACE_EXAMPLE, b"bogus!", &signature),
        Err(Error::Crypto)
    );
}

#[test]
#[cfg(feature = "ed25519")]
fn verify_sk_ed25519() {
    let verifying_key = SK_ED25519_PUBLIC_KEY.parse::<PublicKey>().unwrap();
    let signature = SK_ED25519_SIGNATURE.parse::<SshSig>().unwrap();

    // valid
    assert_eq!(
        verifying_key.verify(NAMESPACE_EXAMPLE, MSG_EXAMPLE, &signature),
        Ok(())
    );

    // bad namespace
    assert_eq!(
        verifying_key.verify("bogus namespace", MSG_EXAMPLE, &signature),
        Err(Error::Namespace)
    );

    // invalid message
    assert_eq!(
        verifying_key.verify(NAMESPACE_EXAMPLE, b"bogus!", &signature),
        Err(Error::Crypto)
    );
}
