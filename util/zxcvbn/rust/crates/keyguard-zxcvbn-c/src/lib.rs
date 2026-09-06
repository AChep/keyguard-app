//! Stable C ABI for Keyguard native password strength estimation.
//!
//! Each function unmarshals its raw arguments, delegates to
//! [`keyguard_zxcvbn_core`], and returns ABI v1 wire values shared with the
//! JNI adapter. Panics never cross the boundary.

#![deny(unsafe_op_in_unsafe_fn)]

use std::{mem::size_of, panic::AssertUnwindSafe, ptr, slice, str};

use keyguard_zxcvbn_core::{
    MAX_USER_INPUTS, ResultWire, abi,
    abi::{pack_bridge_error, pack_bridge_invalid_argument},
};

/// Borrowed UTF-8 string, mirroring `struct keyguard_zxcvbn_str_v1`.
///
/// The pointer may be null only when `len` is zero.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct StrView {
    /// First byte of the string, or null for an empty string.
    pub ptr: *const u8,
    /// Length of the string in UTF-8 bytes.
    pub len: usize,
}

/// Runs `body` behind the panic boundary every entry point shares.
///
/// Production builds install the hook *inside* the boundary:
/// `std::panic::set_hook`
/// "panics if called from a panicking thread", and a panic inside
/// `Once::call_once` poisons the `Once` so that every later call panics too.
/// Installed outside the boundary, one such panic would escape the
/// `extern "C"` frame and abort the process on every subsequent bridge call.
fn contained<R>(body: impl FnOnce() -> R) -> Result<R, i64> {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        // Unit tests keep Rust's default hook so a caught assertion still
        // reports its payload and source location in CI logs.
        #[cfg(not(test))]
        keyguard_zxcvbn_core::install_redacting_panic_hook();
        body()
    }))
    .map_err(|_| abi::pack_bridge_panic())
}

fn unwrap(result: Result<Result<i64, i64>, i64>) -> i64 {
    match result.and_then(std::convert::identity) {
        Ok(value) | Err(value) => value,
    }
}

/// # Safety
///
/// A non-empty string must be represented by a non-null pointer to its
/// declared number of readable UTF-8 bytes, valid for the duration of the
/// call.
unsafe fn string_from_raw<'a>(pointer: *const u8, length: usize) -> Result<&'a str, i64> {
    if length == 0 {
        return Ok("");
    }
    if pointer.is_null() || length > isize::MAX as usize {
        return Err(pack_bridge_invalid_argument());
    }
    // SAFETY: Null and oversized inputs were rejected; the forwarded FFI
    // contract guarantees `length` readable bytes for the complete call.
    let bytes = unsafe { slice::from_raw_parts(pointer, length) };
    str::from_utf8(bytes).map_err(|_| pack_bridge_invalid_argument())
}

/// # Safety
///
/// A non-empty user-input array must be a non-null pointer to `length`
/// readable [`StrView`] records, each describing readable UTF-8 bytes, all
/// valid for the duration of the call.
unsafe fn user_inputs_from_raw<'a>(
    inputs: *const StrView,
    length: usize,
) -> Result<Vec<&'a str>, i64> {
    if length == 0 {
        return Ok(Vec::new());
    }
    // The count is checked before any element is read, so an absurd length
    // never causes an allocation or a dereference.
    if inputs.is_null() || length > MAX_USER_INPUTS {
        return Err(pack_bridge_invalid_argument());
    }
    // SAFETY: Null and oversized inputs were rejected; the forwarded FFI
    // contract guarantees `length` readable records for the complete call.
    let views = unsafe { slice::from_raw_parts(inputs, length) };
    views
        .iter()
        .map(|view| {
            // SAFETY: The caller contract covers every record's own bytes for
            // the duration of the call.
            unsafe { string_from_raw(view.ptr, view.len) }
        })
        .collect()
}

/// # Safety
///
/// `result` must be non-null and valid for reading the size it declares and
/// for writing an ABI v1 result record.
unsafe fn result_size_from_raw(result: *mut ResultWire) -> Result<(), i64> {
    if result.is_null() {
        return Err(pack_bridge_invalid_argument());
    }
    // SAFETY: Null was rejected and the caller guarantees at least the
    // leading size field is readable for the duration of the call.
    let available = unsafe { ptr::read_unaligned(result.cast::<u32>()) };
    if available < size_of::<ResultWire>() as u32 {
        return Err(pack_bridge_invalid_argument());
    }
    Ok(())
}

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_zxcvbn_abi_version() -> u32 {
    contained(|| keyguard_zxcvbn_core::ABI_VERSION).unwrap_or(0)
}

