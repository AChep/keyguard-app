use std::ffi::{c_char, CStr};

// Fits every language tag an input source reports, such as `zh-Hans`.
const LANGUAGE_CAPACITY: usize = 64;

unsafe extern "C" {
    fn kg_keyboard_input_language(buffer: *mut c_char, capacity: usize) -> bool;
}

pub(super) fn input_language() -> Option<String> {
    let mut buffer = [0_u8; LANGUAGE_CAPACITY];
    // SAFETY: The buffer is writable for its full length and outlives the
    // call. The shim writes a NUL-terminated string into it only before it
    // returns true, and never touches it after returning.
    let found = unsafe { kg_keyboard_input_language(buffer.as_mut_ptr().cast(), buffer.len()) };
    if !found {
        return None;
    }
    let language = CStr::from_bytes_until_nul(&buffer).ok()?.to_str().ok()?;
    Some(language.to_owned())
}
