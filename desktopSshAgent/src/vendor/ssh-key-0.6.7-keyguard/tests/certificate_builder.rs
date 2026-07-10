//! Certificate builder tests.

#![cfg(all(
    feature = "alloc",
    feature = "rand_core",
    any(feature = "ed25519", feature = "p256")
))]

use hex_literal::hex;
use rand_chacha::{rand_core::SeedableRng, ChaCha8Rng};
use ssh_key::{certificate, Algorithm, PrivateKey};

#[cfg(feature = "p256")]
use ssh_key::EcdsaCurve;

#[cfg(all(feature = "ed25519", feature = "rsa"))]
use std::str::FromStr;

#[cfg(all(feature = "ed25519", feature = "std"))]
use std::time::{Duration, SystemTime};

/// Example Unix timestamp when a certificate was issued (2020-09-13 12:26:40 UTC).
const ISSUED_AT: u64 = 1600000000;

/// Example Unix timestamp when a certificate is valid (2022-04-15 05:20:00 UTC).
const VALID_AT: u64 = 1650000000;

/// Example Unix timestamp when a certificate expires (2023-11-14 22:13:20 UTC).
const EXPIRES_AT: u64 = 1700000000;

/// Seed to use for PRNG.
const PRNG_SEED: [u8; 32] = [42; 32];

#[cfg(feature = "ed25519")]
#[test]
fn ed25519_sign_and_verify() {
    const SERIAL: u64 = 42;
    const KEY_ID: &str = "example";
    const PRINCIPAL: &str = "nobody";
    const CRITICAL_EXTENSION_1: (&str, &str) = ("critical name 1", "critical data 2");
    const CRITICAL_EXTENSION_2: (&str, &str) = ("critical name 2", "critical data 2");
    const EXTENSION_1: (&str, &str) = ("extension name 1", "extension data 1");
    const EXTENSION_2: (&str, &str) = ("extension name 2", "extension data 2");
    const COMMENT: &str = "user@example.com";

    let mut rng = ChaCha8Rng::from_seed(PRNG_SEED);

    let ca_key = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();
    let subject_key = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();

    let mut cert_builder = certificate::Builder::new_with_random_nonce(
        &mut rng,
        subject_key.public_key(),
        ISSUED_AT,
        EXPIRES_AT,
    )
    .unwrap();

    cert_builder.serial(SERIAL).unwrap();
    cert_builder.key_id(KEY_ID).unwrap();
    cert_builder.valid_principal(PRINCIPAL).unwrap();
    cert_builder
        .critical_option(CRITICAL_EXTENSION_1.0, CRITICAL_EXTENSION_1.1)
        .unwrap();
    cert_builder
        .critical_option(CRITICAL_EXTENSION_2.0, CRITICAL_EXTENSION_2.1)
        .unwrap();
    cert_builder
        .extension(EXTENSION_1.0, EXTENSION_1.1)
        .unwrap();
    cert_builder
        .extension(EXTENSION_2.0, EXTENSION_2.1)
        .unwrap();
    cert_builder.comment(COMMENT).unwrap();

    let cert = cert_builder.sign(&ca_key).unwrap();
    assert_eq!(cert.algorithm(), Algorithm::Ed25519);
    assert_eq!(cert.nonce(), &hex!("321fdf7e0a2afe803308f394f54c6abe"));
    assert_eq!(cert.public_key(), subject_key.public_key().key_data());
    assert_eq!(cert.serial(), SERIAL);
    assert_eq!(cert.cert_type(), certificate::CertType::User);
    assert_eq!(cert.key_id(), KEY_ID);
    assert_eq!(cert.valid_principals().len(), 1);
    assert_eq!(cert.valid_principals()[0], PRINCIPAL);
    assert_eq!(cert.valid_after(), ISSUED_AT);
    assert_eq!(cert.valid_before(), EXPIRES_AT);
    assert_eq!(cert.critical_options().len(), 2);
    assert_eq!(
        cert.critical_options().get(CRITICAL_EXTENSION_1.0).unwrap(),
        CRITICAL_EXTENSION_1.1
    );
    assert_eq!(cert.extensions().get(EXTENSION_2.0).unwrap(), EXTENSION_2.1);
    assert_eq!(cert.extensions().len(), 2);
    assert_eq!(cert.extensions().get(EXTENSION_1.0).unwrap(), EXTENSION_1.1);
    assert_eq!(cert.extensions().get(EXTENSION_2.0).unwrap(), EXTENSION_2.1);
    assert_eq!(cert.signature_key(), ca_key.public_key().key_data());
    assert_eq!(cert.comment(), COMMENT);

    let ca_fingerprint = ca_key.fingerprint(Default::default());
    assert!(cert.validate_at(VALID_AT, &[ca_fingerprint]).is_ok());
}

