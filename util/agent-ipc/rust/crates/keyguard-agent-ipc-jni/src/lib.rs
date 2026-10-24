//! JNI adapter for `com.artemchep.keyguard.util.agentipc.NativeAgentIpcJni`.
//!
//! Each export unmarshals its Java arguments, delegates to
//! [`keyguard_agent_ipc_core::bridge`], and returns the ABI v1 values shared with
//! the C adapter. Panics never cross the boundary.
//!
//! - Every export runs in [`bridge::export`], which clears this thread's
//!   diagnostic first. An argument the adapter rejects is `-1`, a panic `-8`.
//! - A negative handle is an invalid argument.
//! - A byte range is checked against the array's length before anything blocks.
//!   `read` fills a zeroized scratch buffer, then copies it into the array;
//!   `write` copies the range into a zeroized buffer, then blocks. No JNI borrow is
//!   held while a call blocks.
//! - No Java exception is left pending: one raised by a JNI call is cleared and
//!   reported as a code, because a pending exception aborts the VM under
//!   `-Xcheck:jni` once the bridge builds its return value.

use jni::{
    JNIEnv,
    objects::{JByteArray, JObject, JString},
    sys::{jint, jlong, jstring},
};
use keyguard_agent_ipc_core::bridge;
use keyguard_ffi::{
    BRIDGE_INTERNAL, BRIDGE_INVALID_ARGUMENT, PanicHook, java_byte_range, java_string, jbytes,
    jbytes_mut,
};
use zeroize::Zeroizing;

/// Unit tests keep Rust's default hook so a caught assertion still reports
/// its payload and source location in CI logs.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Runs a scalar export named `operation` behind the bridge's panic boundary.
fn scalar(operation: &'static str, body: impl FnOnce() -> Result<i64, i64>) -> i64 {
    bridge::export(PANIC_HOOK, operation, body)
}

/// Runs an export that calls back into the JVM. A Java exception that is still
/// pending when `body` returns is cleared.
fn with_environment(
    environment: &JNIEnv<'_>,
    operation: &'static str,
    body: impl FnOnce() -> Result<i64, i64>,
) -> i64 {
    scalar(operation, || {
        let result = body();
        if environment.exception_check().unwrap_or(true) {
            let _ = environment.exception_clear();
        }
        result
    })
}

/// Handles are positive; a negative one never names an object.
fn java_handle(handle: jlong) -> Result<u64, i64> {
    u64::try_from(handle).map_err(|_| BRIDGE_INVALID_ARGUMENT)
}

/// A nullable string. Null is none, which the bridge takes as empty.
fn optional_string(
    environment: &JNIEnv<'_>,
    value: &JString<'_>,
) -> Result<Zeroizing<String>, i64> {
    if value.is_null() {
        return Ok(Zeroizing::new(String::new()));
    }
    java_string(environment, value)
}

/// Returns the ABI version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_abiVersion(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jint {
    keyguard_ffi::contained(PANIC_HOOK, || keyguard_agent_ipc_core::ABI_VERSION as jint)
        .unwrap_or(0)
}

/// Returns this thread's diagnostic from the last failed call, or null. Reading
/// does not clear it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_lastError(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jstring {
    keyguard_ffi::contained(PANIC_HOOK, || {
        let diagnostic = bridge::last_error()
            .and_then(|diagnostic| environment.new_string(diagnostic).ok())
            .map_or(std::ptr::null_mut(), |diagnostic| diagnostic.into_raw());
        if diagnostic.is_null() && environment.exception_check().unwrap_or(true) {
            let _ = environment.exception_clear();
        }
        diagnostic
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Creates and secures an endpoint and returns its listener handle. A null
/// `parent` is none (Windows); a null `exactName` asks for a random name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_listen(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
    parent: JString<'_>,
    prefix: JString<'_>,
    exact_name: JString<'_>,
) -> jlong {
    with_environment(&environment, "listen", || {
        let parent = optional_string(&environment, &parent)?;
        let prefix = java_string(&environment, &prefix)?;
        let exact_name = optional_string(&environment, &exact_name)?;
        Ok(bridge::listen(&parent, &prefix, &exact_name))
    })
}

/// Returns the listener's socket path or pipe name, or null with a diagnostic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_endpoint(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
    listener: jlong,
) -> jstring {
    let mut endpoint: jstring = std::ptr::null_mut();
    let code = with_environment(&environment, "endpoint", || {
        let listener = java_handle(listener)?;
        match bridge::endpoint(listener, |value| environment.new_string(value)) {
            Ok(Ok(value)) => {
                endpoint = value.into_raw();
                Ok(0)
            }
            // The JVM could not allocate the string.
            Ok(Err(_)) => Err(BRIDGE_INTERNAL),
            Err(code) => Ok(code),
        }
    });
    if code < 0 {
        std::ptr::null_mut()
    } else {
        endpoint
    }
}

fn accept(listener: jlong) -> i64 {
    scalar("accept", || Ok(bridge::accept(java_handle(listener)?)))
}

/// Blocks until a client connects and returns the connection's handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_accept(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    listener: jlong,
) -> jlong {
    accept(listener)
}

fn close_listener(listener: jlong) -> i64 {
    scalar("close_listener", || {
        Ok(bridge::close_listener(java_handle(listener)?))
    })
}

