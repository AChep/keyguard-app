//! JNI exports for the Kotlin utility; no JVM-specific device behavior lives here.
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::{jbyteArray, jint, jlong},
};
use keyguard_yubikey_core as core;
use std::panic::{AssertUnwindSafe, catch_unwind};
use zeroize::Zeroizing;
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_yubikey_NativeYubiKeyJni_abiVersion(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jint {
    core::ABI_VERSION as jint
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_yubikey_NativeYubiKeyJni_create(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jlong {
    catch_unwind(core::create).unwrap_or(0) as jlong
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_yubikey_NativeYubiKeyJni_cancel(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
) {
    let _ = catch_unwind(|| core::cancel(id as u64));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_yubikey_NativeYubiKeyJni_close(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
) {
    let _ = catch_unwind(|| core::close(id as u64));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_yubikey_NativeYubiKeyJni_execute(
    env: JNIEnv<'_>,
    _object: JObject<'_>,
    id: jlong,
    request: JByteArray<'_>,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        // Also rejects a null array.
        let Ok(size) = env.get_array_length(&request) else {
            return vec![core::Error::InvalidArgument as u8];
        };
        if !(core::HEADER_LENGTH as i32..=core::MAX_REQUEST as i32).contains(&size) {
            return vec![core::Error::InvalidArgument as u8];
        }
        match env.convert_byte_array(&request) {
            Ok(bytes) => core::execute(id as u64, &Zeroizing::new(bytes)),
            Err(_) => vec![core::Error::Internal as u8],
        }
    }))
    .unwrap_or_else(|_| vec![core::Error::Internal as u8]);
    env.byte_array_from_slice(&Zeroizing::new(result))
        .map(JByteArray::into_raw)
        .unwrap_or(std::ptr::null_mut())
}
