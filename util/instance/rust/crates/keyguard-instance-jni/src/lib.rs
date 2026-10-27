//! JNI adapter for `com.artemchep.keyguard.util.instance.NativeInstanceJni`.
//!
//! Lifecycle and protocol behavior live in the shared Rust core. This adapter
//! validates Java arguments and contains panics before returning stable ABI codes.

use std::panic::AssertUnwindSafe;

use jni::{
    Env, EnvUnowned,
    objects::{JObject, JString},
    sys::{jint, jlong, jstring},
};
use keyguard_ffi::{java_string_within, with_java_env};
use keyguard_instance_core::{Error, bridge};
use zeroize::Zeroizing;

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

/// Returns this thread's last native diagnostic, without consuming it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_lastError(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jstring {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        with_java_env(&mut environment, |environment| {
            bridge::last_failure()
                .and_then(|failure| {
                    environment
                        .new_string(failure.to_string())
                        .ok()
                        .map(|message| message.into_raw())
                })
                .unwrap_or(std::ptr::null_mut())
        })
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Copies a Java string of at most [`MAX_STRING_BYTES`] UTF-8 bytes. A null,
/// malformed or longer string is an invalid argument.
fn java_string(environment: &Env<'_>, value: &JString<'_>) -> Result<Zeroizing<String>, i64> {
    java_string_within(environment, value, MAX_STRING_BYTES)
        .ok()
        .flatten()
        .ok_or(INVALID_ARGUMENT)
}

fn unsigned(value: jlong) -> Result<u64, i64> {
    u64::try_from(value).map_err(|_| INVALID_ARGUMENT)
}

/// Returns the fixed native ABI version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_abiVersion(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jint {
    keyguard_instance_core::ABI_VERSION as jint
}

/// Decodes a coordination config and passes it to `operation`.
fn arbitrate(
    environment: &mut EnvUnowned<'_>,
    coordination: &JString<'_>,
    runtime: &JString<'_>,
    identity: &JString<'_>,
    timeout_ms: jlong,
    operation: fn(&str, &str, &str, u64) -> i64,
) -> i64 {
    contained(|| {
        let timeout_ms = unsigned(timeout_ms)?;
        let (coordination, runtime, identity) = with_java_env(environment, |environment| {
            Ok::<_, i64>((
                java_string(environment, coordination)?,
                java_string(environment, runtime)?,
                java_string(environment, identity)?,
            ))
        })?;
        Ok(operation(&coordination, &runtime, &identity, timeout_ms))
    })
}

/// Acquires ownership or delivers an acknowledged activation request.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_acquireOrActivate(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    coordination: JString<'_>,
    runtime: JString<'_>,
    identity: JString<'_>,
    timeout_ms: jlong,
) -> jlong {
    arbitrate(
        &mut environment,
        &coordination,
        &runtime,
        &identity,
        timeout_ms,
        bridge::acquire_or_activate,
    )
}

/// Acquires ownership without activating the current owner, waiting for it to exit.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_acquire(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    coordination: JString<'_>,
    runtime: JString<'_>,
    identity: JString<'_>,
    timeout_ms: jlong,
) -> jlong {
    arbitrate(
        &mut environment,
        &coordination,
        &runtime,
        &identity,
        timeout_ms,
        bridge::acquire,
    )
}

/// Waits for activation or shutdown without holding a JNI string or array borrow.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_waitEvent(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    contained(|| Ok(bridge::wait_event(unsigned(handle)?)))
}

/// Stops transport work while retaining ownership.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_stop(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    contained(|| Ok(bridge::stop(unsigned(handle)?)))
}

/// Consumes a handle after stopping transport work.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_instance_NativeInstanceJni_close(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jlong {
    contained(|| Ok(bridge::close(unsigned(handle)?)))
}
