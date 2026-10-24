//! JNI readers, byte-array views and the JNI side of the operation protocol.

#![allow(unsafe_code)]

use jni::{
    JNIEnv,
    objects::{JByteArray, JString},
    sys::{jbyte, jbyteArray, jint},
};
use zeroize::Zeroizing;

use crate::{BRIDGE_INVALID_ARGUMENT, OperationLimits, OperationStatus, PanicHook, contained};

/// Copies a `java.lang.String` into a zeroized owned string.
///
/// `GetStringRegion` is used rather than `GetStringUTFChars` so the copy is
/// ours from the start: the JVM never hands back a buffer this bridge would
/// have to release, and the intermediate UTF-16 buffer is wiped on drop
/// because a string crossing a bridge may be a password.
///
/// # Errors
///
/// Returns [`BRIDGE_INVALID_ARGUMENT`] for a null reference, a Java exception
/// raised while copying, or invalid UTF-16.
pub fn java_string(
    environment: &JNIEnv<'_>,
    value: &JString<'_>,
) -> Result<Zeroizing<String>, i64> {
    if value.is_null() {
        return Err(BRIDGE_INVALID_ARGUMENT);
    }
    let raw_environment = environment.get_raw();
    // SAFETY: `JNIEnv` owns a valid JNI function table for this native call.
    let functions = unsafe { &**raw_environment };
    let get_length = functions.GetStringLength.ok_or(BRIDGE_INVALID_ARGUMENT)?;
    let get_region = functions.GetStringRegion.ok_or(BRIDGE_INVALID_ARGUMENT)?;
    // SAFETY: Null was rejected and JNI export signatures guarantee that
    // `value` is a live local java.lang.String reference.
    let length = unsafe { get_length(raw_environment, value.as_raw()) };
    let length = usize::try_from(length).map_err(|_| BRIDGE_INVALID_ARGUMENT)?;
    let mut utf16 = Zeroizing::new(vec![0_u16; length]);
    if length != 0 {
        let length = i32::try_from(length).map_err(|_| BRIDGE_INVALID_ARGUMENT)?;
        // SAFETY: The requested region is the string's exact UTF-16 extent and
        // the output buffer has matching writable capacity.
        unsafe {
            get_region(
                raw_environment,
                value.as_raw(),
                0,
                length,
                utf16.as_mut_ptr(),
            );
        }
        if environment.exception_check().unwrap_or(true) {
            // The JNI spec permits only a short list of functions while an
            // exception is pending — "the native code must first clear the
            // exception before making other JNI calls" — and the calls that
            // build a bridge's return value are not on it. Leaving the
            // exception pending aborts the VM under `-Xcheck:jni`. Bridges
            // report stable packed codes rather than Java exceptions, so
            // discarding it and returning invalid-argument is the contract.
            let _ = environment.exception_clear();
            return Err(BRIDGE_INVALID_ARGUMENT);
        }
    }
    String::from_utf16(&utf16)
        .map(Zeroizing::new)
        .map_err(|_| BRIDGE_INVALID_ARGUMENT)
}

/// Checks `array[offset..offset + length]`, which must not be null, and
/// returns its length. An empty range at the end of the array is accepted.
///
/// # Errors
///
/// Returns [`BRIDGE_INVALID_ARGUMENT`] for a null array, a negative offset or
/// length, or a range that overflows or ends past the array.
pub fn java_byte_range(
    environment: &JNIEnv<'_>,
    array: &JByteArray<'_>,
    offset: jint,
    length: jint,
) -> Result<usize, i64> {
    // Also rejects a null array, without calling into the JVM.
    let array_length = environment
        .get_array_length(array)
        .map_err(|_| BRIDGE_INVALID_ARGUMENT)?;
    checked_range(array_length, offset, length)
}

/// The length of `offset..offset + length`, if it lies within `array_length`.
fn checked_range(array_length: jint, offset: jint, length: jint) -> Result<usize, i64> {
    let (Ok(array_length), Ok(offset), Ok(length)) = (
        usize::try_from(array_length),
        usize::try_from(offset),
        usize::try_from(length),
    ) else {
        return Err(BRIDGE_INVALID_ARGUMENT);
    };
    offset
        .checked_add(length)
        .filter(|end| *end <= array_length)
        .map(|_| length)
        .ok_or(BRIDGE_INVALID_ARGUMENT)
}

/// Views bytes as the `jbyte`s JNI array regions take.
#[must_use]
pub fn jbytes(bytes: &[u8]) -> &[jbyte] {
    // SAFETY: jbyte and u8 share size, alignment and layout; the cast only changes
    // signedness, and the slice covers the same extent.
    unsafe { std::slice::from_raw_parts(bytes.as_ptr().cast::<jbyte>(), bytes.len()) }
}

/// Views bytes as the mutable `jbyte`s JNI array regions fill.
pub fn jbytes_mut(bytes: &mut [u8]) -> &mut [jbyte] {
    // SAFETY: As in `jbytes`, and the mutable borrow is unique.
    unsafe { std::slice::from_raw_parts_mut(bytes.as_mut_ptr().cast::<jbyte>(), bytes.len()) }
}

/// Executes a request copied from a Java byte array and returns the response
/// as a new one, or null when it cannot be allocated.
pub fn execute_operation<E: OperationStatus>(
    hook: PanicHook,
    limits: OperationLimits,
    environment: &JNIEnv<'_>,
    request: &JByteArray<'_>,
    execute: impl FnOnce(&[u8]) -> Vec<u8>,
) -> jbyteArray {
    let response = contained(hook, || {
        // Also rejects a null array.
        let Ok(length) = environment.get_array_length(request) else {
            return vec![E::INVALID_ARGUMENT.code()];
        };
        if !usize::try_from(length).is_ok_and(|length| limits.accepts_request(length)) {
            return vec![E::INVALID_ARGUMENT.code()];
        }
        match environment.convert_byte_array(request) {
            Ok(bytes) => execute(&Zeroizing::new(bytes)),
            Err(_) => vec![E::INTERNAL.code()],
        }
    })
    .unwrap_or_else(|_| vec![E::INTERNAL.code()]);
    let response = Zeroizing::new(response);
    contained(hook, || environment.byte_array_from_slice(&response))
        .ok()
        .and_then(Result::ok)
        .map_or(std::ptr::null_mut(), JByteArray::into_raw)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranges_must_lie_within_the_array() {
        assert_eq!(checked_range(4, 4, 0), Ok(0));
        assert_eq!(checked_range(4, 0, 4), Ok(4));
        assert_eq!(checked_range(4, 1, 2), Ok(2));
        assert_eq!(checked_range(0, 0, 0), Ok(0));
        assert_eq!(checked_range(4, 3, 2), Err(BRIDGE_INVALID_ARGUMENT));
        assert_eq!(checked_range(4, 5, 0), Err(BRIDGE_INVALID_ARGUMENT));
        assert_eq!(checked_range(4, -1, 1), Err(BRIDGE_INVALID_ARGUMENT));
        assert_eq!(checked_range(4, 0, -1), Err(BRIDGE_INVALID_ARGUMENT));
        assert_eq!(
            checked_range(jint::MAX, jint::MAX, jint::MAX),
            Err(BRIDGE_INVALID_ARGUMENT)
        );
    }

    #[test]
    fn signed_views_keep_every_byte() {
        let mut bytes = [0_u8, 1, 127, 128, 255];
        assert_eq!(jbytes(&bytes), &[0, 1, 127, -128, -1]);
        jbytes_mut(&mut bytes)[0] = -2;
        assert_eq!(bytes[0], 254);
    }
}
