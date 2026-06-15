use crate::ffi::BiometricsVerifyCallback;
use crate::platform;
use std::ffi::c_char;

pub(crate) fn is_supported() -> bool {
    platform::biometrics::is_supported()
}

pub(crate) fn verify(title: *const c_char, callback: BiometricsVerifyCallback) {
    platform::biometrics::verify(title, callback);
}
