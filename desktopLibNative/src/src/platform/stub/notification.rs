use std::ffi::c_char;
use std::ffi::c_int;

pub(crate) fn post(_id: c_int, _title: *const c_char, _text: *const c_char) -> c_int {
    0
}

#[cfg(test)]
mod tests {
    use super::post;
    use std::ptr;

    #[test]
    fn returns_zero_by_default() {
        assert_eq!(post(1, ptr::null(), ptr::null()), 0);
    }
}
