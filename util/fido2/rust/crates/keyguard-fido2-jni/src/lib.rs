//! JNI exports for the Kotlin utility; no JVM-specific device behavior lives here.
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::{jbyteArray, jint, jlong},
};
use keyguard_ffi::{PanicHook, contained, execute_operation};
use keyguard_fido2_core as core;

/// Unit tests keep Rust's default hook so a caught assertion still reports its payload.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_fido2_NativeFido2Jni_abiVersion(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jint {
    core::ABI_VERSION as jint
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_fido2_NativeFido2Jni_create(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jlong {
    contained(PANIC_HOOK, core::create).unwrap_or(0) as jlong
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_fido2_NativeFido2Jni_cancel(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
) {
    let _ = contained(PANIC_HOOK, || core::cancel(id as u64));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_fido2_NativeFido2Jni_close(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
) {
    let _ = contained(PANIC_HOOK, || core::close(id as u64));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_fido2_NativeFido2Jni_execute(
    env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
    request: JByteArray<'_>,
) -> jbyteArray {
    execute_operation::<core::Error>(PANIC_HOOK, core::LIMITS, &env, &request, |request| {
        core::execute(id as u64, request)
    })
}
