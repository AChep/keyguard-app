//! Stable C ABI with bounded borrowed input and caller-owned diagnostic buffers.
use keyguard_ffi::{PanicHook, string_from_raw};
use keyguard_launch_at_login_core as core;

/// Tests keep the default panic hook so CI logs show assertion details.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Returns the stable bridge version.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_launch_at_login_abi_version() -> u32 {
    core::ABI_VERSION
}

/// Executes an operation with a UTF-8 request. An empty request may use a null pointer.
///
/// # Safety
/// A non-null pointer must identify length readable bytes throughout this call.
/// The pointer is never retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_launch_at_login_execute(
    operation: i32,
    pointer: *const u8,
    length: usize,
) -> i32 {
    if length > core::MAX_REQUEST {
        return core::invalid_argument();
    }
    // SAFETY: Forwarded from this function's contract; the view is dropped before returning.
    match unsafe { string_from_raw(pointer, length) } {
        Ok(request) => core::execute(operation, request),
        Err(_) => core::invalid_argument(),
    }
}

/// Returns the required UTF-8 diagnostic length and copies only if capacity suffices.
///
/// # Safety
/// A non-null buffer must identify capacity writable bytes. Null is a size query.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_launch_at_login_last_error(
    buffer: *mut u8,
    capacity: usize,
) -> usize {
    keyguard_ffi::contained(PANIC_HOOK, || {
        let Some(message) = core::last_error() else {
            return 0;
        };
        if !buffer.is_null() && capacity >= message.len() {
            // SAFETY: The caller guarantees sufficient capacity in a distinct allocation.
            unsafe { std::ptr::copy_nonoverlapping(message.as_ptr(), buffer, message.len()) };
        }
        message.len()
    })
    .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_invalid_buffers_and_preserves_size_query_diagnostic() {
        assert_eq!(
            // SAFETY: These null inputs are rejected before dereferencing.
            unsafe { keyguard_launch_at_login_execute(0, std::ptr::null(), 1) },
            -1
        );
        // SAFETY: Null is an allowed size query.
        let size = unsafe { keyguard_launch_at_login_last_error(std::ptr::null_mut(), 0) };
        assert!(size > 0);
        let mut small = [42u8];
        assert_eq!(
            // SAFETY: The supplied writable array has exactly capacity bytes.
            unsafe { keyguard_launch_at_login_last_error(small.as_mut_ptr(), 1) },
            size
        );
        assert_eq!(small, [42]);
    }
    #[test]
    fn empty_request_reaches_login_detection_but_not_registration() {
        // SAFETY: A zero length never dereferences the null pointer.
        unsafe {
            assert_eq!(keyguard_launch_at_login_execute(5, std::ptr::null(), 0), 0);
            assert_eq!(keyguard_launch_at_login_execute(0, std::ptr::null(), 0), -1);
        }
    }
}
