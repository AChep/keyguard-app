//! Layered small-payload benchmarks for the Bitwarden AES-CBC/HMAC fast path.
//!
//! This is diagnostic output rather than a release gate. It separates Rust
//! protobuf encoding/dispatch from the allocation-free direct data plane so
//! the JVM benchmark can attribute the remaining platform-boundary cost.

use std::{hint::black_box, time::Duration};

use criterion::{BenchmarkId, Criterion, Throughput, criterion_group, criterion_main};
use keyguard_crypto_core::{
    PROTOCOL_VERSION, call, fast,
    protocol::{AesCbcPkcs7HmacSha256EncryptRequest, NativeRequest, native_request},
};
use prost::Message;

const PAYLOAD_SIZES: [usize; 4] = [0, 32, 1024, 64 * 1024];

struct Fixture {
    payload_bytes: usize,
    encryption_key: Vec<u8>,
    mac_key: Vec<u8>,
    iv: Vec<u8>,
    plaintext: Vec<u8>,
    request: NativeRequest,
    encoded_request: Vec<u8>,
    ciphertext: Vec<u8>,
    mac: Vec<u8>,
    plaintext_output: Vec<u8>,
}

impl Fixture {
    fn new(payload_bytes: usize) -> Self {
        let encryption_key: Vec<u8> = (0_u8..32).collect();
        let mac_key: Vec<u8> = (32_u8..64).collect();
        let iv: Vec<u8> = (64_u8..80).collect();
        let plaintext = deterministic_bytes(payload_bytes);
        let request = NativeRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: Some(native_request::Operation::AesCbcPkcs7HmacSha256Encrypt(
                AesCbcPkcs7HmacSha256EncryptRequest {
                    encryption_key: encryption_key.clone(),
                    mac_key: mac_key.clone(),
                    iv: iv.clone(),
                    plaintext: plaintext.clone(),
                },
            )),
        };
        let encoded_request = request.encode_to_vec();
        let ciphertext_bytes = payload_bytes
            .checked_add(16)
            .map(|length| length / 16 * 16)
            .expect("benchmark payload length must be representable");
        let mut ciphertext = vec![0_u8; ciphertext_bytes];
        let mut mac = vec![0_u8; 32];
        fast::encrypt_into(
            &encryption_key,
            &mac_key,
            &iv,
            &plaintext,
            &mut ciphertext,
            &mut mac,
        )
        .expect("benchmark encryption fixture must be valid");
        let plaintext_output = vec![0_u8; ciphertext.len()];

        Self {
            payload_bytes,
            encryption_key,
            mac_key,
            iv,
            plaintext,
            request,
            encoded_request,
            ciphertext,
            mac,
            plaintext_output,
        }
    }
}

fn benchmark_small_primitives(c: &mut Criterion) {
    let mut fixtures = PAYLOAD_SIZES.map(Fixture::new);

    {
        let mut group = c.benchmark_group("protobuf-request-encode");
        for fixture in &fixtures {
            set_throughput(&mut group, fixture.payload_bytes);
            group.bench_with_input(
                BenchmarkId::from_parameter(fixture.payload_bytes),
                fixture,
                |bencher, fixture| {
                    bencher.iter(|| {
                        let encoded = black_box(black_box(&fixture.request).encode_to_vec());
                        checksum(&encoded)
                    });
                },
            );
        }
        group.finish();
    }

    {
        let mut group = c.benchmark_group("protobuf-dispatch-encrypt");
        for fixture in &fixtures {
            set_throughput(&mut group, fixture.payload_bytes);
            group.bench_with_input(
                BenchmarkId::from_parameter(fixture.payload_bytes),
                fixture,
                |bencher, fixture| {
                    bencher.iter(|| {
                        let response = black_box(call(black_box(&fixture.encoded_request)));
                        checksum(&response)
                    });
                },
            );
        }
        group.finish();
    }

    {
        let mut group = c.benchmark_group("direct-fast-encrypt");
        for fixture in &mut fixtures {
            set_throughput(&mut group, fixture.payload_bytes);
            let benchmark_id = BenchmarkId::from_parameter(fixture.payload_bytes);
            group.bench_function(benchmark_id, |bencher| {
                bencher.iter(|| {
                    fast::encrypt_into(
                        black_box(&fixture.encryption_key),
                        black_box(&fixture.mac_key),
                        black_box(&fixture.iv),
                        black_box(&fixture.plaintext),
                        black_box(&mut fixture.ciphertext),
                        black_box(&mut fixture.mac),
                    )
                    .expect("benchmark encryption must succeed")
                });
            });
        }
        group.finish();
    }

    {
        let mut group = c.benchmark_group("direct-fast-decrypt");
        for fixture in &mut fixtures {
            set_throughput(&mut group, fixture.payload_bytes);
            let benchmark_id = BenchmarkId::from_parameter(fixture.payload_bytes);
            group.bench_function(benchmark_id, |bencher| {
                bencher.iter(|| {
                    fast::decrypt_into(
                        black_box(&fixture.encryption_key),
                        black_box(&fixture.mac_key),
                        black_box(&fixture.iv),
                        black_box(&fixture.ciphertext),
                        black_box(&fixture.mac),
                        black_box(&mut fixture.plaintext_output),
                    )
                    .expect("benchmark decryption must succeed")
                });
            });
        }
        group.finish();
    }
}

fn set_throughput(
    group: &mut criterion::BenchmarkGroup<'_, criterion::measurement::WallTime>,
    payload_bytes: usize,
) {
    if payload_bytes > 0 {
        group.throughput(Throughput::Bytes(payload_bytes as u64));
    }
}

fn deterministic_bytes(size: usize) -> Vec<u8> {
    (0..size)
        .map(|index| ((0x43_usize + index * 37) & 0xff) as u8)
        .collect()
}

fn checksum(bytes: &[u8]) -> usize {
    if bytes.is_empty() {
        return 1;
    }
    bytes.len().wrapping_mul(17)
        ^ usize::from(bytes[0])
        ^ usize::from(bytes[bytes.len() / 2]).wrapping_mul(3)
        ^ usize::from(bytes[bytes.len() - 1]).wrapping_mul(7)
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
    targets = benchmark_small_primitives
}
criterion_main!(benches);
