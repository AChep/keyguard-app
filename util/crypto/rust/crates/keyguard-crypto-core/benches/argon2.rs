//! Fixed-parameter Argon2 benchmark used for release regression tracking.

use std::{hint::black_box, time::Duration};

use criterion::{Criterion, criterion_group, criterion_main};
use keyguard_crypto_core::{
    PROTOCOL_VERSION, call,
    protocol::{Argon2Mode, Argon2Request, NativeRequest, native_request},
};
use prost::Message;

fn benchmark_argon2(c: &mut Criterion) {
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_request::Operation::Argon2(Argon2Request {
            mode: Argon2Mode::Id as i32,
            seed: b"benchmark-password".to_vec(),
            salt: b"benchmark-salt".to_vec(),
            iterations: 3,
            memory_kib: 64 * 1024,
            parallelism: 1,
            length: 32,
            version: 0,
            secret: None,
            associated_data: None,
        })),
    }
    .encode_to_vec();

    c.bench_function("argon2id_m64m_t3_p1", |bencher| {
        bencher.iter(|| black_box(call(black_box(&request))));
    });
}

fn criterion_config() -> Criterion {
    Criterion::default()
        .sample_size(10)
        .warm_up_time(Duration::from_millis(500))
        .measurement_time(Duration::from_secs(2))
        .without_plots()
}

criterion_group! {
    name = benches;
    config = criterion_config();
    targets = benchmark_argon2
}
criterion_main!(benches);
