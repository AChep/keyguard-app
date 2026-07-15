//! OpenSSH certificate tests.

#![cfg(feature = "alloc")]

use hex_literal::hex;
use ssh_key::{Algorithm, Certificate};
use std::str::FromStr;

#[cfg(feature = "ecdsa")]
use ssh_key::EcdsaCurve;

#[cfg(feature = "rsa")]
use ssh_key::HashAlg;

/// DSA OpenSSH Certificate
const DSA_CERT_EXAMPLE: &str = include_str!("examples/id_dsa_1024-cert.pub");

/// ECDSA/P-256 OpenSSH Certificate
#[cfg(feature = "ecdsa")]
const ECDSA_P256_CERT_EXAMPLE: &str = include_str!("examples/id_ecdsa_p256-cert.pub");

/// Ed25519 OpenSSH Certificate
const ED25519_CERT_EXAMPLE: &str = include_str!("examples/id_ed25519-cert.pub");

/// Ed25519 OpenSSH Certificate with deliberately invalid signature
#[cfg(feature = "ed25519")]
const ED25519_CERT_BADSIG_EXAMPLE: &str = include_str!("examples/id_ed25519-cert-badsig.pub");

/// Ed25519 OpenSSH Certificate with P-256 certificate authority
#[cfg(feature = "p256")]
const ED25519_CERT_WITH_P256_CA_EXAMPLE: &str =
    include_str!("examples/id_ed25519-cert-with-p256-ca.pub");

/// Ed25519 OpenSSH Certificate with RSA certificate authority
#[cfg(feature = "rsa")]
const ED25519_CERT_WITH_RSA_CA_EXAMPLE: &str =
    include_str!("examples/id_ed25519-cert-with-rsa-ca.pub");

/// RSA (4096-bit) OpenSSH Certificate
const RSA_4096_CERT_EXAMPLE: &str = include_str!("examples/id_rsa_4096-cert.pub");

/// Security Key (FIDO/U2F) ECDSA/NIST P-256 OpenSSH Certificate
#[cfg(feature = "ecdsa")]
const SK_ECDSA_P256_CERT_EXAMPLE: &str = include_str!("examples/id_sk_ecdsa_p256-cert.pub");

/// Security Key (FIDO/U2F) Ed25519 OpenSSH Certificate
const SK_ED25519_CERT_EXAMPLE: &str = include_str!("examples/id_sk_ed25519-cert.pub");

/// Example certificate authority fingerprint (matches `id_ed25519.pub` example)
#[cfg(feature = "ed25519")]
const CA_FINGERPRINT: &str = "SHA256:UCUiLr7Pjs9wFFJMDByLgc3NrtdU344OgUM45wZPcIQ";

/// Valid certificate timestamp.
#[cfg(feature = "ed25519")]
const VALID_TIMESTAMP: u64 = 1750000000;

/// Timestamp which is before the validity window.
#[cfg(feature = "ed25519")]
const PAST_TIMESTAMP: u64 = 1500000000;

/// Expired certificate timestamp.
#[cfg(feature = "ed25519")]
const EXPIRED_TIMESTAMP: u64 = 2500000000;

#[test]
fn decode_dsa_openssh() {
    let cert = Certificate::from_str(DSA_CERT_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Dsa, cert.public_key().algorithm());

    let dsa_key = cert.public_key().dsa().unwrap();
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

    assert_eq!("user@example.com", cert.comment());
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_ecdsa_p256_openssh() {
    let cert = Certificate::from_str(ECDSA_P256_CERT_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP256
        },
        cert.public_key().algorithm(),
    );

    let ecdsa_key = cert.public_key().ecdsa().unwrap();
    assert_eq!(EcdsaCurve::NistP256, ecdsa_key.curve());
    assert_eq!(
        &hex!(
            "047c1fd8730ce53457be8d924098ec3648830f92aa8a2363ac656fdd4521fa6313e511f1891b4e9e5aaf8e1
             42d06ad15a66a4257f3f051d84e8a0e2f91ba807047"
        ),
        ecdsa_key.as_ref(),
    );

    assert_eq!("user@example.com", cert.comment());
}

