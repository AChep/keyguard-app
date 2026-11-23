//! JNI adapter; platform registration and failure state live in the shared core.
use jni::{
    EnvUnowned,
    objects::{JObject, JString},
    sys::{jint, jstring},
};
use keyguard_ffi::{PanicHook, java_string_within, with_java_env};
use keyguard_launch_at_login_core as core;

/// Tests keep the default panic hook so CI logs show assertion details.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

fn contained<R>(body: impl FnOnce() -> R) -> Result<R, i64> {
    keyguard_ffi::contained(PANIC_HOOK, body)
}

/// Returns the stable bridge version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_launchatlogin_NativeLaunchAtLoginJni_abiVersion(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jint {
    core::ABI_VERSION as jint
}

/// Executes a bounded request without retaining a JVM string during OS operations.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_launchatlogin_NativeLaunchAtLoginJni_execute(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    operation: jint,
    configuration: JString<'_>,
) -> jint {
    contained(|| {
        let request = with_java_env(&mut environment, |environment| {
            java_string_within(environment, &configuration, core::MAX_REQUEST)
                .ok()
                .flatten()
        });
        match request {
            Some(request) => core::execute(operation, &request),
            None => core::invalid_argument(),
        }
    })
    .unwrap_or(core::Error::Internal as i32)
}

/// Copies the calling thread's latest diagnostic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_launchatlogin_NativeLaunchAtLoginJni_lastError(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jstring {
    contained(|| {
        with_java_env(&mut environment, |environment| {
            core::last_error()
                .and_then(|message| environment.new_string(message).ok())
                .map_or(std::ptr::null_mut(), |message| message.into_raw())
        })
    })
    .unwrap_or(std::ptr::null_mut())
}
