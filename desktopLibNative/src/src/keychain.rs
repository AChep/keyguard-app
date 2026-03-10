use crate::platform;
use std::ffi::c_char;

pub(crate) fn add_password(id: *const c_char, password: *const c_char) -> bool {
    platform::keychain::add_password(id, password)
}

pub(crate) fn get_password(id: *const c_char) -> *mut c_char {
    platform::keychain::get_password(id)
}

pub(crate) fn delete_password(id: *const c_char) -> bool {
    platform::keychain::delete_password(id)
}

pub(crate) fn contains_password(id: *const c_char) -> bool {
    platform::keychain::contains_password(id)
}
