#![allow(non_snake_case)]

mod accent;
mod autotype;
mod biometrics;
mod ffi;
mod hotkey;
mod keychain;
mod notification;

use ffi::{BiometricsResultCallback, BiometricsVerifyCallback, HotKeyPressedCallback};
use std::ffi::c_char;
use std::ffi::c_int;
use std::ffi::c_void;
use std::ptr;

#[cfg_attr(not(test), no_mangle)]
/// Types the supplied payload.
///
/// # Safety
///
/// If `payload` is non-null, it must point to an immutable, readable,
/// NUL-terminated byte sequence for the duration of this call.
pub unsafe extern "C" fn autoType(payload: *const c_char) -> bool {
    ffi::with_redacted_ffi_boundary("autoType", false, || {
        // SAFETY: `autoType` requires its caller to provide a valid C string
        // for the duration of this call.
        let payload = unsafe { ffi::require_string(payload, "payload") }?;
        autotype::execute(&payload)?;
        Ok(true)
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn getSystemAccentColor() -> c_int {
    ffi::with_ffi_boundary("getSystemAccentColor", 0, || {
        Ok(accent::get_system_accent_color() as c_int)
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn biometricsIsSupported() -> bool {
    ffi::with_ffi_boundary("biometricsIsSupported", false, || {
        Ok(biometrics::is_supported())
    })
}

#[cfg_attr(not(test), no_mangle)]
/// Asks the platform to verify the current user.
///
/// # Safety
///
/// If `title` is non-null, it must point to an immutable, readable,
/// NUL-terminated byte sequence for the duration of this call. A non-null
/// `callback` must remain callable until asynchronous verification completes.
pub unsafe extern "C" fn biometricsVerify(
    window_handle: i64,
    title: *const c_char,
    callback: BiometricsVerifyCallback,
) {
    ffi::with_ffi_boundary("biometricsVerify", (), || {
        // SAFETY: `biometricsVerify` requires its caller to provide a valid C
        // string for the duration of this call.
        let title = unsafe { ffi::require_string(title, "title") }?;
        biometrics::verify(window_handle, &title, callback);
        Ok(())
    });
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn biometricsDeleteCredential() -> c_int {
    ffi::with_ffi_boundary("biometricsDeleteCredential", 0, || {
        Ok(c_int::from(biometrics::delete_credential()))
    })
}

#[cfg_attr(not(test), no_mangle)]
/// Wraps or unwraps a secret with the platform-protected biometric key.
///
/// `decrypt` must be `0` to wrap or `1` to unwrap. The callback is invoked
/// synchronously and must copy its borrowed result before returning.
///
/// # Safety
///
/// The caller must ensure that:
/// - a non-null `title` points to an immutable, readable, NUL-terminated byte
///   sequence for the duration of this call;
/// - a non-null `input` points to `input_len` initialized bytes that remain
///   immutable and readable for the duration of this call;
/// - a non-null `callback` remains callable for the duration of this call and
///   does not retain the borrowed result or error pointers;
/// - a non-zero `window_handle` identifies a live native window.
pub unsafe extern "C" fn biometricsTransformSecret(
    window_handle: i64,
    title: *const c_char,
    input: *const u8,
    input_len: u64,
    decrypt: c_int,
    callback: BiometricsResultCallback,
) -> c_int {
    ffi::with_ffi_boundary("biometricsTransformSecret", 0, || {
        let decrypt = ffi::bool_from_c_int(decrypt, "decrypt")?;
        let callback = callback.ok_or("callback pointer was null")?;
        // SAFETY: `biometricsTransformSecret` requires its caller to provide a
        // valid C string for the duration of this call.
        let title = unsafe { ffi::require_string(title, "title") }?;
        let input = ffi::require_non_null(input, "input")?;
        let input_len = usize::try_from(input_len)
            .map_err(|_| "input length did not fit in memory".to_owned())?;
        if input_len == 0 || input_len > 4096 {
            return Err("input length must be between 1 and 4096 bytes".to_owned());
        }

        // SAFETY: The exported FFI contract requires `input` to reference
        // `input_len` readable bytes for this synchronous call. The length
        // was bounded above and converted to usize before constructing the
        // borrowed slice.
        let input = unsafe { std::slice::from_raw_parts(input, input_len) };
        biometrics::transform_secret(window_handle, &title, input, decrypt, Some(callback));
        Ok(1)
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn keychainAddPassword(id: *const c_char, password: *const c_char) -> bool {
    ffi::with_ffi_boundary("keychainAddPassword", false, || {
        let id = ffi::require_non_null(id, "id")?;
        let password = ffi::require_non_null(password, "password")?;
        Ok(keychain::add_password(id.cast(), password.cast()))
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn keychainGetPassword(id: *const c_char) -> *mut c_char {
    ffi::with_ffi_boundary("keychainGetPassword", ptr::null_mut(), || {
        let id = ffi::require_non_null(id, "id")?;
        Ok(keychain::get_password(id.cast()))
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn keychainDeletePassword(id: *const c_char) -> bool {
    ffi::with_ffi_boundary("keychainDeletePassword", false, || {
        let id = ffi::require_non_null(id, "id")?;
        Ok(keychain::delete_password(id.cast()))
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn keychainContainsPassword(id: *const c_char) -> bool {
    ffi::with_ffi_boundary("keychainContainsPassword", false, || {
        let id = ffi::require_non_null(id, "id")?;
        Ok(keychain::contains_password(id.cast()))
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn postNotification(id: c_int, title: *const c_char, text: *const c_char) -> c_int {
    ffi::with_ffi_boundary("postNotification", 0, || {
        let title = ffi::require_non_null(title, "title")?;
        let text = ffi::require_non_null(text, "text")?;
        Ok(notification::post(id, title.cast(), text.cast()))
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn registerNativeGlobalHotKey(
    native_key_code: c_int,
    native_modifiers: c_int,
    callback: HotKeyPressedCallback,
) -> c_int {
    ffi::with_ffi_boundary(
        "registerNativeGlobalHotKey",
        hotkey::REGISTER_STATUS_INTERNAL_ERROR,
        || {
            let callback = callback.ok_or("callback pointer was null")?;
            Ok(hotkey::register(
                native_key_code as u32,
                native_modifiers as u32,
                Some(callback),
            ) as c_int)
        },
    )
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn unregisterNativeGlobalHotKey(id: c_int) -> bool {
    ffi::with_ffi_boundary("unregisterNativeGlobalHotKey", false, || {
        Ok(hotkey::unregister(id))
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn freePointer(ptr: *mut c_void) {
    if ptr.is_null() {
        return;
    }

    ffi::with_ffi_boundary("freePointer", (), || {
        ffi::free_ptr(ptr);
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::{biometricsTransformSecret, registerNativeGlobalHotKey};
    use crate::hotkey::REGISTER_STATUS_INTERNAL_ERROR;
    use std::ptr;

    #[test]
    fn biometrics_transform_rejects_noncanonical_boolean() {
        // SAFETY: All pointer arguments are null, so the function rejects the
        // noncanonical boolean before attempting to read any foreign memory.
        let result = unsafe { biometricsTransformSecret(0, ptr::null(), ptr::null(), 0, -1, None) };
        assert_eq!(0, result);
    }

    #[test]
    fn register_native_global_hotkey_rejects_null_callback() {
        let result = registerNativeGlobalHotKey(49, 0, None);
        assert_eq!(REGISTER_STATUS_INTERNAL_ERROR, result);
    }
}
