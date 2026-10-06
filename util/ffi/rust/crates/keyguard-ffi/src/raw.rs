//! Readers for buffers borrowed from a C caller.

#![allow(unsafe_code)]

use std::{slice, str};

use crate::BRIDGE_INVALID_ARGUMENT;

/// Borrows `length` readable bytes. An empty buffer may use a null pointer.
///
/// # Errors
///
/// Returns [`BRIDGE_INVALID_ARGUMENT`] for a null pointer with a non-zero
/// length, or a length above `isize::MAX`.
///
/// # Safety
///
/// A non-null `pointer` must be valid for `length` readable bytes for the
/// duration of the call.
pub unsafe fn bytes_from_raw<'a>(pointer: *const u8, length: usize) -> Result<&'a [u8], i64> {
    if length == 0 {
        return Ok(&[]);
    }
    if pointer.is_null() || length > isize::MAX as usize {
        return Err(BRIDGE_INVALID_ARGUMENT);
    }
    // SAFETY: Null and oversized inputs were rejected; the caller contract
    // guarantees `length` readable bytes.
    Ok(unsafe { slice::from_raw_parts(pointer, length) })
}

/// Borrows `length` writable bytes. An empty buffer may use a null pointer.
///
/// # Errors
///
/// As [`bytes_from_raw`].
///
/// # Safety
///
/// A non-null `pointer` must be valid for `length` writable, unaliased bytes
/// for the duration of the call.
pub unsafe fn bytes_from_raw_mut<'a>(pointer: *mut u8, length: usize) -> Result<&'a mut [u8], i64> {
    if length == 0 {
        return Ok(&mut []);
    }
    if pointer.is_null() || length > isize::MAX as usize {
        return Err(BRIDGE_INVALID_ARGUMENT);
    }
    // SAFETY: Null and oversized inputs were rejected; the caller contract
    // guarantees `length` writable, unaliased bytes.
    Ok(unsafe { slice::from_raw_parts_mut(pointer, length) })
}

/// Borrows `length` bytes of UTF-8. An empty string may use a null pointer.
///
/// # Errors
///
/// As [`bytes_from_raw`], and [`BRIDGE_INVALID_ARGUMENT`] for invalid UTF-8.
///
/// # Safety
///
/// As [`bytes_from_raw`].
pub unsafe fn string_from_raw<'a>(pointer: *const u8, length: usize) -> Result<&'a str, i64> {
    // SAFETY: Forwarded from this function's own contract.
    let bytes = unsafe { bytes_from_raw(pointer, length) }?;
    str::from_utf8(bytes).map_err(|_| BRIDGE_INVALID_ARGUMENT)
}

#[cfg(test)]
mod tests {
    use std::ptr;

    use super::*;

    #[test]
    fn empty_buffers_accept_a_null_pointer() {
        // SAFETY: A zero length never dereferences the pointer.
        unsafe {
            assert_eq!(bytes_from_raw(ptr::null(), 0), Ok(&[][..]));
            assert_eq!(
                bytes_from_raw_mut(ptr::null_mut(), 0).map(|it| it.len()),
                Ok(0)
            );
            assert_eq!(string_from_raw(ptr::null(), 0), Ok(""));
        }
    }

    #[test]
    fn null_or_oversized_buffers_are_invalid_arguments() {
        // SAFETY: Null and oversized inputs are rejected before any access.
        unsafe {
            assert_eq!(bytes_from_raw(ptr::null(), 1), Err(BRIDGE_INVALID_ARGUMENT));
            assert_eq!(
                bytes_from_raw_mut(ptr::null_mut(), 1).map(|it| it.len()),
                Err(BRIDGE_INVALID_ARGUMENT),
            );
            let byte = 0_u8;
            assert_eq!(
                bytes_from_raw(&byte, isize::MAX as usize + 1),
                Err(BRIDGE_INVALID_ARGUMENT),
            );
        }
    }

    #[test]
    fn strings_must_be_utf8() {
        let valid = "kéy".as_bytes();
        let invalid = [0xff_u8, 0xfe];
        // SAFETY: Both buffers are valid for their exact lengths.
        unsafe {
            assert_eq!(string_from_raw(valid.as_ptr(), valid.len()), Ok("kéy"));
            assert_eq!(
                string_from_raw(invalid.as_ptr(), invalid.len()),
                Err(BRIDGE_INVALID_ARGUMENT),
            );
        }
    }
}
