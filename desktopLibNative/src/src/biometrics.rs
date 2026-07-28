use crate::ffi::BiometricsVerifyCallback;
use std::ffi::c_char;

#[cfg_attr(target_os = "macos", path = "biometrics/macos.rs")]
#[cfg_attr(not(target_os = "macos"), path = "biometrics/stub.rs")]
mod imp;

pub(crate) fn is_supported() -> bool {
    imp::is_supported()
}

pub(crate) fn verify(title: *const c_char, callback: BiometricsVerifyCallback) {
    imp::verify(title, callback);
}
