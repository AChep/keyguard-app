use std::ffi::c_char;
use std::ptr;

pub(crate) fn add_password(_id: *const c_char, _password: *const c_char) -> bool {
    false
}

pub(crate) fn get_password(_id: *const c_char) -> *mut c_char {
    ptr::null_mut()
}

pub(crate) fn delete_password(_id: *const c_char) -> bool {
    false
}

pub(crate) fn contains_password(_id: *const c_char) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::{add_password, contains_password, delete_password, get_password};
    use std::ptr;

    #[test]
    fn returns_stubbed_keychain_results() {
        assert!(!add_password(ptr::null(), ptr::null()));
        assert!(get_password(ptr::null()).is_null());
        assert!(!delete_password(ptr::null()));
        assert!(!contains_password(ptr::null()));
    }
}
