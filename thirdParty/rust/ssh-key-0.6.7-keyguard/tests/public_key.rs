//! SSH public key tests.

use hex_literal::hex;
#[cfg(feature = "alloc")]
use ssh_key::public::{Ed25519PublicKey, SkEd25519};
use ssh_key::{Algorithm, PublicKey};
use std::collections::HashSet;
#[cfg(all(feature = "ecdsa", feature = "alloc"))]
use {sec1::consts::U32, ssh_key::public::SkEcdsaSha2NistP256};

#[cfg(feature = "ecdsa")]
use ssh_key::EcdsaCurve;

#[cfg(feature = "std")]
use std::{fs, path::PathBuf};

/// DSA OpenSSH-formatted public key
#[cfg(feature = "alloc")]
const OPENSSH_DSA_EXAMPLE: &str = include_str!("examples/id_dsa_1024.pub");

/// ECDSA/NIST P-256 OpenSSH-formatted public key
#[cfg(feature = "ecdsa")]
const OPENSSH_ECDSA_P256_EXAMPLE: &str = include_str!("examples/id_ecdsa_p256.pub");

/// ECDSA/NIST P-384 OpenSSH-formatted public key
#[cfg(feature = "ecdsa")]
const OPENSSH_ECDSA_P384_EXAMPLE: &str = include_str!("examples/id_ecdsa_p384.pub");

/// ECDSA/NIST P-521 OpenSSH-formatted public key
#[cfg(feature = "ecdsa")]
const OPENSSH_ECDSA_P521_EXAMPLE: &str = include_str!("examples/id_ecdsa_p521.pub");

/// Ed25519 OpenSSH-formatted public key
const OPENSSH_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519.pub");

/// RSA (3072-bit) OpenSSH-formatted public key
#[cfg(feature = "alloc")]
const OPENSSH_RSA_3072_EXAMPLE: &str = include_str!("examples/id_rsa_3072.pub");

/// RSA (4096-bit) OpenSSH-formatted public key
#[cfg(feature = "alloc")]
const OPENSSH_RSA_4096_EXAMPLE: &str = include_str!("examples/id_rsa_4096.pub");

/// Security Key (FIDO/U2F) ECDSA/NIST P-256 OpenSSH-formatted public key
#[cfg(feature = "ecdsa")]
const OPENSSH_SK_ECDSA_P256_EXAMPLE: &str = include_str!("examples/id_sk_ecdsa_p256.pub");

/// Security Key (FIDO/U2F) Ed25519 OpenSSH-formatted public key
const OPENSSH_SK_ED25519_EXAMPLE: &str = include_str!("examples/id_sk_ed25519.pub");

/// OpenSSH-formatted public key with a custom algorithm name
#[cfg(feature = "alloc")]
const OPENSSH_OPAQUE_EXAMPLE: &str = include_str!("examples/id_opaque.pub");

/// Get a path into the `tests/scratch` directory.
#[cfg(feature = "std")]
pub fn scratch_path(filename: &str) -> PathBuf {
    PathBuf::from(&format!("tests/scratch/{}.pub", filename))
}

