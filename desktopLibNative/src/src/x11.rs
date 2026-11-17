//! Xlib declarations shared by the Linux modules.

use libc::{c_char, c_int};

#[repr(C)]
pub(crate) struct Display {
    _private: [u8; 0],
}

#[link(name = "X11")]
unsafe extern "C" {
    pub(crate) fn XInitThreads() -> c_int;
    pub(crate) fn XOpenDisplay(display_name: *const c_char) -> *mut Display;
    pub(crate) fn XCloseDisplay(display: *mut Display) -> c_int;
}
