//! JNI exports for the Kotlin utility; no JVM-specific device behavior lives here.
use jni::{
    EnvUnowned,
    objects::{JByteArray, JObject},
    sys::{jbyteArray, jint, jlong},
};
use keyguard_age_core as core;
use keyguard_ffi::{PanicHook, contained, execute_operation};

/// Unit tests keep Rust's default hook so a caught assertion still reports its payload.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Native bridge entry point.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_age_NativeAgeJni_abiVersion(
    _env: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jint {
    core::ABI_VERSION as jint
}
/// Native bridge entry point.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_age_NativeAgeJni_create(
    _env: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jlong {
    contained(PANIC_HOOK, core::create).unwrap_or(0) as jlong
}
/// Native bridge entry point.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_age_NativeAgeJni_close(
    _env: EnvUnowned<'_>,
    _object: JObject<'_>,
    id: jlong,
) {
    let _ = contained(PANIC_HOOK, || core::close(id as u64));
}
/// Native bridge entry point.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_age_NativeAgeJni_execute(
    mut env: EnvUnowned<'_>,
    _object: JObject<'_>,
    id: jlong,
    request: JByteArray<'_>,
) -> jbyteArray {
    execute_operation::<core::Error>(PANIC_HOOK, core::LIMITS, &mut env, &request, |request| {
        core::execute(id as u64, request)
    })
}
