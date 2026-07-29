//! JNI adapter for `com.artemchep.keyguard.util.io.NativeIoJni`.
//!
//! Every function unmarshals its Java arguments, delegates to
//! [`keyguard_io_core::bridge`], and returns ABI v1 wire values shared with
//! the C adapter. Panics never cross the boundary: they are contained and
//! reported as a bridge failure with no path or payload disclosure.

use std::panic::AssertUnwindSafe;

use jni::{
    JNIEnv,
    objects::{JByteArray, JIntArray, JLongArray, JObject, JString},
    sys::{jint, jlong, jlongArray},
};
use keyguard_io_core::{abi, bridge};
use zeroize::Zeroizing;

/// Runs `body` behind the panic boundary every entry point shares.
///
/// The hook is installed *inside* the boundary: `std::panic::set_hook`
/// "panics if called from a panicking thread", and a panic inside
/// `Once::call_once` poisons the `Once` so that every later call panics too.
/// Installed outside the boundary, one such panic would escape the
/// `extern "system"` frame and abort the process on every subsequent bridge
/// call — permanently bricking the library rather than failing one operation.
fn contained<R>(body: impl FnOnce() -> R) -> Result<R, i64> {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        keyguard_io_core::install_redacting_panic_hook();
        body()
    }))
    .map_err(|_| abi::pack_bridge_panic())
}

fn java_string(environment: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, i64> {
    if value.is_null() {
        return Err(abi::pack_bridge_invalid_argument());
    }
    let raw_environment = environment.get_raw();
    // SAFETY: `JNIEnv` owns a valid JNI function table for this native call.
    let functions = unsafe { &**raw_environment };
    let get_length = functions
        .GetStringLength
        .ok_or_else(abi::pack_bridge_invalid_argument)?;
    let get_region = functions
        .GetStringRegion
        .ok_or_else(abi::pack_bridge_invalid_argument)?;
    // SAFETY: Null was rejected and JNI export signatures guarantee that
    // `value` is a live local java.lang.String reference.
    let length = unsafe { get_length(raw_environment, value.as_raw()) };
    let length = usize::try_from(length).map_err(|_| abi::pack_bridge_invalid_argument())?;
    let mut utf16 = vec![0_u16; length];
    if length != 0 {
        let length = i32::try_from(length).map_err(|_| abi::pack_bridge_invalid_argument())?;
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
            // exception before making other JNI calls" — and `NewLongArray` /
            // `SetLongArrayRegion`, which this call's error path still needs to
            // build a return value, are not on it. Leaving the exception
            // pending aborts the VM under `-Xcheck:jni`. The bridge reports
            // stable packed codes rather than Java exceptions, so discarding it
            // and returning invalid-argument is the intended contract.
            let _ = environment.exception_clear();
            return Err(abi::pack_bridge_invalid_argument());
        }
    }
    String::from_utf16(&utf16).map_err(|_| abi::pack_bridge_invalid_argument())
}

fn java_handle(handle: jlong) -> Result<u64, i64> {
    u64::try_from(handle).map_err(|_| abi::pack_bridge_invalid_argument())
}

fn java_txn_options(
    environment: &JNIEnv<'_>,
    options: &JIntArray<'_>,
) -> Result<abi::TxnOptionsWire, i64> {
    if options.is_null() {
        return Err(abi::pack_bridge_invalid_argument());
    }
    let length = environment
        .get_array_length(options)
        .map_err(|_| abi::pack_bridge_invalid_argument())?;
    if usize::try_from(length).ok() != Some(abi::TxnOptionsWire::JNI_FIELD_COUNT) {
        return Err(abi::pack_bridge_invalid_argument());
    }
    let mut fields = [0_i32; abi::TxnOptionsWire::JNI_FIELD_COUNT];
    environment
        .get_int_array_region(options, 0, &mut fields)
        .map_err(|_| abi::pack_bridge_invalid_argument())?;
    Ok(abi::TxnOptionsWire::from_jni_fields(fields))
}

fn checked_range(array_length: jint, offset: jint, length: jint) -> Result<usize, i64> {
    let (Ok(array_length), Ok(offset), Ok(length)) = (
        usize::try_from(array_length),
        usize::try_from(offset),
        usize::try_from(length),
    ) else {
        return Err(abi::pack_bridge_invalid_argument());
    };
    offset
        .checked_add(length)
        .filter(|end| *end <= array_length)
        .map(|_| length)
        .ok_or_else(abi::pack_bridge_invalid_argument)
}

/// Copies a caller-selected Java byte-array range into a zeroized buffer.
fn java_bytes(
    environment: &JNIEnv<'_>,
    input: &JByteArray<'_>,
    offset: jint,
    length: jint,
) -> Result<Zeroizing<Vec<u8>>, i64> {
    let array_length = environment
        .get_array_length(input)
        .map_err(|_| abi::pack_bridge_invalid_argument())?;
    let length = checked_range(array_length, offset, length)?;
    let mut buffer = Zeroizing::new(vec![0_u8; length]);
    // SAFETY: jbyte and u8 share size, alignment, and layout; the cast only
    // changes signedness and the slice covers the buffer's exact extent.
    let signed =
        unsafe { std::slice::from_raw_parts_mut(buffer.as_mut_ptr().cast::<i8>(), buffer.len()) };
    environment
        .get_byte_array_region(input, offset, signed)
        .map_err(|_| abi::pack_bridge_invalid_argument())?;
    Ok(buffer)
}