#[cfg(feature = "p256")]
#[test]
fn ecdsa_nistp256_sign_and_verify() {
    let mut rng = ChaCha8Rng::from_seed(PRNG_SEED);

    let algorithm = Algorithm::Ecdsa {
        curve: EcdsaCurve::NistP256,
    };
    let ca_key = PrivateKey::random(&mut rng, algorithm.clone()).unwrap();
    let subject_key = PrivateKey::random(&mut rng, algorithm.clone()).unwrap();
    let mut cert_builder = certificate::Builder::new_with_random_nonce(
        &mut rng,
        subject_key.public_key(),
        ISSUED_AT,
        EXPIRES_AT,
    )
    .unwrap();
    cert_builder.all_principals_valid().unwrap();
    let cert = cert_builder.sign(&ca_key).unwrap();

    assert_eq!(cert.algorithm(), algorithm);
    assert_eq!(cert.nonce(), &hex!("321fdf7e0a2afe803308f394f54c6abe"));
    assert_eq!(cert.public_key(), subject_key.public_key().key_data());
    assert_eq!(cert.signature_key(), ca_key.public_key().key_data());

    let ca_fingerprint = ca_key.fingerprint(Default::default());
    assert!(cert.validate_at(VALID_AT, &[ca_fingerprint]).is_ok());
}

