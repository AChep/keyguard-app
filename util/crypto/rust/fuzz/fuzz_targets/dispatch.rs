#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let response = keyguard_crypto_core::call(data);
    assert!(response.len() <= keyguard_crypto_core::MAX_CONTROL_ENVELOPE_BYTES);
});