/// Closes a listener and consumes its handle; wakes a blocked accept.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_closeListener(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    listener: jlong,
) -> jlong {
    close_listener(listener)
}

fn peer_pid(connection: jlong) -> i64 {
    scalar("peer_pid", || {
        Ok(bridge::peer_pid(java_handle(connection)?))
    })
}

/// The peer's process ID from the accept snapshot; `-7` when not verifiable.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_peerPid(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    connection: jlong,
) -> jlong {
    peer_pid(connection)
}

/// Reads up to `min(length, 1 MiB)` bytes into `buffer[offset..]`: the count, or 0
/// at end of stream. A zero `length` is `-1`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_read(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
    connection: jlong,
    buffer: JByteArray<'_>,
    offset: jint,
    length: jint,
) -> jlong {
    with_environment(&environment, "read", || {
        let connection = java_handle(connection)?;
        let length = java_byte_range(&environment, &buffer, offset, length)?;
        let mut scratch = Zeroizing::new(vec![0_u8; length.min(bridge::MAX_READ_BYTES)]);
        let count = bridge::read(connection, &mut scratch);
        if count > 0 {
            let filled = usize::try_from(count)
                .ok()
                .and_then(|count| scratch.get(..count))
                .ok_or(BRIDGE_INTERNAL)?;
            environment
                .set_byte_array_region(&buffer, offset, jbytes(filled))
                .map_err(|_| BRIDGE_INTERNAL)?;
        }
        Ok(count)
    })
}

/// Writes all of `buffer[offset..offset + length]`, at most 32 MiB, and returns 0.
/// A zero `length` returns 0 without a system call.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_write(
    environment: JNIEnv<'_>,
    _object: JObject<'_>,
    connection: jlong,
    buffer: JByteArray<'_>,
    offset: jint,
    length: jint,
) -> jlong {
    with_environment(&environment, "write", || {
        let connection = java_handle(connection)?;
        let length = java_byte_range(&environment, &buffer, offset, length)?;
        // Rejected before copying, so an oversized array is never duplicated.
        if length > bridge::MAX_WRITE_BYTES {
            return Err(BRIDGE_INVALID_ARGUMENT);
        }
        let mut data = Zeroizing::new(vec![0_u8; length]);
        if length != 0 {
            environment
                .get_byte_array_region(&buffer, offset, jbytes_mut(&mut data))
                .map_err(|_| BRIDGE_INVALID_ARGUMENT)?;
        }
        Ok(bridge::write(connection, &data))
    })
}

fn await_input(connection: jlong) -> i64 {
    scalar("await_input", || {
        Ok(bridge::await_input(java_handle(connection)?))
    })
}

/// Waits without consuming input: 1 when input is readable, 0 at end of stream.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_awaitInput(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    connection: jlong,
) -> jlong {
    await_input(connection)
}

fn close(connection: jlong) -> i64 {
    scalar("close", || Ok(bridge::close(java_handle(connection)?)))
}

/// Closes a connection and consumes its handle; wakes every blocked call on it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_agentipc_NativeAgentIpcJni_close(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
    connection: jlong,
) -> jlong {
    close(connection)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn last() -> String {
        bridge::last_error().unwrap_or_default()
    }

    /// The scalar exports, by their operation names.
    type Scalar = fn(jlong) -> i64;
    const SCALARS: [(Scalar, &str); 5] = [
        (accept, "accept"),
        (close_listener, "close_listener"),
        (peer_pid, "peer_pid"),
        (await_input, "await_input"),
        (close, "close"),
    ];

    #[test]
    fn negative_handles_are_invalid_arguments() {
        assert_eq!(java_handle(-1), Err(BRIDGE_INVALID_ARGUMENT));
        assert_eq!(java_handle(jlong::MIN), Err(BRIDGE_INVALID_ARGUMENT));
        assert_eq!(java_handle(7), Ok(7));
        for (call, operation) in SCALARS {
            assert_eq!(call(-1), -1, "{operation}");
            assert_eq!(last(), format!("{operation}: invalid_argument"));
        }
    }

    #[test]
    fn unknown_handles_are_consumed_or_never_issued() {
        for (call, operation) in SCALARS {
            assert_eq!(call(0), -5, "{operation}");
            assert_eq!(last(), format!("{operation}: invalid_handle"));
            assert_eq!(call(jlong::MAX), -5, "{operation}");
        }
    }

    #[test]
    fn panics_are_contained_as_internal_failures() {
        let code = scalar("read", || -> Result<i64, i64> { panic!("fixture") });
        assert_eq!(code, -8);
        assert_eq!(last(), "read: panic");
        // The next export clears the diagnostic.
        assert_eq!(scalar("read", || Ok(0)), 0);
        assert_eq!(bridge::last_error(), None);
    }

    #[test]
    fn adapter_failures_map_to_the_module_codes() {
        assert_eq!(scalar("write", || Err(BRIDGE_INVALID_ARGUMENT)), -1);
        assert_eq!(last(), "write: invalid_argument");
        assert_eq!(scalar("endpoint", || Err(BRIDGE_INTERNAL)), -8);
        assert_eq!(last(), "endpoint: bridge");
    }
}
