use crate::ffi::BiometricsVerifyCallback;
use std::ffi::c_char;

unsafe extern "C" {
    fn kg_biometrics_is_supported() -> bool;
    fn kg_biometrics_verify(title: *const c_char, callback: BiometricsVerifyCallback);
}

pub(crate) fn is_supported() -> bool {
    unsafe { kg_biometrics_is_supported() }
}

pub(crate) fn verify(title: *const c_char, callback: BiometricsVerifyCallback) {
    unsafe {
        kg_biometrics_verify(title, callback);
    }
}
