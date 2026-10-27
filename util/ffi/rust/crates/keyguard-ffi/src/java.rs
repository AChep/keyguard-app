//! JNI readers, byte-array views and the JNI side of the operation protocol.

#![allow(unsafe_code)]

use jni::{
    Env, EnvUnowned, Outcome,
    objects::{JByteArray, JString},
    sys::{jbyte, jbyteArray, jint},
};
use zeroize::Zeroizing;

use crate::{BRIDGE_INVALID_ARGUMENT, OperationLimits, OperationStatus, PanicHook, contained};

/// Runs `body` with the environment of the current native method call.
///
/// Call it inside [`contained`]: neither acquiring the environment nor `body`
/// catches a panic, so one unwinds to that boundary rather than becoming a
/// Java exception.
pub fn with_java_env<'local, R>(
    environment: &mut EnvUnowned<'local>,
    body: impl FnOnce(&mut Env<'local>) -> R,
) -> R {
    let outcome = environment
        .with_env_no_catch(|environment| Ok::<_, jni::errors::Error>(body(environment)))
        .into_outcome();
    match outcome {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => {
            unreachable!("the body cannot fail, and panics are not caught")
        }
    }
}

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
pub fn java_string(environment: &Env<'_>, value: &JString<'_>) -> Result<Zeroizing<String>, i64> {
    let length = string_length(environment, value)?;
    copy_string(environment, value, length)
}

/// UTF-16 units the bounded reader validates per JNI call.
const SCAN_CHUNK_UNITS: usize = 256;

/// Copies a `java.lang.String` only when it fits `max_utf8_bytes`.
///
/// Returns `None` for a well-formed string over the limit. Every well-formed
/// UTF-16 unit encodes to at least one UTF-8 byte, so a string with more
/// units than the limit is too long or malformed. It is validated in
/// fixed-size chunks and never copied whole.
///
/// # Errors
///
/// As [`java_string`]. A malformed string is an error even when it is also
/// too long.
pub fn java_string_within(
    environment: &Env<'_>,
    value: &JString<'_>,
    max_utf8_bytes: usize,
) -> Result<Option<Zeroizing<String>>, i64> {
    let length = string_length(environment, value)?;
    if length <= max_utf8_bytes {
        let string = copy_string(environment, value, length)?;
        return Ok((string.len() <= max_utf8_bytes).then_some(string));
    }
    let mut chunk = Zeroizing::new([0_u16; SCAN_CHUNK_UNITS]);
    let mut pending_high = false;
    let mut start = 0;
    while start < length {
        let units = &mut chunk[..SCAN_CHUNK_UNITS.min(length - start)];
        string_region(environment, value, start, units)?;
        pending_high = scan_utf16(pending_high, units)?;
        start += units.len();
    }
    if pending_high {
        Err(BRIDGE_INVALID_ARGUMENT)
    } else {
        Ok(None)
    }
}

/// Validates `units` as the continuation of a UTF-16 sequence and returns
/// whether it ends between the halves of a surrogate pair.
fn scan_utf16(mut pending_high: bool, units: &[u16]) -> Result<bool, i64> {
    for &unit in units {
        // A low surrogate must follow a high one, and only a low one may.
        if pending_high != (0xDC00..=0xDFFF).contains(&unit) {
            return Err(BRIDGE_INVALID_ARGUMENT);
        }
        pending_high = (0xD800..=0xDBFF).contains(&unit);
    }
    Ok(pending_high)
}

/// Returns the UTF-16 length of a `java.lang.String`, rejecting null.
fn string_length(environment: &Env<'_>, value: &JString<'_>) -> Result<usize, i64> {
    if value.is_null() {
        return Err(BRIDGE_INVALID_ARGUMENT);
    }
    let raw_environment = environment.get_raw();
    // SAFETY: `Env` owns a valid JNI function table for this native call, and
    // every JNI version provides the 1.1 functions.
    let get_length = unsafe { (**raw_environment).v1_1.GetStringLength };
    // SAFETY: Null was rejected and JNI export signatures guarantee that
    // `value` is a live local java.lang.String reference.
    let length = unsafe { get_length(raw_environment, value.as_raw()) };
    usize::try_from(length).map_err(|_| BRIDGE_INVALID_ARGUMENT)
}

/// Copies and decodes all `length` UTF-16 units of a string that
/// [`string_length`] accepted.
fn copy_string(
    environment: &Env<'_>,
    value: &JString<'_>,
    length: usize,
) -> Result<Zeroizing<String>, i64> {
    let mut utf16 = Zeroizing::new(vec![0_u16; length]);
    string_region(environment, value, 0, &mut utf16)?;
    String::from_utf16(&utf16)
        .map(Zeroizing::new)
        .map_err(|_| BRIDGE_INVALID_ARGUMENT)
}