fn unwrap(result: Result<i64, i64>) -> jlong {
    match result {
        Ok(value) | Err(value) => value,
    }
}

fn java_long_array(environment: &JNIEnv<'_>, fields: &[jlong]) -> jlongArray {
    let Ok(length) = jint::try_from(fields.len()) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = environment.new_long_array(length) else {
        return std::ptr::null_mut();
    };
    if environment
        .set_long_array_region(&array, 0, fields)
        .is_err()
    {
        return std::ptr::null_mut();
    }
    JLongArray::into_raw(array)
}

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_abiVersion(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jint {
    contained(|| keyguard_io_core::ABI_VERSION as jint).unwrap_or(0)
}

/// Opens and retains an existing absolute directory.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_directoryOpen(
    mut environment: JNIEnv<'_>,
    _object: JObject<'_>,
    directory: JString<'_>,
) -> jlong {
    unwrap(
        contained(|| {
            let directory = java_string(&mut environment, &directory)?;
            Ok(bridge::directory_open(&directory))
        })
        .and_then(std::convert::identity),
    )
}

/// Closes one retained-directory handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_directoryClose(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    unwrap(
        contained(|| Ok(bridge::directory_close(java_handle(handle)?)))
            .and_then(std::convert::identity),
    )
}

/// Opens an atomic-write transaction; returns a positive handle or a packed
/// failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_txnBegin(
    mut environment: JNIEnv<'_>,
    _object: JObject<'_>,
    destination: JString<'_>,
    options: JIntArray<'_>,
) -> jlong {
    unwrap(
        contained(|| {
            let destination = java_string(&mut environment, &destination)?;
            let options = java_txn_options(&environment, &options)?;
            Ok(bridge::txn_begin(&destination, options))
        })
        .and_then(std::convert::identity),
    )
}

/// Opens an atomic-write transaction beneath a retained directory.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_txnBeginAtDirectory(
    mut environment: JNIEnv<'_>,
    _object: JObject<'_>,
    directory_handle: jlong,
    relative_destination: JString<'_>,
    options: JIntArray<'_>,
) -> jlong {
    unwrap(
        contained(|| {
            let directory_handle = java_handle(directory_handle)?;
            let destination = java_string(&mut environment, &relative_destination)?;
            let options = java_txn_options(&environment, &options)?;
            Ok(bridge::txn_begin_at_directory(
                directory_handle,
                &destination,
                options,
            ))
        })
        .and_then(std::convert::identity),
    )
}

/// Appends a Java byte-array range to a transaction; returns the byte count
/// or a packed failure.
///
/// A failed append permanently prevents publication. The handle remains
/// available for abort, and later writes return the original failure without
/// replaying I/O. Commit consumes the handle, attempts cleanup only, and
/// reports that original write failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_txnWrite(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
    input: JByteArray<'_>,
    offset: jint,
    length: jint,
) -> jlong {
    unwrap(
        contained(|| {
            let handle = java_handle(handle)?;
            let bytes = java_bytes(&environment, &input, offset, length)?;
            Ok(bridge::txn_write(handle, &bytes))
        })
        .and_then(std::convert::identity),
    )
}

/// Commits a transaction, consuming its handle; returns a packed commit
/// report or failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_txnCommit(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    unwrap(
        contained(|| Ok(bridge::txn_commit(java_handle(handle)?))).and_then(std::convert::identity),
    )
}

/// Aborts a transaction, consuming its handle; returns zero or a packed
/// failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_txnAbort(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    unwrap(
        contained(|| Ok(bridge::txn_abort(java_handle(handle)?))).and_then(std::convert::identity),
    )
}

/// Opens private scratch storage; returns a positive handle or a packed
/// failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_scratchOpen(
    mut environment: JNIEnv<'_>,
    _object: JObject<'_>,
    directory: JString<'_>,
) -> jlong {
    unwrap(
        contained(|| {
            let directory = java_string(&mut environment, &directory)?;
            Ok(bridge::scratch_open(&directory))
        })
        .and_then(std::convert::identity),
    )
}

/// Appends a Java byte-array range to scratch storage; returns the byte
/// count or a packed failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_scratchWrite(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
    input: JByteArray<'_>,
    offset: jint,
    length: jint,
) -> jlong {
    unwrap(
        contained(|| {
            let handle = java_handle(handle)?;
            let bytes = java_bytes(&environment, &input, offset, length)?;
            Ok(bridge::scratch_write(handle, &bytes))
        })
        .and_then(std::convert::identity),
    )
}

