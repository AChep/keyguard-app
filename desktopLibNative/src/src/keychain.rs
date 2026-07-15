use std::ffi::c_char;

#[cfg_attr(target_os = "macos", path = "keychain/macos.rs")]
#[cfg_attr(not(target_os = "macos"), path = "keychain/stub.rs")]
mod imp;

pub(crate) fn add_password(id: *const c_char, password: *const c_char) -> bool {
    imp::add_password(id, password)
}

pub(crate) fn get_password(id: *const c_char) -> *mut c_char {
    imp::get_password(id)
}

pub(crate) fn delete_password(id: *const c_char) -> bool {
    imp::delete_password(id)
}

pub(crate) fn contains_password(id: *const c_char) -> bool {
    imp::contains_password(id)
}