#[cfg(feature = "alloc")]
#[test]
fn decode_dsa_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_DSA_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Dsa, key.key_data().algorithm());

    let dsa_key = key.key_data().dsa().unwrap();
    assert_eq!(
        &hex!(
            "00dc3d89250ed9462114cb2c8d4816e3a511aaff1b06b0e01de17c1cb04e581bcab97176471d89fd7ca1817
             e3c48e2ccbafd2170f69e8e5c8b6ab69b9c5f45d95e1d9293e965227eee5b879b1123371c21b1db60f14b5e
             5c05a4782ceb43a32f449647703063621e7a286bec95b16726c18b5e52383d00b297a6b03489b06068a5"
        ),
        dsa_key.p.as_bytes(),
    );
    assert_eq!(
        &hex!("00891815378597fe42d3fd261fe76df365845bbb87"),
        dsa_key.q.as_bytes(),
    );
    assert_eq!(
        &hex!(
            "4739b3908a8415466dc7b156fb98ecb71552a170ba0b3b7aa81bd81391de0a7ae7a1b45002dfeadc9225fbc
             520a713fe4104a74bed53fd5915da736365afd3f09777bbccfbadf7ac2b087b7f4d95fabe47d72a46e95088
             f9cd2a9fbf236b58a6982647f3c00430ad7352d47a25ebbe9477f0c3127da86ad7448644b76de5875c"
        ),
        dsa_key.g.as_bytes(),
    );
    assert_eq!(
        &hex!(
            "6042a6b3fd861344cb21ccccd8719e25aa0be0980e79cbabf4877f5ef071f6039770352eac3d4c368f29daf
             a57b475c78d44989f16577527e598334be6aae4abd750c36af80489d392697c1f32f3cf3c9a8b99bcddb53d
             7a37e1a28fd53d4934131cf41c437c6734d1e04004adcd925b84b3956c30c3a3904eecb31400b0df48"
        ),
        dsa_key.y.as_bytes(),
    );

    assert_eq!("user@example.com", key.comment());
    assert_eq!(
        "SHA256:Nh0Me49Zh9fDw/VYUfq43IJmI1T+XrjiYONPND8GzaM",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_ecdsa_p256_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_ECDSA_P256_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP256
        },
        key.key_data().algorithm(),
    );

    let ecdsa_key = key.key_data().ecdsa().unwrap();
    assert_eq!(EcdsaCurve::NistP256, ecdsa_key.curve());
    assert_eq!(
        &hex!(
            "047c1fd8730ce53457be8d924098ec3648830f92aa8a2363ac656fdd4521fa6313e511f1891b4e9e5aaf8e1
             42d06ad15a66a4257f3f051d84e8a0e2f91ba807047"
        ),
        ecdsa_key.as_ref(),
    );

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());

    assert_eq!(
        "SHA256:JQ6FV0rf7qqJHZqIj4zNH8eV0oB8KLKh9Pph3FTD98g",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_ecdsa_p384_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_ECDSA_P384_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP384
        },
        key.key_data().algorithm(),
    );

    let ecdsa_key = key.key_data().ecdsa().unwrap();
    assert_eq!(EcdsaCurve::NistP384, ecdsa_key.curve());
    assert_eq!(
        &hex!(
            "042e6e82dc5407f104a11117c7c05b1993c3ceb3db25fae68ba169502a4ff9395d9ad36b543e8014ff15d70
             8e21f09f585aa6dfad575b79b943418b86198d9bcd9b07fff9399b15d43d34efaeb2e56b7b33cff880b242b
             3e0b58af96c75841ec41"
        ),
        ecdsa_key.as_ref(),
    );

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());

    assert_eq!(
        "SHA256:nkGE8oV7pHvOiPKHtQRs67WUPiVLRxbNu//gV/k4Vjw",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_ecdsa_p521_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_ECDSA_P521_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP521
        },
        key.key_data().algorithm(),
    );

    let ecdsa_key = key.key_data().ecdsa().unwrap();
    assert_eq!(ecdsa_key.curve(), EcdsaCurve::NistP521);
    assert_eq!(
        &hex!(
            "04016136934f192b23d961fbf44c8184166002cea2c7d18b20ad018d046ef068d3e8250fd4e9f17ca6693a8
             554c3269a6d9f5762a2f9a2cb8797d4b201de421d3dcc580103cb947a858bb7783df863f82951d96f91a792
             5d7e2baad26e47e3f2fa5b07c8272848a4423b750d7ad2b8b692d66ddecaec5385086b1fd1b682ca291c88d
             63762"
        ),
        ecdsa_key.as_ref(),
    );

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());

    assert_eq!(
        "SHA256:l3AUUMK6Q2BbuiqvMx2fs97f8LUYq7sWCAx7q5m3S6M",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[test]
fn decode_ed25519_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();

    assert_eq!(Algorithm::Ed25519, key.key_data().algorithm());
    assert_eq!(
        &hex!("b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62"),
        key.key_data().ed25519().unwrap().as_ref(),
    );

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());

    assert_eq!(
        "SHA256:UCUiLr7Pjs9wFFJMDByLgc3NrtdU344OgUM45wZPcIQ",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(feature = "alloc")]