#[test]
fn decode_ed25519_openssh() {
    let cert = Certificate::from_str(ED25519_CERT_EXAMPLE).unwrap();

    assert_eq!(Algorithm::Ed25519, cert.public_key().algorithm());
    assert_eq!(
        &hex!("b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62"),
        cert.public_key().ed25519().unwrap().as_ref(),
    );

    assert_eq!("user@example.com", cert.comment());
    assert_eq!(cert.valid_principals().len(), 1);
    assert_eq!(cert.valid_principals()[0], "host.example.com");
}

#[test]
fn decode_ed25519_openssh_with_crit_options() {
    let src = "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAIBW/4zLqXWROWmN1sPgdySnH1GUsEFBjFrRwKKw71BoBAAAAIH1MFwI1oRdEifXgBQvWQfCBBtA/Pi8YCUE/I3wXFJo2AAAAAAAAAAAAAAABAAAAA2ZvbwAAAAAAAAAAAAAAAH//////////AAAAIwAAABFoZWxsb0BleGFtcGxlLmNvbQAAAAoAAAAGZm9vYmFyAAAAAAAAAAAAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIH1MFwI1oRdEifXgBQvWQfCBBtA/Pi8YCUE/I3wXFJo2AAAAUwAAAAtzc2gtZWQyNTUxOQAAAEDRoPdI48KyoaLgaDZsSGs80qBeYQOXBd84CX8GYzFt/L21rxF1EeuPOkgsx7Q39WllXp+FgMMojsHftK/DJHEN";
    let cert = Certificate::from_str(src).unwrap();

    assert_eq!(Algorithm::Ed25519, cert.public_key().algorithm());

    assert_eq!(cert.critical_options().len(), 1);
    assert_eq!(
        cert.critical_options().get("hello@example.com").unwrap(),
        "foobar"
    );

    let openssh = cert.to_openssh().unwrap();

    assert_eq!(openssh, src);

    assert_eq!(cert, Certificate::from_str(&openssh).unwrap());
}

