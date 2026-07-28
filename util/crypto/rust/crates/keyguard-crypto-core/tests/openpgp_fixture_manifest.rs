//! Integrity lock for the externally verified OpenPGP fixture corpus.

use aws_lc_rs::digest::{SHA256, digest};

#[test]
fn checked_in_openpgp_fixtures_match_the_reviewed_manifest() {
    let fixtures: &[(&str, &[u8], &str)] = &[
        (
            "benchmark-detached-signature-8m-5a.asc",
            include_bytes!("fixtures/openpgp/benchmark-detached-signature-8m-5a.asc"),
            "d2135b920a30c81539f82c932997c3efcc7847b7974d36c0c066ad66d6e0eb6c",
        ),
        (
            "clear-signed.asc",
            include_bytes!("fixtures/openpgp/clear-signed.asc"),
            "06f6733de088eef0b7d4658f3e9c61dd459c496545b2f5af8681eece486295ff",
        ),
        (
            "cv25519-public.asc",
            include_bytes!("fixtures/openpgp/cv25519-public.asc"),
            "b7b827e7c13bf02954f9a136b0bb98b23e4ee2a08ca51a48f6da3ef731d8b5ea",
        ),
        (
            "cv25519-secret.asc",
            include_bytes!("fixtures/openpgp/cv25519-secret.asc"),
            "af3d7b417cca041f27479e8b1476712d7b4c7130e76564616b72a9d9ba63df90",
        ),
        (
            "designated-revoked-public.asc",
            include_bytes!("fixtures/openpgp/designated-revoked-public.asc"),
            "6e9ab985e3fd4111d775db8e035734dfd0a186a491686f74796bdc5df469b03f",
        ),
        (
            "designated-revoker-public.asc",
            include_bytes!("fixtures/openpgp/designated-revoker-public.asc"),
            "f67aa51dc62c00060ae76526e9306cc7b641f5e152304958d33067154c18923c",
        ),
        (
            "detached-body.txt",
            include_bytes!("fixtures/openpgp/detached-body.txt"),
            "8525a3326cf20c9f6e53b41257f0b0afdd8c58052dd666162a3bf70e9c114a6a",
        ),
        (
            "detached-signature.asc",
            include_bytes!("fixtures/openpgp/detached-signature.asc"),
            "b2051b4e3f03793ae9db9769d4650aff15070866be76d0498c8633c59d4cc26f",
        ),
        (
            "mdc-public.asc",
            include_bytes!("fixtures/openpgp/mdc-public.asc"),
            "f68a73328dbc77441ccc15f2d65509e46a57aa598ed7474542700ba32552a81b",
        ),
        (
            "mdc-secret.asc",
            include_bytes!("fixtures/openpgp/mdc-secret.asc"),
            "cade1a0b5b8963632b0892e971d4dad346fccb703b20c63201d1562babed77f0",
        ),
        (
            "v3-public.asc",
            include_bytes!("fixtures/openpgp/v3-public.asc"),
            "998a9e2054e4f05c86a6e47c44f344a634cd5bdda19257675f31b89a63e7889c",
        ),
        (
            "v3-secret.asc",
            include_bytes!("fixtures/openpgp/v3-secret.asc"),
            "d47336c382b4b7772ab98c38f2a39d8d3a083b6a55a8956c5f709ea7af127ae7",
        ),
    ];

    for (name, contents, expected) in fixtures {
        let actual = digest(&SHA256, contents)
            .as_ref()
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        assert_eq!(actual, *expected, "fixture digest changed: {name}");
    }
}
