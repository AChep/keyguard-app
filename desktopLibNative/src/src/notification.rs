use std::ffi::c_char;
use std::ffi::c_int;

#[cfg_attr(target_os = "macos", path = "notification/macos.rs")]
#[cfg_attr(not(target_os = "macos"), path = "notification/stub.rs")]
mod imp;

pub(crate) fn post(id: c_int, title: *const c_char, text: *const c_char) -> c_int {
    imp::post(id, title, text)
}