#[cfg(all(feature = "ed25519", feature = "rsa"))]
#[test]
fn rsa_sign_and_verify() {
    let ca_key = PrivateKey::from_str(
        r#"-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
NhAAAAAwEAAQAAAQEAyng6J3IE5++Ji7EfVNTANDnhYH46LnZW+bwW45etzKswQkc/AvSA
9ih2VAhE8FFUR0Z6pyl4hEn/878x50pGt1FHplbbe4wZ5aornT1hcGGYy313Glt+zyn96M
BTAjO0yULa1RrhBBmeY3yXIEAApUIVdvxcLOvJgltSFmFURtbY5cZkweuspwnHBE/JUPBX
9/Njb+z2R4BTnf0UrudxRKA/TJx9mL3Pb2JjkXfQ07pZqp+oEiUoGMvdfN9vYW4J5LTbXo
n20kRt5UKSxKggBBa0rzGabF+P/BTd39ZrI27WRYhDAzeYJoLq/xfO6qCgAM3TKxe0tDeT
gV4akFJ9CwAAA7hN/dPaTf3T2gAAAAdzc2gtcnNhAAABAQDKeDoncgTn74mLsR9U1MA0Oe
Fgfjoudlb5vBbjl63MqzBCRz8C9ID2KHZUCETwUVRHRnqnKXiESf/zvzHnSka3UUemVtt7
jBnlqiudPWFwYZjLfXcaW37PKf3owFMCM7TJQtrVGuEEGZ5jfJcgQAClQhV2/Fws68mCW1
IWYVRG1tjlxmTB66ynCccET8lQ8Ff382Nv7PZHgFOd/RSu53FEoD9MnH2Yvc9vYmORd9DT
ulmqn6gSJSgYy918329hbgnktNteifbSRG3lQpLEqCAEFrSvMZpsX4/8FN3f1msjbtZFiE
MDN5gmgur/F87qoKAAzdMrF7S0N5OBXhqQUn0LAAAAAwEAAQAAAQAxxSgWdjK6iOl4y0t2
YO32aJv8SksnDLQIo7HEtI5ml1Y/lJ/qrAvfdsbPlVDM+lELTEnuOYWEj2Q5mLA9uMZ1Xa
eNPiCp2CCtkg0yk9oV9AfJTcgvVHpxllLyGgTNr8QrDSIZ7IePqHSE5CWKKfF+riX0n8hQ
yo04XBZrpfU/jDQV8ENKiNQd3Aiy6ppSbnDhyTzZEYIxtvnh1FmvU0Ct1jQRd8p42gurEn
sq6nAPE9pnn0otKmjRdfGCnM9X/ZbUcaUcU/X8pPYG1pW0GZR7eTO+1f9s8TS5LIqz2Eru
L4gBQweASh9mhatsMqJX/ZRrdHvdIuH8N1VDSahf1ZTxAAAAgF1+qA6ZVBEaoCj+fAJZyU
EYf7NMI/nPqEVxiIjg4WKmRYKC9Pb9cuGehOs/XTi3KMEHzYJIKT1K+uO0OG025XVH06qk
9qyWcBwtRbCPVFJPSkKyGBPaUIxMI07x1+434vig6z7iwVROxy3vyhslgiJNpIkaWVUhQN
EGEHX0oWLfAAAAgQDLd25QLAb1kngTsuwQ+xo3S6UcQvOTiDnVRvxWPaW4yn/3qO55+esd
dzxUujFXhUO/POeUJiHv0B1QlDm/sHYL6YVI5+XRaWAst/z0T93mM4ts63Z1OoJbAtE5qH
yGlKVPQ5ZG8SUVElbX+SZE2CcnsPx53trW8qQu/R2bPdDN7QAAAIEA/r7nlgz6D93vMVkn
wq38d49h+PTfyBQ1bum8AhxCEfTaK94YrH9BeizO6Ma5MIjY6WHWbq7Co93J3fl8f4eTCo
CpHJYWfbBqrf/5PUoOIjdMdfFHK6GpUCQNxhbSpnL4l75sxrhkEXtBHVKRXCNR5T4JnOcx
R6qbyo6hPuCiV9cAAAAAAQID
-----END OPENSSH PRIVATE KEY-----"#,
    )
    .unwrap();

    let mut rng = ChaCha8Rng::from_seed(PRNG_SEED);
    let subject_key = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();
    let mut cert_builder = certificate::Builder::new_with_random_nonce(
        &mut rng,
        subject_key.public_key(),
        ISSUED_AT,
        EXPIRES_AT,
    )
    .unwrap();
    cert_builder.all_principals_valid().unwrap();
    let cert = cert_builder.sign(&ca_key).unwrap();

    assert_eq!(
        cert.signature_key().algorithm(),
        Algorithm::Rsa { hash: None }
    );
    assert_eq!(cert.nonce(), &hex!("55742ecb25ee56057b9e35eae54c40a9"));
    assert_eq!(cert.public_key(), subject_key.public_key().key_data());
    assert_eq!(cert.signature_key(), ca_key.public_key().key_data());

    let ca_fingerprint = ca_key.fingerprint(Default::default());
    assert!(cert.validate_at(VALID_AT, &[ca_fingerprint]).is_ok());
}

#[cfg(all(feature = "ed25519", feature = "std"))]
#[test]
fn new_with_validity_times() {
    let mut rng = ChaCha8Rng::from_seed(PRNG_SEED);
    let subject_key = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();

    // NOTE: use a random nonce, not an all-zero one!
    let nonce = [0u8; certificate::Builder::RECOMMENDED_NONCE_SIZE];

    let issued_at = SystemTime::now();
    let expires_at = issued_at + Duration::from_secs(3600);

    assert!(certificate::Builder::new_with_validity_times(
        nonce,
        subject_key.public_key(),
        issued_at,
        expires_at
    )
    .is_ok());
}
