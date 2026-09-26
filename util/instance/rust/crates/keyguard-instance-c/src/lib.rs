//! Stable C ABI for application instance ownership and activation.
//!
//! This adapter only validates raw inputs and delegates to the shared core.
//! Every exported function contains panics before they cross the C boundary.

use std::{panic::AssertUnwindSafe, slice, str};

use keyguard_instance_core::{Error, bridge};

const INVALID_ARGUMENT: i64 = Error::InvalidArgument as i64;
const INTERNAL: i64 = Error::Internal as i64;
const MAX_STRING_BYTES: usize = 65_536;

fn contained(body: impl FnOnce() -> Result<i64, i64>) -> i64 {
    bridge::clear_failure();
    let result = std::panic::catch_unwind(AssertUnwindSafe(body))
        .unwrap_or(Err(INTERNAL))
        .unwrap_or_else(std::convert::identity);
    if result < 0 && bridge::last_failure().is_none() {
        let error = if result == INVALID_ARGUMENT {
            Error::InvalidArgument
        } else {
            Error::Internal
        };
        bridge::record_failure(error.into());
    }
    result
}

/// Copies this thread's last failure as UTF-8 without a NUL terminator.
/// Returns the required byte count; copies nothing when capacity is insufficient.
/// Does not clear the diagnostic. A successful operation clears it.
///
/// # Safety
/// If capacity is nonzero, buffer must identify that many writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_instance_last_error(buffer: *mut u8, capacity: usize) -> usize {
    std::panic::catch_unwind(|| {
        let Some(failure) = bridge::last_failure() else {
            return 0;
        };
        let message = failure.to_string();
        if capacity >= message.len() && !buffer.is_null() {
            // SAFETY: The caller guarantees capacity writable bytes, and the source
            // is a separate live allocation of exactly message.len() bytes.
            unsafe { std::ptr::copy_nonoverlapping(message.as_ptr(), buffer, message.len()) };
        }
        message.len()
    })
    .unwrap_or(0)
}

/// Clears a previous diagnostic before a caller performs its own argument validation.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_instance_clear_error() {
    let _ = std::panic::catch_unwind(bridge::clear_failure);
}

/// # Safety
/// Non-empty input must contain `length` readable bytes for the duration of the call.
unsafe fn string_from_raw<'a>(pointer: *const u8, length: usize) -> Result<&'a str, i64> {
    if length > MAX_STRING_BYTES {
        return Err(INVALID_ARGUMENT);
    }
    if length == 0 {
        return Ok("");
    }
    if pointer.is_null() {
        return Err(INVALID_ARGUMENT);
    }
    // SAFETY: Null and oversized inputs were rejected; the caller guarantees readable bytes.
    let bytes = unsafe { slice::from_raw_parts(pointer, length) };
    str::from_utf8(bytes).map_err(|_| INVALID_ARGUMENT)
}

/// Returns the C ABI version.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_instance_abi_version() -> u32 {
    keyguard_instance_core::ABI_VERSION
}

/// Acquires ownership or activates the incumbent. Returns a positive handle, zero, or an error.
///
/// # Safety
/// Every pointer/length pair must identify readable UTF-8 bytes throughout the call.
/// Empty strings may use null pointers. No pointer is retained after the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_instance_acquire_or_activate(
    coordination_ptr: *const u8,
    coordination_len: usize,
    runtime_ptr: *const u8,
    runtime_len: usize,
    identity_ptr: *const u8,
    identity_len: usize,
    timeout_ms: u64,
) -> i64 {
    contained(|| {
        // SAFETY: The exported function forwards the caller's readable-buffer contracts.
        let coordination = unsafe { string_from_raw(coordination_ptr, coordination_len) }?;
        // SAFETY: The exported function forwards the caller's readable-buffer contracts.
        let runtime = unsafe { string_from_raw(runtime_ptr, runtime_len) }?;
        // SAFETY: The exported function forwards the caller's readable-buffer contracts.
        let identity = unsafe { string_from_raw(identity_ptr, identity_len) }?;
        Ok(bridge::acquire_or_activate(
            coordination,
            runtime,
            identity,
            timeout_ms,
        ))
    })
}

/// Waits for activation (1) or shutdown (0). Exactly one receiver is supported per handle.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_instance_wait_event(handle: u64) -> i64 {
    contained(|| Ok(bridge::wait_event(handle)))
}

/// Stops listening and wakes the receiver while retaining ownership.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_instance_stop(handle: u64) -> i64 {
    contained(|| Ok(bridge::stop(handle)))
}

/// Consumes a primary handle and releases ownership after stopping transport work.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_instance_close(handle: u64) -> i64 {
    contained(|| Ok(bridge::close(handle)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diagnostic_buffer_is_bounded_and_survives_size_queries() {
        bridge::record_failure(keyguard_instance_core::Failure::from(
            std::io::Error::from_raw_os_error(2),
        ));
        // SAFETY: A zero-capacity query does not access its null buffer.
        let required = unsafe { keyguard_instance_last_error(std::ptr::null_mut(), 0) };
        assert!(required > 1);
        let mut small = [42];
        assert_eq!(
            // SAFETY: small contains exactly the declared writable byte.
            unsafe { keyguard_instance_last_error(small.as_mut_ptr(), small.len()) },
            required
        );
        assert_eq!(small, [42]);
        let mut buffer = vec![0; required];
        assert_eq!(
            // SAFETY: buffer remains writable for its exact declared length.
            unsafe { keyguard_instance_last_error(buffer.as_mut_ptr(), buffer.len()) },
            required
        );
        let diagnostic = String::from_utf8(buffer).unwrap();
        assert!(diagnostic.contains("os_code=2"));
        assert_eq!(contained(|| Ok(0)), 0);
        assert_eq!(
            // SAFETY: This is another zero-capacity size query.
            unsafe { keyguard_instance_last_error(std::ptr::null_mut(), 0) },
            0
        );
    }

    #[test]
    fn rejects_invalid_string_buffers() {
        // SAFETY: These deliberately invalid null/size combinations are rejected before reading.
        unsafe {
            assert_eq!(string_from_raw(std::ptr::null(), 1), Err(INVALID_ARGUMENT));
            assert_eq!(
                string_from_raw(std::ptr::null(), MAX_STRING_BYTES + 1),
                Err(INVALID_ARGUMENT)
            );
            assert_eq!(string_from_raw(std::ptr::null(), 0), Ok(""));
        }
        let invalid_utf8 = [0xff];
        assert_eq!(
            // SAFETY: The byte array remains readable for its exact declared length.
            unsafe { string_from_raw(invalid_utf8.as_ptr(), 1) },
            Err(INVALID_ARGUMENT)
        );
    }

    #[test]
    fn contains_panics_and_rejects_missing_handles() {
        assert_eq!(contained(|| panic!("fixture")), INTERNAL);
        assert_eq!(keyguard_instance_wait_event(0), -5);
        assert_eq!(keyguard_instance_stop(0), -5);
        assert_eq!(keyguard_instance_close(0), -5);
    }
}