/// Seals scratch storage for reading; returns zero or a packed failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_scratchSeal(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    unwrap(
        contained(|| Ok(bridge::scratch_seal(java_handle(handle)?)))
            .and_then(std::convert::identity),
    )
}

/// Returns the scratch length in bytes or a packed failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_scratchLength(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    unwrap(
        contained(|| Ok(bridge::scratch_length(java_handle(handle)?)))
            .and_then(std::convert::identity),
    )
}

/// Reads scratch bytes at a fixed position into a Java byte-array range;
/// returns the byte count, `-1` at end-of-file, or a packed failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_scratchReadAt(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
    position: jlong,
    output: JByteArray<'_>,
    offset: jint,
    length: jint,
) -> jlong {
    unwrap(
        contained(|| {
            let handle = java_handle(handle)?;
            let position =
                u64::try_from(position).map_err(|_| abi::pack_bridge_invalid_argument())?;
            let array_length = environment
                .get_array_length(&output)
                .map_err(|_| abi::pack_bridge_invalid_argument())?;
            let length = checked_range(array_length, offset, length)?;
            let mut buffer = Zeroizing::new(vec![0_u8; length]);
            let result = bridge::scratch_read_at(handle, position, &mut buffer);
            if result <= 0 {
                return Ok(result);
            }
            let read = result as usize;
            // SAFETY: jbyte and u8 share size, alignment, and layout; the cast
            // only changes signedness and `read` never exceeds the buffer length.
            let signed = unsafe { std::slice::from_raw_parts(buffer.as_ptr().cast::<i8>(), read) };
            environment
                .set_byte_array_region(&output, offset, signed)
                .map_err(|_| abi::pack_bridge_invalid_argument())?;
            Ok(result)
        })
        .and_then(std::convert::identity),
    )
}

/// Closes scratch storage, consuming its handle; returns zero or a packed
/// failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_scratchClose(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    unwrap(
        contained(|| Ok(bridge::scratch_close(java_handle(handle)?)))
            .and_then(std::convert::identity),
    )
}

/// Sweeps a directory for orphaned temporary artifacts.
///
/// A successful array mirrors [`abi::SweepReportWire`]. Root failures return
/// a one-element array containing the packed negative failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_io_NativeIoJni_sweepOrphans(
    mut environment: JNIEnv<'_>,
    _object: JObject<'_>,
    directory: JString<'_>,
    older_than_ms: jlong,
    role_mask: jint,
) -> jlongArray {
    let fields = contained(|| {
        let role_mask = role_mask as u32;
        if role_mask & !bridge::SWEEP_ROLE_MASK_ALL != 0 {
            return Err(abi::pack_bridge_invalid_argument());
        }
        let directory = java_string(&mut environment, &directory)?;
        let older_than_ms =
            u64::try_from(older_than_ms).map_err(|_| abi::pack_bridge_invalid_argument())?;
        bridge::sweep(&directory, older_than_ms, role_mask)
            .map(abi::SweepReportWire::from_report)
            .map(|report| report.as_jni_fields().to_vec())
    })
    .and_then(std::convert::identity)
    .unwrap_or_else(|packed_failure| vec![packed_failure]);

    // Marshalling is itself contained. This is the only entry point that has to
    // build a Java object to return, and doing so outside the boundary would let
    // a panic in the `jni` layer unwind across `extern "system"`.
    contained(|| java_long_array(&environment, &fields)).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checked_range_accepts_empty_tail_and_rejects_overflow() {
        assert_eq!(checked_range(4, 4, 0), Ok(0));
        assert_eq!(checked_range(4, 0, 4), Ok(4));
        assert!(checked_range(4, 3, 2).is_err());
        assert!(checked_range(4, -1, 1).is_err());
        assert!(checked_range(4, 0, -1).is_err());
    }

    #[test]
    fn negative_handles_are_rejected_as_invalid_arguments() {
        assert_eq!(
            java_handle(-1).expect_err("negative handle must be rejected"),
            abi::pack_bridge_invalid_argument()
        );
    }

    #[test]
    fn scalar_boundary_preserves_the_cleanup_incomplete_bit() {
        let packed = abi::pack_bridge_panic() | (1_i64 << 56);
        assert_eq!(unwrap(Ok(packed)), packed);
    }

    #[test]
    fn scalar_boundary_preserves_publication_unknown_operation_bits() {
        let packed = 0x0E00_0000_0000_0BF7_i64;
        assert_eq!(unwrap(Ok(packed)), packed);
    }

    #[test]
    fn unknown_handles_produce_a_structured_bridge_failure() {
        let packed = bridge::txn_commit(0xDEAD);
        assert!(packed < 0);
        assert_ne!(packed, -1);
        assert_eq!(
            (packed as u64 >> 8) as u8,
            keyguard_io_core::FailureKind::NotFound as u8
        );
        assert_eq!(
            (packed as u64 >> 16) as u8,
            keyguard_io_core::ErrorDomain::Bridge as u8
        );
        assert_eq!(
            (packed as u64 >> 24) as u32,
            keyguard_io_core::BRIDGE_ERROR_UNKNOWN_HANDLE
        );
    }
}
