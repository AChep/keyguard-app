//! End-to-end OpenPGP certificate reconciliation benchmark.

use std::{hint::black_box, io::Cursor, time::Duration};

use criterion::{Criterion, criterion_group, criterion_main};
use keyguard_crypto_core::{
    PROTOCOL_VERSION, call,
    protocol::{
        NativeRequest, NativeResponse, OpenPgpCertificateMaterialReconcileRequest,
        OpenPgpCertificateMaterialReconcileResult, native_request, native_response,
        open_pgp_certificate_material_reconcile_result,
    },
};
use pgp::{
    composed::{Deserializable, SignedPublicKey},
    packet::{Signature, Subpacket, SubpacketData},
    ser::Serialize,
    types::KeyDetails,
};
use prost::Message;

const PUBLIC_KEY: &[u8] = include_bytes!("../tests/fixtures/openpgp/cv25519-public.asc");

fn benchmark_certificate_reconcile(c: &mut Criterion) {
    let (certificate, _) = SignedPublicKey::from_reader_single(Cursor::new(PUBLIC_KEY))
        .expect("benchmark public certificate must parse");
    let request = reconcile_request(&certificate, PUBLIC_KEY);
    assert_reconcile_succeeds(&request);

    let mut flooded = certificate.clone();
    let signature = flooded.details.users[0].signatures[0].clone();
    flooded.details.users[0].signatures = (0..256)
        .map(|marker| signature_with_unhashed_marker(&signature, marker))
        .collect();
    let flooded_bytes = flooded
        .to_bytes()
        .expect("benchmark signature flood must serialize");
    let flooded_request = reconcile_request(&flooded, &flooded_bytes);
    assert_reconcile_succeeds(&flooded_request);

    c.bench_function(
        "openpgp_certificate_material_reconcile_identical",
        |bencher| {
            bencher.iter(|| black_box(call(black_box(&request))));
        },
    );
    c.bench_function(
        "openpgp_certificate_material_reconcile_unhashed_variant_flood_256",
        |bencher| {
            bencher.iter(|| black_box(call(black_box(&flooded_request))));
        },
    );
}

fn reconcile_request(certificate: &SignedPublicKey, bytes: &[u8]) -> Vec<u8> {
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(
            native_request::Operation::OpenPgpCertificateMaterialReconcile(
                OpenPgpCertificateMaterialReconcileRequest {
                    expected_primary_fingerprint: format!(
                        "{:X}",
                        certificate.primary_key.fingerprint()
                    ),
                    existing_public_certificate: Some(bytes.to_vec()),
                    incoming_public_certificate: Some(bytes.to_vec()),
                    existing_secret_certificate: None,
                    incoming_secret_certificate: None,
                },
            ),
        ),
    };
    request.encode_to_vec()
}

fn signature_with_unhashed_marker(signature: &Signature, marker: u16) -> Signature {
    let mut config = signature
        .config()
        .cloned()
        .expect("benchmark signature must have a known configuration");
    config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::Experimental(
            100,
            marker.to_be_bytes().to_vec().into(),
        ))
        .expect("benchmark experimental subpacket must serialize"),
    );
    Signature::from_config(
        config,
        signature
            .signed_hash_value()
            .expect("benchmark signature must have a digest prefix"),
        signature
            .signature()
            .cloned()
            .expect("benchmark signature must have signature material"),
    )
    .expect("benchmark signature variant must be valid")
}

fn assert_reconcile_succeeds(request: &[u8]) {
    let response = NativeResponse::decode(call(request).as_slice())
        .expect("benchmark native response must decode");
    let payload = match response.result {
        Some(native_response::Result::BytesValue(payload)) => payload,
        _ => panic!("benchmark reconciliation must return bytes"),
    };
    let result = OpenPgpCertificateMaterialReconcileResult::decode(payload.as_slice())
        .expect("benchmark reconciliation result must decode");
    assert!(matches!(
        result.result,
        Some(open_pgp_certificate_material_reconcile_result::Result::Success(_)),
    ));
}

fn criterion_config() -> Criterion {
    Criterion::default()
        .sample_size(20)
        .warm_up_time(Duration::from_millis(500))
        .measurement_time(Duration::from_secs(2))
        .without_plots()
}

criterion_group! {
    name = benches;
    config = criterion_config();
    targets = benchmark_certificate_reconcile
}
criterion_main!(benches);
