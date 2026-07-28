use crate::ffi::BiometricsVerifyCallback;
use std::ffi::c_char;

unsafe extern "C" {
    fn kg_biometrics_is_supported() -> bool;
    fn kg_biometrics_verify(title: *const c_char, callback: BiometricsVerifyCallback);
}

pub(crate) fn is_supported() -> bool {
    // SAFETY: The Objective-C shim takes no arguments, returns a C-compatible
    // bool by value, and transfers no ownership.
    unsafe { kg_biometrics_is_supported() }
}

pub(crate) fn verify(title: *const c_char, callback: BiometricsVerifyCallback) {
    // SAFETY: The exported FFI contract supplies title as a readable,
    // NUL-terminated string and keeps any callback alive until asynchronous
    // completion. The shim copies title before returning and invokes callback
    // with the declared C ABI without taking Rust ownership of either value.
    unsafe {
        kg_biometrics_verify(title, callback);
    }
}
