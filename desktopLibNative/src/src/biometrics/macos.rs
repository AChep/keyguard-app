use super::{report_verify_result, ChallengeResult, ChallengeStatus};
use crate::ffi::BiometricsVerifyCallback;
use std::ffi::{c_char, CString};

unsafe extern "C" {
    fn kg_biometrics_is_supported() -> bool;
    fn kg_biometrics_verify(title: *const c_char, callback: BiometricsVerifyCallback);
}

pub(crate) fn is_supported() -> bool {
    // SAFETY: The Objective-C shim takes no arguments, returns a C-compatible
    // bool by value, and transfers no ownership.
    unsafe { kg_biometrics_is_supported() }
}

pub(crate) fn verify(_window_handle: i64, title: &str, callback: BiometricsVerifyCallback) {
    let Ok(title) = CString::new(title) else {
        report_verify_result(
            callback,
            ChallengeStatus::Unknown,
            Some("Prompt title contained an interior NUL byte"),
        );
        return;
    };
    // SAFETY: `title` is a live NUL-terminated string for the duration of the
    // call and the exported FFI contract keeps any callback alive until
    // asynchronous completion. The shim copies title before returning and
    // invokes callback with the declared C ABI without taking Rust ownership
    // of either value.
    unsafe {
        kg_biometrics_verify(title.as_ptr(), callback);
    }
}

pub(crate) fn delete_credential() -> bool {
    false
}

pub(crate) fn transform_secret(
    _window_handle: i64,
    _title: &str,
    _input: &[u8],
    _decrypt: bool,
) -> ChallengeResult {
    ChallengeResult::unavailable()
}