#[test]
fn decode_rsa_3072_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_RSA_3072_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Rsa { hash: None }, key.key_data().algorithm());

    let rsa_key = key.key_data().rsa().unwrap();
    assert_eq!(&hex!("010001"), rsa_key.e.as_bytes());
    assert_eq!(
        &hex!(
            "00a68e478c9bc93726436b7f5e9e6f9a46e1b73bec1e8cb7754de2c6a5b6c455f2f012a7259afcf94181d69
             e95d39a349e4d2b482a5372b28943731db75c73ce7bd9eec85010c94bfae56960118922f86a8b3655b357d2
             4e7a679cd8a7d9bf6eae66f7f9a56fe3d090d0632218a682960d8aad93c01898780ead2dbefd70fb4703471
             7e412e4fdae685292ec891e2423f7fe43df2f54329ab0a5d7561e582e42e86ebaee0c1e9eaf603d7ce70850
             5d0ee090912e1fc3735eb5804ddf42b6133107a76e9a59cdfc6b65f43c6302cfbca8e7aa6f97457fa96d3b5
             a26e8f41204d2cd42be119c684b0f02370899a71ae3c1e71331543cc3fb2b4268780011ae4ea934c0ff0770
             8ee183e7e906fee489e8e1e57fce7a1c6df8fbaef39bbd1955dbd5ad1abffbe126f50205cb884af080ff3d7
             0549d3174b85bd7f6624c3753cf235b650d0e4228f32be7b54a590d869fb7786559bb7a4d66f9d3a69c085e
             fdf083a915d47a1d9161a08756b263b06e739d99f2890362abc96ade42cce8f939a40daff9"
        ),
        rsa_key.n.as_bytes(),
    );
    assert_eq!("user@example.com", key.comment());
    assert_eq!(
        "SHA256:Fmxts/GcV77PakFnf1Ueki5mpU4ZjUQWGRjZGAo3n/I",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(feature = "alloc")]
#[test]
fn decode_rsa_4096_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_RSA_4096_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Rsa { hash: None }, key.key_data().algorithm());

    let rsa_key = key.key_data().rsa().unwrap();
    assert_eq!(&hex!("010001"), rsa_key.e.as_bytes());
    assert_eq!(
        &hex!(
            "00b45911edc6ec5e7d2261a48c46ab889b1858306271123e6f02dc914cf3c0352492e8a6b7a7925added527
             e547dcebff6d0c19c0bc9153975199f47f4964ed20f5aceed4e82556b228a0c1fbfaa85e6339ba2ff4094d9
             4e2b09d43a3dd68225d0bbc858293cbf167b18d6374ebe79220a633d400176f1f6b46fd626acb252bf294aa
             db2acd59626a023a8e5ec53ced8685164c72ca3a2ec646812c6e61ffcba740ff15c054f0691e3a8d52c79c4
             4b7c1fc6c9704aed09ee0195bf09c5c5ba1173b7b1179be33fb3711d3b82e98f80521367a84303cb1236ebe
             8fc095683420a4de652c071d592759d42a0c9d2e73313cdfb71a071c936659433481a406308820e173b934f
             be877d873fec24d31a4d3bb9a3645055ca37bf710e214e5fc250d5964c66f18e4f05a3b93f42aa0753bd044
             e45b456c0e62fdcc1fcadef72930dc8a7a96b3e27d8eecea139a00aaf2fe79063ccb78d26d537625bdf0c4c
             8a68a04ed6f965eef7a6b1da5d8e26fc57f1047b97e2c594a9e420410977f22d1751b6d9498e8e457034049
             3c336bf86563ef03a15bc49b0ba6fe73201f64f0413ddb4d0cc5f6cf43389907e1df29e0cc388040e3371d0
             4814140f75cac08079431043222fb91f075d76be55cbe138e3b99a605c561c49dea50e253c8306c4f4f77d9
             96f898db64c5d8a0a15c6efa28b0934bf0b6f2b01950d877230fe4401078420fd6dd3"
        ),
        rsa_key.n.as_bytes(),
    );
    assert_eq!("user@example.com", key.comment());
    assert_eq!(
        "SHA256:FKAyeywtQNZLl1YTzIzCV/ThadBlnWMaD7jHQYDseEY",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_sk_ecdsa_p256_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_SK_ECDSA_P256_EXAMPLE).unwrap();
    assert_eq!(Algorithm::SkEcdsaSha2NistP256, key.key_data().algorithm());

    let ecdsa_key = key.key_data().sk_ecdsa_p256().unwrap();
    assert_eq!(
        &hex!(
            "04810b409d8382f697d72425285a247d6336b2eb9a085236aa9d1e268747ca0e8ee227f17375e944a775392
             f1d35842d13f6237574ab03e00e9cc1799ecd8d931e"
        ),
        ecdsa_key.ec_point().as_ref(),
    );

    assert_eq!("ssh:", ecdsa_key.application());

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());

    assert_eq!(
        "SHA256:UINe2WXFh3SiqwLxsBv34fBO2ei+g7uOeJJXVEK95iE",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(all(feature = "ecdsa", feature = "alloc"))]
