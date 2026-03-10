#![allow(non_snake_case)]

mod autotype;
mod biometrics;
mod ffi;
mod keychain;
mod notification;
mod platform;

use ffi::BiometricsVerifyCallback;
use std::ffi::c_char;
use std::ffi::c_int;
use std::ffi::c_void;
use std::ptr;

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn autoType(payload: *const c_char) -> bool {
    ffi::with_redacted_ffi_boundary("autoType", false, || {
        let payload = ffi::require_string(payload, "payload")?;
        autotype::execute(&payload)?;
        Ok(true)
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn biometricsIsSupported() -> bool {
    ffi::with_ffi_boundary("biometricsIsSupported", false, || {
        Ok(biometrics::is_supported())
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn biometricsVerify(title: *const c_char, callback: BiometricsVerifyCallback) {
    ffi::with_ffi_boundary("biometricsVerify", (), || {
        let title = ffi::require_non_null(title, "title")?;
        biometrics::verify(title.cast(), callback);
        Ok(())
    });
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
pub extern "C" fn freePointer(ptr: *mut c_void) {
    if ptr.is_null() {
        return;
    }

    ffi::with_ffi_boundary("freePointer", (), || {
        ffi::free_ptr(ptr);
        Ok(())
    });
}
