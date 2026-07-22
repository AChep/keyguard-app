use crate::ffi::BiometricsVerifyCallback;
use std::ffi::c_char;
use std::ptr;

pub(crate) fn is_supported() -> bool {
    false
}

pub(crate) fn verify(_title: *const c_char, callback: BiometricsVerifyCallback) {
    if let Some(callback) = callback {
        // SAFETY: A non-null callback came from the exported biometrics FFI
        // contract and is invoked synchronously with its declared C ABI. The
        // callback ABI explicitly permits a null error pointer.
        unsafe {
            callback(false, ptr::null());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::is_supported;

    #[test]
    fn returns_unsupported_by_default() {
        assert!(!is_supported());
    }
}