/// Estimates a password's strength into a caller-owned result record.
///
/// Returns zero after writing `result`, or a packed failure that leaves
/// `result` unchanged.
///
/// # Safety
///
/// `password_ptr`/`password_len` must describe readable UTF-8 bytes valid for
/// the duration of the call; the pointer may be null when the length is zero.
/// `user_inputs`/`user_inputs_len` follow the same contract per record and
/// the pointer may be null when the length is zero. `result` must be non-null,
/// have its `size` field initialized to at least the ABI v1 record size, and
/// be valid for writing that record.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_zxcvbn_estimate(
    password_ptr: *const u8,
    password_len: usize,
    user_inputs: *const StrView,
    user_inputs_len: usize,
    result: *mut ResultWire,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: The caller contract is forwarded from this function's own.
        unsafe { result_size_from_raw(result) }?;
        // SAFETY: The caller contract is forwarded from this function's own.
        let inputs = unsafe { user_inputs_from_raw(user_inputs, user_inputs_len) }?;
        // SAFETY: The caller contract is forwarded from this function's own.
        let password = unsafe { string_from_raw(password_ptr, password_len) }?;
        let wire = keyguard_zxcvbn_core::estimate(password, &inputs).map_err(pack_bridge_error)?;
        // SAFETY: The declared size covers the complete trivially-copyable
        // wire record. An unaligned write accepts any caller storage
        // alignment.
        unsafe { ptr::write_unaligned(result, wire) };
        Ok(0)
    }))
}

#[cfg(test)]
mod tests {
    use keyguard_zxcvbn_core::{BridgeError, RESULT_WIRE_VERSION, WARNING_NONE};

    use super::*;

    fn empty_result() -> ResultWire {
        ResultWire {
            size: size_of::<ResultWire>() as u32,
            version: 0,
            score: 0,
            warning: 0,
            suggestions: 0,
            reserved0: 0,
            guesses: 0,
            guesses_log10: 0.0,
            online_throttling_100_per_hour: 0.0,
            online_no_throttling_10_per_second: 0.0,
            offline_slow_hashing_1e4_per_second: 0.0,
            offline_fast_hashing_1e10_per_second: 0.0,
            reserved: [0; 2],
        }
    }

    #[test]
    fn the_abi_version_is_pinned_to_one() {
        assert_eq!(keyguard_zxcvbn_abi_version(), 1);
    }

