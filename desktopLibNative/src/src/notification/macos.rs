use std::ffi::c_char;
use std::ffi::c_int;

unsafe extern "C" {
    fn kg_post_notification(id: c_int, title: *const c_char, text: *const c_char) -> c_int;
}

pub(crate) fn post(id: c_int, title: *const c_char, text: *const c_char) -> c_int {
    // SAFETY: The exported FFI contract supplies title and text as readable,
    // NUL-terminated strings. The shim copies both before returning and uses
    // matching C ABI types for the scalar values.
    unsafe { kg_post_notification(id, title, text) }
}
