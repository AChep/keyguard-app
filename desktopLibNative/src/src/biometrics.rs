use crate::ffi::{self, BiometricsResultCallback, BiometricsVerifyCallback};
use zeroize::Zeroizing;

#[cfg_attr(target_os = "windows", path = "biometrics/windows.rs")]
#[cfg_attr(target_os = "macos", path = "biometrics/macos.rs")]
#[cfg_attr(
    not(any(target_os = "macos", target_os = "windows")),
    path = "biometrics/stub.rs"
)]
mod imp;

/// Status codes shared with the JVM side and the macOS shim. Not every
/// platform produces every variant, but the wire contract needs them all.
#[repr(i32)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[allow(dead_code)]
pub(crate) enum ChallengeStatus {
    Success = 0,
    UserCanceled = 1,
    CredentialNotFound = 2,
    SecurityDeviceLocked = 3,
    Unavailable = 4,
    UserPrefersPassword = 5,
    Unknown = 6,
}

pub(crate) struct ChallengeResult {
    pub(crate) status: ChallengeStatus,
    pub(crate) value: Zeroizing<Vec<u8>>,
    pub(crate) error: Option<String>,
}

impl ChallengeResult {
    #[cfg_attr(not(target_os = "windows"), allow(dead_code))]
    pub(crate) fn success(value: impl Into<Zeroizing<Vec<u8>>>) -> Self {
        Self {
            status: ChallengeStatus::Success,
            value: value.into(),
            error: None,
        }
    }

    pub(crate) fn failure(status: ChallengeStatus, error: impl Into<String>) -> Self {
        Self {
            status,
            value: Zeroizing::default(),
            error: Some(error.into()),
        }
    }

    /// Fallback for platforms without a protected key store.
    #[cfg_attr(target_os = "windows", allow(dead_code))]
    pub(crate) fn unavailable() -> Self {
        Self::failure(
            ChallengeStatus::Unavailable,
            "Secret wrapping is unavailable on this platform",
        )
    }
}

pub(crate) fn is_supported() -> bool {
    imp::is_supported()
}

/// Asks the platform to confirm the user's presence and reports a
/// [ChallengeStatus] code to `callback`, together with an optional error
/// message that is only valid for the duration of the callback.
pub(crate) fn verify(window_handle: i64, title: &str, callback: BiometricsVerifyCallback) {
    imp::verify(window_handle, title, callback);
}

/// Invokes a verify callback with a status and an optional message. Both
/// values are owned by this frame and stay alive for the whole callback.
pub(crate) fn report_verify_result(
    callback: BiometricsVerifyCallback,
    status: ChallengeStatus,
    error: Option<&str>,
) {
    let Some(callback) = callback else {
        return;
    };
    let error = ffi::optional_cstring(error);
    callback(status as i32, ffi::optional_cstring_ptr(&error));
}

/// Removes the platform-protected key, if any. Returns `true` when no key
/// remains afterwards.
pub(crate) fn delete_credential() -> bool {
    imp::delete_credential()
}

/// Wraps (`decrypt == false`) or unwraps (`decrypt == true`) a secret with a
/// platform-protected key and reports the outcome to `callback`. The result
/// and error pointers are only valid for the duration of the callback.
pub(crate) fn transform_secret(
    window_handle: i64,
    title: &str,
    input: &[u8],
    decrypt: bool,
    callback: BiometricsResultCallback,
) {
    let Some(callback) = callback else {
        return;
    };
    let result = imp::transform_secret(window_handle, title, input, decrypt);
    let error = ffi::optional_cstring(result.error.as_deref());
    callback(
        result.status as i32,
        result.value.as_ptr(),
        result.value.len() as u64,
        ffi::optional_cstring_ptr(&error),
    );
}
