//! Bounded JNI transport. Vault keys never cross this bridge.
#![deny(
    clippy::multiple_unsafe_ops_per_block,
    clippy::borrow_as_ptr,
    clippy::cast_possible_truncation
)]

use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::{jbyteArray, jint, jlong},
};
use keyguard_ffi::{PanicHook, contained, execute_operation};
use keyguard_windows_passkeys_core as core;

/// Unit tests keep Rust's default hook so a caught assertion still reports its payload.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Returns the bridge ABI version checked by the Kotlin adapter.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_windowspasskeys_NativeWindowsPasskeys_abiVersion(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jint {
    core::ABI_VERSION
}

/// Returns the handle of a new operation, or zero.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_windowspasskeys_NativeWindowsPasskeys_create(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jlong {
    // Handles never exceed i64::MAX; see OperationRegistry::create.
    contained(PANIC_HOOK, core::create).map_or(0, u64::cast_signed)
}

/// Cancels an operation from any thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_windowspasskeys_NativeWindowsPasskeys_cancel(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
) {
    let _ = contained(PANIC_HOOK, || core::cancel(id.cast_unsigned()));
}

/// Removes an operation once its command returned.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_windowspasskeys_NativeWindowsPasskeys_close(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
) {
    let _ = contained(PANIC_HOOK, || core::close(id.cast_unsigned()));
}

/// Executes a bounded bridge command as the operation `id` and returns its framed reply.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_windowspasskeys_NativeWindowsPasskeys_execute(
    env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
    request: JByteArray<'_>,
) -> jbyteArray {
    execute_operation::<core::Transport>(PANIC_HOOK, core::LIMITS, &env, &request, |request| {
        core::execute(id.cast_unsigned(), request)
    })
}
