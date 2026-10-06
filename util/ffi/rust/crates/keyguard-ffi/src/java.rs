//! JNI readers and the JNI side of the operation protocol.

#![allow(unsafe_code)]

use jni::{
    JNIEnv,
    objects::{JByteArray, JString},
    sys::jbyteArray,
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