/// Copies `units.len()` UTF-16 units from `start` of a string that
/// [`string_length`] accepted.
fn string_region(
    environment: &Env<'_>,
    value: &JString<'_>,
    start: usize,
    units: &mut [u16],
) -> Result<(), i64> {
    let raw_environment = environment.get_raw();
    // SAFETY: `Env` owns a valid JNI function table for this native call, and
    // every JVM that loads the bridges provides the JNI 1.2 functions.
    let get_region = unsafe { (**raw_environment).v1_2.GetStringRegion };
    if units.is_empty() {
        return Ok(());
    }
    let start = jint::try_from(start).map_err(|_| BRIDGE_INVALID_ARGUMENT)?;
    let length = jint::try_from(units.len()).map_err(|_| BRIDGE_INVALID_ARGUMENT)?;
    // SAFETY: `string_length` rejected null, the requested region lies within
    // the string's UTF-16 extent it reported, and the output buffer has
    // matching writable capacity.
    unsafe {
        get_region(
            raw_environment,
            value.as_raw(),
            start,
            length,
            units.as_mut_ptr(),
        );
    }
    if environment.exception_check() {
        // The JNI spec permits only a short list of functions while an
        // exception is pending — "the native code must first clear the
        // exception before making other JNI calls" — and the calls that
        // build a bridge's return value are not on it. Leaving the
        // exception pending aborts the VM under `-Xcheck:jni`. Bridges
        // report stable packed codes rather than Java exceptions, so
        // discarding it and returning invalid-argument is the contract.
        environment.exception_clear();
        return Err(BRIDGE_INVALID_ARGUMENT);
    }
    Ok(())
}

/// Checks `array[offset..offset + length]`, which must not be null, and
/// returns its length. An empty range at the end of the array is accepted.
///
/// # Errors
///
/// Returns [`BRIDGE_INVALID_ARGUMENT`] for a null array, a negative offset or
/// length, or a range that overflows or ends past the array.
pub fn java_byte_range(
    environment: &Env<'_>,
    array: &JByteArray<'_>,
    offset: jint,
    length: jint,
) -> Result<usize, i64> {
    // Also rejects a null array, without calling into the JVM.
    let array_length = array
        .len(environment)
        .map_err(|_| BRIDGE_INVALID_ARGUMENT)
        .and_then(|length| jint::try_from(length).map_err(|_| BRIDGE_INVALID_ARGUMENT))?;
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
    environment: &mut EnvUnowned<'_>,
    request: &JByteArray<'_>,
    execute: impl FnOnce(&[u8]) -> Vec<u8>,
) -> jbyteArray {
    let response = contained(hook, || {
        with_java_env(environment, |environment| {
            // Also rejects a null array.
            let Ok(length) = request.len(environment) else {
                return vec![E::INVALID_ARGUMENT.code()];
            };
            if !limits.accepts_request(length) {
                return vec![E::INVALID_ARGUMENT.code()];
            }
            match environment.convert_byte_array(request) {
                Ok(bytes) => execute(&Zeroizing::new(bytes)),
                Err(_) => vec![E::INTERNAL.code()],
            }
        })
    })
    .unwrap_or_else(|_| vec![E::INTERNAL.code()]);
    let response = Zeroizing::new(response);
    contained(hook, || {
        with_java_env(environment, |environment| {
            environment.byte_array_from_slice(&response)
        })
    })
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
    fn utf16_scan_matches_decode_utf16_at_every_split() {
        const UNITS: [u16; 10] = [
            0x41, 0xE9, 0x20AC, 0xD7FF, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0xE000, 0xFFFF,
        ];
        let mut sequence = Vec::new();
        for length in 0..=5 {
            for mut index in 0..UNITS.len().pow(length) {
                sequence.clear();
                for _ in 0..length {
                    sequence.push(UNITS[index % UNITS.len()]);
                    index /= UNITS.len();
                }
                let decoded: Result<String, _> =
                    char::decode_utf16(sequence.iter().copied()).collect::<Result<_, _>>();
                if let Ok(decoded) = &decoded {
                    // The bounded reader's gate relies on this.
                    assert!(decoded.len() >= sequence.len(), "{sequence:04X?}");
                }
                for chunk in 1..=6 {
                    let scanned = sequence
                        .chunks(chunk)
                        .try_fold(false, scan_utf16)
                        .is_ok_and(|pending_high| !pending_high);
                    assert_eq!(
                        scanned,
                        decoded.is_ok(),
                        "{sequence:04X?} in chunks of {chunk}"
                    );
                }
            }
        }
    }

    #[test]
    fn signed_views_keep_every_byte() {
        let mut bytes = [0_u8, 1, 127, 128, 255];
        assert_eq!(jbytes(&bytes), &[0, 1, 127, -128, -1]);
        jbytes_mut(&mut bytes)[0] = -2;
        assert_eq!(bytes[0], 254);
    }
}