#[test]
fn new_sk_ecdsa_p256() {
    const EXAMPLE_EC_POINT: [u8; 65] = [
        0x04, 0x81, 0x0b, 0x40, 0x9d, 0x83, 0x82, 0xf6, 0x97, 0xd7, 0x24, 0x25, 0x28, 0x5a, 0x24,
        0x7d, 0x63, 0x36, 0xb2, 0xeb, 0x9a, 0x08, 0x52, 0x36, 0xaa, 0x9d, 0x1e, 0x26, 0x87, 0x47,
        0xca, 0x0e, 0x8e, 0xe2, 0x27, 0xf1, 0x73, 0x75, 0xe9, 0x44, 0xa7, 0x75, 0x39, 0x2f, 0x1d,
        0x35, 0x84, 0x2d, 0x13, 0xf6, 0x23, 0x75, 0x74, 0xab, 0x03, 0xe0, 0x0e, 0x9c, 0xc1, 0x79,
        0x9e, 0xcd, 0x8d, 0x93, 0x1e,
    ];

    let ec_point = sec1::EncodedPoint::<U32>::from_bytes(&EXAMPLE_EC_POINT).unwrap();
    let sk_key = SkEcdsaSha2NistP256::new(ec_point, "ssh:".to_string());
    let key = PublicKey::from_openssh(OPENSSH_SK_ECDSA_P256_EXAMPLE).unwrap();

    let ecdsa_key = key.key_data().sk_ecdsa_p256().unwrap();
    assert_eq!(&sk_key, ecdsa_key);
}

#[test]
fn decode_sk_ed25519_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_SK_ED25519_EXAMPLE).unwrap();
    assert_eq!(Algorithm::SkEd25519, key.key_data().algorithm());

    let ed25519_key = key.key_data().sk_ed25519().unwrap();
    assert_eq!(
        &hex!("2168fe4e4b53cf3adeeeba602f5e50edb5ef441dba884f5119109db2dafdd733"),
        ed25519_key.public_key().as_ref(),
    );

    assert_eq!("ssh:", ed25519_key.application());

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());

    assert_eq!(
        "SHA256:6WZVJ44bqhAWLVP4Ns0TDkoSQSsZo/h2K+mEvOaNFbw",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(feature = "alloc")]