#[test]
fn decode_rsa_4096_openssh() {
    let cert = Certificate::from_str(RSA_4096_CERT_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Rsa { hash: None }, cert.public_key().algorithm());

    let rsa_key = cert.public_key().rsa().unwrap();
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

    assert_eq!("user@example.com", cert.comment());
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_sk_ecdsa_p256_openssh() {
    let cert = Certificate::from_str(SK_ECDSA_P256_CERT_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::SkEcdsaSha2NistP256,
        cert.public_key().algorithm()
    );

    let ecdsa_key = cert.public_key().sk_ecdsa_p256().unwrap();
    assert_eq!(
        &hex!(
            "04810b409d8382f697d72425285a247d6336b2eb9a085236aa9d1e268747ca0e8ee227f17375e944a775392
             f1d35842d13f6237574ab03e00e9cc1799ecd8d931e"
        ),
        ecdsa_key.ec_point().as_ref(),
    );

    assert_eq!("ssh:", ecdsa_key.application());
    assert_eq!("user@example.com", cert.comment());
}

#[test]
fn decode_sk_ed25519_openssh() {
    let cert = Certificate::from_str(SK_ED25519_CERT_EXAMPLE).unwrap();

    assert_eq!(Algorithm::SkEd25519, cert.public_key().algorithm());

    let ed25519_key = cert.public_key().sk_ed25519().unwrap();
    assert_eq!(
        &hex!("2168fe4e4b53cf3adeeeba602f5e50edb5ef441dba884f5119109db2dafdd733"),
        ed25519_key.public_key().as_ref(),
    );

    assert_eq!("ssh:", ed25519_key.application());
    assert_eq!("user@example.com", cert.comment());
}

#[test]
fn encode_dsa_openssh() {
    let cert = Certificate::from_str(DSA_CERT_EXAMPLE).unwrap();
    assert_eq!(DSA_CERT_EXAMPLE.trim_end(), &cert.to_openssh().unwrap());
}

#[cfg(feature = "ecdsa")]
#[test]
fn encode_ecdsa_p256_openssh() {
    let cert = Certificate::from_str(ECDSA_P256_CERT_EXAMPLE).unwrap();
    assert_eq!(
        ECDSA_P256_CERT_EXAMPLE.trim_end(),
        &cert.to_openssh().unwrap()
    );
}

#[test]
fn encode_ed25519_openssh() {
    let cert = Certificate::from_str(ED25519_CERT_EXAMPLE).unwrap();
    assert_eq!(ED25519_CERT_EXAMPLE.trim_end(), &cert.to_openssh().unwrap());
}

#[test]
fn encode_rsa_4096_openssh() {
    let cert = Certificate::from_str(RSA_4096_CERT_EXAMPLE).unwrap();
    assert_eq!(
        RSA_4096_CERT_EXAMPLE.trim_end(),
        &cert.to_openssh().unwrap()
    );
}

#[cfg(feature = "ed25519")]
#[test]
fn verify_ed25519_certificate_signature() {
    let cert = Certificate::from_str(ED25519_CERT_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Ed25519, cert.signature_key().algorithm());
    assert!(cert.verify_signature().is_ok());
}

#[cfg(feature = "ed25519")]
#[test]
fn reject_ed25519_certificate_with_invalid_signature() {
    let cert = Certificate::from_str(ED25519_CERT_BADSIG_EXAMPLE).unwrap();
    assert!(cert.verify_signature().is_err());
}

#[cfg(feature = "ed25519")]
#[test]
fn validate_certificate() {
    let cert = Certificate::from_str(ED25519_CERT_EXAMPLE).unwrap();
    let ca = CA_FINGERPRINT.parse().unwrap();
    assert!(cert.validate_at(VALID_TIMESTAMP, &[ca]).is_ok());
}

#[cfg(all(feature = "ed25519", feature = "std"))]
#[test]
fn validate_certificate_against_system_clock() {
    let cert = Certificate::from_str(ED25519_CERT_EXAMPLE).unwrap();
    let ca = CA_FINGERPRINT.parse().unwrap();
    assert!(cert.validate(&[ca]).is_ok());
}

#[cfg(feature = "ed25519")]
#[test]
fn reject_certificate_with_invalid_signature() {
    let cert = Certificate::from_str(ED25519_CERT_BADSIG_EXAMPLE).unwrap();
    let ca = CA_FINGERPRINT.parse().unwrap();
    assert!(cert.validate_at(VALID_TIMESTAMP, &[ca]).is_err());
}

#[cfg(feature = "ed25519")]
#[test]
fn reject_certificate_with_untrusted_ca() {
    let cert = Certificate::from_str(ED25519_CERT_EXAMPLE).unwrap();
    let ca = Certificate::from_str(DSA_CERT_EXAMPLE)
        .unwrap()
        .public_key()
        .fingerprint(Default::default());

    assert!(cert.validate_at(VALID_TIMESTAMP, &[ca]).is_err());
}

#[cfg(feature = "ed25519")]
#[test]
fn reject_expired_certificate() {
    let cert = Certificate::from_str(ED25519_CERT_EXAMPLE).unwrap();
    let ca = CA_FINGERPRINT.parse().unwrap();
    assert!(cert.validate_at(EXPIRED_TIMESTAMP, &[ca]).is_err());
}

#[cfg(feature = "ed25519")]
#[test]
fn reject_certificate_with_future_valid_after() {
    let cert = Certificate::from_str(ED25519_CERT_EXAMPLE).unwrap();
    let ca = CA_FINGERPRINT.parse().unwrap();
    assert!(cert.validate_at(PAST_TIMESTAMP, &[ca]).is_err())
}

#[cfg(feature = "p256")]
#[test]
fn verify_p256_certificate_signature() {
    let cert = Certificate::from_str(ED25519_CERT_WITH_P256_CA_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP256
        },
        cert.signature_key().algorithm()
    );
    assert!(cert.verify_signature().is_ok());
}

#[cfg(feature = "rsa")]
#[test]
fn verify_rsa_certificate_signature() {
    let cert = Certificate::from_str(ED25519_CERT_WITH_RSA_CA_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Rsa { hash: None },
        cert.signature_key().algorithm()
    );
    assert!(cert.verify_signature().is_ok());
    assert_eq!(
        Algorithm::Rsa {
            hash: Some(HashAlg::Sha512)
        },
        cert.signature().algorithm()
    );
}
