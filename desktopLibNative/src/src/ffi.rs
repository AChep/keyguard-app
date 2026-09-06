use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::panic::UnwindSafe;
use std::ptr;

pub(crate) type BiometricsVerifyCallback = Option<extern "C" fn(i32, *const c_char)>;
pub(crate) type BiometricsResultCallback =
    Option<extern "C" fn(i32, *const u8, u64, *const c_char)>;
pub(crate) type HotKeyPressedCallback = Option<unsafe extern "C" fn(i32)>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FailureLogDetail {
    Full,
    Redacted,
}

/// Copies a required C string into a Rust string.
///
/// # Safety
///
/// If `ptr` is non-null, it must point to an immutable, readable,
/// NUL-terminated byte sequence contained in one allocation and remain valid
/// for the duration of this call.
pub(crate) unsafe fn require_string(ptr: *const c_char, label: &str) -> Result<String, String> {
    let ptr = require_non_null(ptr, label)?;
    // SAFETY: The caller guarantees the `CStr::from_ptr` requirements above.
    Ok(unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned())
}

/// Converts an optional message into a C string, dropping messages that
/// cannot be represented. Pair with [optional_cstring_ptr].
pub(crate) fn optional_cstring(value: Option<&str>) -> Option<CString> {
    value.and_then(|value| CString::new(value).ok())
}

/// Borrows a pointer for a callback; null when there is no message.
pub(crate) fn optional_cstring_ptr(value: &Option<CString>) -> *const c_char {
    value.as_ref().map_or(ptr::null(), |value| value.as_ptr())
}

pub(crate) fn require_non_null<T>(ptr: *const T, label: &str) -> Result<*const T, String> {
    if ptr.is_null() {
        return Err(format!("{label} pointer was null"));
    }

    Ok(ptr)
}

pub(crate) fn bool_from_c_int(value: c_int, label: &str) -> Result<bool, String> {
    match value {
        0 => Ok(false),
        1 => Ok(true),
        _ => Err(format!("{label} must be either 0 or 1")),
    }
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
    // SAFETY: The exported freePointer contract only accepts pointers returned
    // by this library's strdup-backed keychain getter (or null, which free also
    // accepts), so the allocation belongs to the C allocator exactly once.
    unsafe {
        libc::free(ptr);
    }
}

#[cfg(test)]
mod tests {
    use super::{
        bool_from_c_int, format_failure_log, free_ptr, optional_cstring, optional_cstring_ptr,
        require_non_null, require_string, FailureLogDetail,
    };
    use std::ffi::CString;

    #[test]
    fn require_non_null_rejects_null() {
        let result = require_non_null::<u8>(std::ptr::null(), "payload");
        assert_eq!(result.unwrap_err(), "payload pointer was null");
    }

    #[test]
    fn require_string_reads_utf8_string() {
        let value = CString::new("hello").unwrap();
        // SAFETY: `value` is an immutable, readable, NUL-terminated C string
        // that remains alive for the duration of the conversion.
        let result = unsafe { require_string(value.as_ptr(), "payload") }.unwrap();
        assert_eq!(result, "hello");
    }

    #[test]
    fn c_int_boolean_conversion_accepts_only_canonical_values() {
        assert!(!bool_from_c_int(0, "value").unwrap());
        assert!(bool_from_c_int(1, "value").unwrap());
        assert_eq!(
            bool_from_c_int(-1, "value").unwrap_err(),
            "value must be either 0 or 1",
        );
    }

    #[test]
    fn optional_cstring_is_null_when_absent_or_invalid() {
        assert!(optional_cstring_ptr(&optional_cstring(None)).is_null());
        assert!(optional_cstring_ptr(&optional_cstring(Some("a\0b"))).is_null());
        assert!(!optional_cstring_ptr(&optional_cstring(Some("ok"))).is_null());
    }

    #[test]
    fn free_ptr_releases_strdup_allocation() {
        let payload = CString::new("hello").unwrap();
        // SAFETY: CString provides a valid NUL-terminated source pointer for
        // the duration of the call; strdup returns a C-allocator allocation.
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
