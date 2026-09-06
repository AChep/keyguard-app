use super::{report_verify_result, ChallengeResult, ChallengeStatus};
use crate::ffi::BiometricsVerifyCallback;

pub(crate) fn is_supported() -> bool {
    false
}

pub(crate) fn verify(_window_handle: i64, _title: &str, callback: BiometricsVerifyCallback) {
    report_verify_result(
        callback,
        ChallengeStatus::Unavailable,
        Some("Biometrics are unavailable on this platform"),
    );
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
#[cfg(test)]
mod tests {
    use super::is_supported;

    #[test]
    fn returns_unsupported_by_default() {
        assert!(!is_supported());
    }
}