#[test]
fn new_sk_ed25519_openssh() {
    const EXAMPLE_PUBKEY: Ed25519PublicKey = Ed25519PublicKey {
        0: [
            0x21, 0x68, 0xfe, 0x4e, 0x4b, 0x53, 0xcf, 0x3a, 0xde, 0xee, 0xba, 0x60, 0x2f, 0x5e,
            0x50, 0xed, 0xb5, 0xef, 0x44, 0x1d, 0xba, 0x88, 0x4f, 0x51, 0x19, 0x10, 0x9d, 0xb2,
            0xda, 0xfd, 0xd7, 0x33,
        ],
    };

    let sk_key = SkEd25519::new(EXAMPLE_PUBKEY, "ssh:".to_string());
    let key = PublicKey::from_openssh(OPENSSH_SK_ED25519_EXAMPLE).unwrap();

    let ed25519_key = key.key_data().sk_ed25519().unwrap();
    assert_eq!(&sk_key, ed25519_key);
}

#[cfg(all(feature = "alloc"))]
#[test]
fn decode_custom_algorithm_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_OPAQUE_EXAMPLE).unwrap();
    assert!(
        matches!(key.algorithm(), Algorithm::Other(name) if name.as_str() == "name@example.com")
    );

    let opaque_key = key.key_data().other().unwrap();
    assert_eq!(
        &hex!("888f24ee17adfed0091e67e485fb9844cfed6072cac1d06390e4005f5015b44f"),
        opaque_key.as_ref(),
    );

    assert_eq!("comment@example.com", key.comment());

    assert_eq!(
        "SHA256:8GV7v5qOHG9invseKCx0NVwFocNL0MwdyRC9bfjTFGs",
        &key.fingerprint(Default::default()).to_string(),
    );
}

#[cfg(feature = "alloc")]
#[test]
fn encode_dsa_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_DSA_EXAMPLE).unwrap();
    assert_eq!(OPENSSH_DSA_EXAMPLE.trim_end(), &key.to_string());
}

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
#[test]
fn encode_ecdsa_p256_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_ECDSA_P256_EXAMPLE).unwrap();
    assert_eq!(OPENSSH_ECDSA_P256_EXAMPLE.trim_end(), &key.to_string());
}

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
#[test]
fn encode_ecdsa_p384_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_ECDSA_P384_EXAMPLE).unwrap();
    assert_eq!(OPENSSH_ECDSA_P384_EXAMPLE.trim_end(), &key.to_string());
}

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
#[test]
fn encode_ecdsa_p521_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_ECDSA_P521_EXAMPLE).unwrap();
    assert_eq!(OPENSSH_ECDSA_P521_EXAMPLE.trim_end(), &key.to_string());
}

#[cfg(feature = "alloc")]
#[test]
fn encode_ed25519_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    assert_eq!(OPENSSH_ED25519_EXAMPLE.trim_end(), &key.to_string());
}

#[cfg(feature = "alloc")]
#[test]
fn encode_rsa_3072_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_RSA_3072_EXAMPLE).unwrap();
    assert_eq!(OPENSSH_RSA_3072_EXAMPLE.trim_end(), &key.to_string());
}

#[cfg(feature = "alloc")]
#[test]
fn encode_rsa_4096_openssh() {
    let key = PublicKey::from_openssh(OPENSSH_RSA_4096_EXAMPLE).unwrap();
    assert_eq!(OPENSSH_RSA_4096_EXAMPLE.trim_end(), &key.to_string());
}

#[test]
fn public_keys_are_hashable() {
    let key = PublicKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    let set = HashSet::from([&key]);
    assert_eq!(true, set.contains(&key));
}

#[cfg(feature = "std")]
#[test]
fn write_openssh_file() {
    let example_key = OPENSSH_ED25519_EXAMPLE;
    let key = PublicKey::from_openssh(example_key).unwrap();

    let fingerprint = key
        .fingerprint(Default::default())
        .to_string()
        .replace(':', "-")
        .replace('/', "_");

    let path = scratch_path(&fingerprint);
    key.write_openssh_file(&path).unwrap();
    assert_eq!(example_key, fs::read_to_string(&path).unwrap());
}
