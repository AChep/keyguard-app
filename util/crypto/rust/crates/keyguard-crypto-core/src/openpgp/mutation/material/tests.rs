use super::*;

const PUBLIC_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/cv25519-public.asc");
const SECRET_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/cv25519-secret.asc");
const OTHER_PUBLIC_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/mdc-public.asc");

#[test]
fn preflight_maps_a_different_primary_key_to_fingerprint_mismatch() {
    assert_eq!(
        MutationPreflight::open(SECRET_KEY, OTHER_PUBLIC_KEY, &[], "", 1_700_000_000)
            .err()
            .expect("mismatched certificate must be refused"),
        MutationMaterialError::FingerprintMismatch,
    );
}

#[test]
fn preflight_unions_the_secret_projection_with_the_stored_certificate() {
    let preflight = MutationPreflight::open(SECRET_KEY, PUBLIC_KEY, &[], "", 1_700_000_000)
        .expect("open preflight");
    let (projection, _) = project_secret_certificate(SECRET_KEY).expect("project secret");
    let projected = parse_single_certificate_packet_set(&projection).expect("parse projection");
    let stored = parse_single_certificate_packet_set(PUBLIC_KEY).expect("parse stored certificate");
    let mut expected = stored;
    expected.merge(projected).expect("merge evidence");
    assert_eq!(
        preflight.canonical.bytes,
        expected.finalize().expect("finalize union").bytes,
    );
    // Marker packets carry no evidence, so adding one must not change the
    // union the mutation operates on.
    let mut with_marker = PUBLIC_KEY.to_vec();
    with_marker.extend_from_slice(&[0xca, 0x03, b'P', b'G', b'P']);
    let guarded = MutationPreflight::open(SECRET_KEY, &with_marker, &[], "", 1_700_000_000)
        .expect("open guarded preflight");
    assert_eq!(guarded.canonical.bytes, preflight.canonical.bytes);
}
