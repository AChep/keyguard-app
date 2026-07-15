//! Tests for `AlgorithmName` parsing.

#![cfg(feature = "alloc")]

use ssh_key::AlgorithmName;
use std::str::FromStr;

#[test]
fn additional_algorithm_name() {
    const NAME: &str = "name@example.com";
    const CERT_STR: &str = "name-cert-v01@example.com";

    let name = AlgorithmName::from_str(NAME).unwrap();
    assert_eq!(name.as_str(), NAME);
    assert_eq!(name.certificate_type(), CERT_STR);

    let name = AlgorithmName::from_certificate_type(CERT_STR).unwrap();
    assert_eq!(name.as_str(), NAME);
    assert_eq!(name.certificate_type(), CERT_STR);
}

#[test]
fn invalid_algorithm_name() {
    const INVALID_NAMES: &[&str] = &[
        "nameß@example.com",
        "name@example@com",
        "name",
        "@name",
        "name@",
        "",
        "@",
        "a-name-that-is-too-long-but-would-otherwise-be-valid-@example.com",
    ];

    const INVALID_CERT_STRS: &[&str] = &[
        "nameß-cert-v01@example.com",
        "name-cert-v01@example@com",
        "name@example.com",
    ];

    for name in INVALID_NAMES {
        assert!(
            AlgorithmName::from_str(&name).is_err(),
            "{:?} should be an invalid algorithm name",
            name
        );
    }

    for name in INVALID_CERT_STRS {
        assert!(
            AlgorithmName::from_certificate_type(&name).is_err(),
            "{:?} should be an invalid certificate str",
            name
        );
    }
}
