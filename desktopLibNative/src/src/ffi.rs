use std::ffi::c_char;
use std::ffi::c_void;
use std::panic::UnwindSafe;

pub(crate) type BiometricsVerifyCallback = Option<unsafe extern "C" fn(bool, *const c_char)>;
pub(crate) type HotKeyPressedCallback = Option<unsafe extern "C" fn(i32)>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FailureLogDetail {
    Full,
    Redacted,
}

pub(crate) fn require_string(ptr: *const c_char, label: &str) -> Result<String, String> {
    use std::ffi::CStr;

    let ptr = require_non_null(ptr, label)?;
    Ok(unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned())
}

pub(crate) fn require_non_null<T>(ptr: *const T, label: &str) -> Result<*const T, String> {
    if ptr.is_null() {
        return Err(format!("{label} pointer was null"));
    }

    Ok(ptr)
}

pub(crate) fn with_ffi_boundary<T>(
    name: &str,
    default: T,
    block: impl FnOnce() -> Result<T, String> + UnwindSafe,
) -> T {
    with_ffi_boundary_with_detail(name, default, FailureLogDetail::Full, block)
}

pub(crate) fn with_redacted_ffi_boundary<T>(
    name: &str,
    default: T,
    block: impl FnOnce() -> Result<T, String> + UnwindSafe,
) -> T {
    with_ffi_boundary_with_detail(name, default, FailureLogDetail::Redacted, block)
}

fn with_ffi_boundary_with_detail<T>(
    name: &str,
    default: T,
    detail: FailureLogDetail,
    block: impl FnOnce() -> Result<T, String> + UnwindSafe,
) -> T {
    match std::panic::catch_unwind(block) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            eprintln!("{}", format_failure_log(name, &message, detail));
            default
        }
        Err(_) => {
            eprintln!("keyguard-lib::{name} panicked");
            default
        }
    }
}

fn format_failure_log(name: &str, message: &str, detail: FailureLogDetail) -> String {
    match detail {
        FailureLogDetail::Full => format!("keyguard-lib::{name} failed: {message}"),
        FailureLogDetail::Redacted => format!("keyguard-lib::{name} failed"),
    }
}

pub(crate) fn free_ptr(ptr: *mut c_void) {
    unsafe {
        libc::free(ptr);
    }
}

#[cfg(test)]
mod tests {
    use super::{format_failure_log, free_ptr, require_non_null, require_string, FailureLogDetail};
    use std::ffi::CString;

    #[test]
    fn require_non_null_rejects_null() {
        let result = require_non_null::<u8>(std::ptr::null(), "payload");
        assert_eq!(result.unwrap_err(), "payload pointer was null");
    }

    #[test]
    fn require_string_reads_utf8_string() {
        let value = CString::new("hello").unwrap();
        let result = require_string(value.as_ptr(), "payload").unwrap();
        assert_eq!(result, "hello");
    }

    #[test]
    fn free_ptr_releases_strdup_allocation() {
        let payload = CString::new("hello").unwrap();
        let duplicated = unsafe { libc::strdup(payload.as_ptr()) };
        assert!(!duplicated.is_null());

        free_ptr(duplicated.cast());
    }

    #[test]
    fn format_failure_log_includes_message_by_default() {
        let formatted = format_failure_log("autoType", "secret payload", FailureLogDetail::Full);
        assert_eq!(formatted, "keyguard-lib::autoType failed: secret payload");
    }

    #[test]
    fn format_failure_log_redacts_sensitive_messages() {
        let formatted =
            format_failure_log("autoType", "secret payload", FailureLogDetail::Redacted);
        assert_eq!(formatted, "keyguard-lib::autoType failed");
        assert!(!formatted.contains("secret payload"));
    }
}
