//! JNI adapter for `com.artemchep.keyguard.util.instance.NativeInstanceJni`.
//!
//! Lifecycle and protocol behavior live in the shared Rust core. This adapter
//! validates Java arguments and contains panics before returning stable ABI codes.

use std::panic::AssertUnwindSafe;

use jni::{
    JNIEnv,
    objects::{JObject, JString},
    sys::{jint, jlong, jstring},
};
use keyguard_instance_core::{Error, bridge};

const INVALID_ARGUMENT: i64 = Error::InvalidArgument as i64;
const INTERNAL: i64 = Error::Internal as i64;
const MAX_STRING_UNITS: usize = 65_536;

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

/// Returns this thread's last native diagnostic, without consuming it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_lastError(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jstring {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        bridge::last_failure()
            .and_then(|failure| {
                environment
                    .new_string(failure.to_string())
                    .ok()
                    .map(|message| message.into_raw())
            })
            .unwrap_or(std::ptr::null_mut())
    }))
    .unwrap_or(std::ptr::null_mut())
}

fn java_string(environment: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, i64> {
    if value.is_null() {
        return Err(INVALID_ARGUMENT);
    }
    let raw_environment = environment.get_raw();
    // SAFETY: JNIEnv owns the live JNI function table for this native call.
    let functions = unsafe { &**raw_environment };
    let get_length = functions.GetStringLength.ok_or(INVALID_ARGUMENT)?;
    let get_region = functions.GetStringRegion.ok_or(INVALID_ARGUMENT)?;
    // SAFETY: JNI guarantees a live String argument, and null was rejected above.
    let length = unsafe { get_length(raw_environment, value.as_raw()) };
    let length = usize::try_from(length).map_err(|_| INVALID_ARGUMENT)?;
    if length > MAX_STRING_UNITS {
        return Err(INVALID_ARGUMENT);
    }
    let mut utf16 = vec![0_u16; length];
    if length != 0 {
        // SAFETY: The requested region and writable UTF-16 buffer have the exact String length.
        unsafe {
            get_region(
                raw_environment,
                value.as_raw(),
                0,
                length as jint,
                utf16.as_mut_ptr(),
            )
        };
        if environment.exception_check().unwrap_or(true) {
            let _ = environment.exception_clear();
            return Err(INVALID_ARGUMENT);
        }
    }
    let result = String::from_utf16(&utf16).map_err(|_| INVALID_ARGUMENT)?;
    if result.len() > MAX_STRING_UNITS {
        return Err(INVALID_ARGUMENT);
    }
    Ok(result)
}

fn unsigned(value: jlong) -> Result<u64, i64> {
    u64::try_from(value).map_err(|_| INVALID_ARGUMENT)
}

/// Returns the fixed native ABI version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_abiVersion(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jint {
    keyguard_instance_core::ABI_VERSION as jint
}

/// Acquires ownership or delivers an acknowledged activation request.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_acquireOrActivate(
    mut environment: JNIEnv<'_>,
    _object: JObject<'_>,
    coordination: JString<'_>,
    runtime: JString<'_>,
    identity: JString<'_>,
    timeout_ms: jlong,
) -> jlong {
    contained(|| {
        let timeout_ms = unsigned(timeout_ms)?;
        let coordination = java_string(&mut environment, &coordination)?;
        let runtime = java_string(&mut environment, &runtime)?;
        let identity = java_string(&mut environment, &identity)?;
        Ok(bridge::acquire_or_activate(
            &coordination,
            &runtime,
            &identity,
            timeout_ms,
        ))
    })
}

/// Waits for activation or shutdown without holding a JNI string or array borrow.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_waitEvent(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    contained(|| Ok(bridge::wait_event(unsigned(handle)?)))
}

/// Stops transport work while retaining ownership.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_stop(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    contained(|| Ok(bridge::stop(unsigned(handle)?)))
}

/// Consumes a handle after stopping transport work.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_close(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    contained(|| Ok(bridge::close(unsigned(handle)?)))
}
