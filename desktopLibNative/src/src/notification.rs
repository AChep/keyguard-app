use crate::platform;
use std::ffi::c_char;
use std::ffi::c_int;

pub(crate) fn post(id: c_int, title: *const c_char, text: *const c_char) -> c_int {
    platform::notification::post(id, title, text)
}