    #[test]
    fn a_null_result_record_is_an_invalid_argument() {
        let password = "password";
        // SAFETY: The password pointer refers to a live UTF-8 buffer and the
        // result pointer is deliberately null.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                ptr::null(),
                0,
                ptr::null_mut(),
            )
        };
        assert_eq!(packed, pack_bridge_invalid_argument());
    }

    #[test]
    fn an_undersized_result_record_is_an_invalid_argument() {
        let password = "password";
        let mut result = empty_result();
        result.size = size_of::<ResultWire>() as u32 - 1;
        // SAFETY: Both pointers refer to live initialized storage.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                ptr::null(),
                0,
                &mut result,
            )
        };
        assert_eq!(packed, pack_bridge_invalid_argument());
        assert_eq!(result.version, 0, "an undersized record must be untouched");
    }

    #[test]
    fn a_larger_result_record_is_accepted_and_stamped_with_the_written_size() {
        let password = "password";
        let mut result = empty_result();
        result.size = size_of::<ResultWire>() as u32 + 8;
        // SAFETY: Both pointers refer to live initialized storage.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                ptr::null(),
                0,
                &mut result,
            )
        };
        assert_eq!(packed, 0);
        assert_eq!(result.size, size_of::<ResultWire>() as u32);
    }

    #[test]
    fn invalid_utf8_is_an_invalid_argument() {
        let password = [0xff_u8, 0xfe];
        let mut result = empty_result();
        // SAFETY: Both pointers refer to live initialized storage.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                ptr::null(),
                0,
                &mut result,
            )
        };
        assert_eq!(packed, pack_bridge_invalid_argument());
    }

    #[test]
    fn a_null_non_empty_password_is_an_invalid_argument() {
        let mut result = empty_result();
        // SAFETY: The password pointer is deliberately null with a non-zero
        // length; the result pointer refers to live initialized storage.
        let packed =
            unsafe { keyguard_zxcvbn_estimate(ptr::null(), 8, ptr::null(), 0, &mut result) };
        assert_eq!(packed, pack_bridge_invalid_argument());
    }

    #[test]
    fn a_null_non_empty_user_input_array_is_an_invalid_argument() {
        let password = "password";
        let mut result = empty_result();
        // SAFETY: The user-input pointer is deliberately null with a non-zero
        // length; every other pointer refers to live storage.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                ptr::null(),
                1,
                &mut result,
            )
        };
        assert_eq!(packed, pack_bridge_invalid_argument());
    }

    #[test]
    fn an_empty_password_is_accepted_with_a_null_pointer() {
        let mut result = empty_result();
        // SAFETY: The password pointer is null with a zero length, which the
        // ABI permits; the result pointer refers to live storage.
        let packed =
            unsafe { keyguard_zxcvbn_estimate(ptr::null(), 0, ptr::null(), 0, &mut result) };
        assert_eq!(packed, 0);
        assert_eq!(result.score, 0);
        assert_eq!(result.guesses, 0);
        assert_eq!(result.guesses_log10, f64::NEG_INFINITY);
    }

    #[test]
    fn a_top_ten_password_round_trips_through_the_c_abi() {
        let password = "password";
        let mut result = empty_result();
        // SAFETY: Both pointers refer to live initialized storage.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                ptr::null(),
                0,
                &mut result,
            )
        };
        assert_eq!(packed, 0);
        assert_eq!(result.size, size_of::<ResultWire>() as u32);
        assert_eq!(result.version, RESULT_WIRE_VERSION);
        assert_eq!(result.score, 0);
        assert_eq!(result.warning, 4);
        assert_ne!(result.warning, WARNING_NONE);
        assert!(result.guesses > 0);
        assert_eq!(result.reserved, [0; 2]);
    }

    #[test]
    fn user_inputs_travel_through_the_c_abi_and_lower_the_guess_count() {
        let password = "keyguardvault2026";
        let input = "keyguardvault";
        let views = [StrView {
            ptr: input.as_ptr(),
            len: input.len(),
        }];

        let mut biased = empty_result();
        // SAFETY: Every pointer refers to live initialized storage that
        // outlives the call.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                views.as_ptr(),
                views.len(),
                &mut biased,
            )
        };
        assert_eq!(packed, 0);

        let mut baseline = empty_result();
        // SAFETY: Both pointers refer to live initialized storage.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                ptr::null(),
                0,
                &mut baseline,
            )
        };
        assert_eq!(packed, 0);
        assert!(biased.guesses < baseline.guesses);
    }

    #[test]
    fn too_many_user_inputs_are_rejected_before_any_element_is_read() {
        let password = "password";
        let input = "keyguard";
        let views = vec![
            StrView {
                ptr: input.as_ptr(),
                len: input.len(),
            };
            MAX_USER_INPUTS + 1
        ];
        let mut result = empty_result();
        // SAFETY: Every pointer refers to live initialized storage.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                views.as_ptr(),
                views.len(),
                &mut result,
            )
        };
        assert_eq!(packed, pack_bridge_invalid_argument());
    }

    #[test]
    fn an_oversized_password_is_reported_as_input_too_long() {
        let password = "a".repeat(keyguard_zxcvbn_core::MAX_PASSWORD_BYTES + 1);
        let mut result = empty_result();
        // SAFETY: Both pointers refer to live initialized storage.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(
                password.as_ptr(),
                password.len(),
                ptr::null(),
                0,
                &mut result,
            )
        };
        assert_eq!(packed, pack_bridge_error(BridgeError::InputTooLong));
        assert_eq!(packed, abi::pack_bridge_input_too_long());
    }

    #[test]
    fn the_result_record_survives_an_unaligned_caller_buffer() {
        let password = "password";
        let mut storage = [0_u8; size_of::<ResultWire>() + 8];
        // Offset by one byte so the record is misaligned for every field.
        let record = storage[1..].as_mut_ptr().cast::<ResultWire>();
        // SAFETY: The storage outlives the call and has room for the record at
        // the offset; the size field is written unaligned for the same reason.
        unsafe { ptr::write_unaligned(record.cast::<u32>(), size_of::<ResultWire>() as u32) };
        // SAFETY: Both pointers refer to live storage; the record pointer is
        // deliberately unaligned, which the ABI's unaligned access supports.
        let packed = unsafe {
            keyguard_zxcvbn_estimate(password.as_ptr(), password.len(), ptr::null(), 0, record)
        };
        assert_eq!(packed, 0);
        // SAFETY: The call above wrote a complete record at this address.
        let wire = unsafe { ptr::read_unaligned(record) };
        assert_eq!(wire.version, RESULT_WIRE_VERSION);
        assert_eq!(wire.warning, 4);
    }

    #[test]
    fn a_contained_panic_uses_the_bridge_panic_failure() {
        assert_eq!(
            unwrap(contained(|| -> Result<i64, i64> { panic!("boom") })),
            abi::pack_bridge_panic()
        );
    }
}
