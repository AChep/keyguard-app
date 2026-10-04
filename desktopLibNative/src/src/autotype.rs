#[cfg_attr(target_os = "macos", path = "autotype/macos.rs")]
#[cfg_attr(target_os = "windows", path = "autotype/windows.rs")]
#[cfg_attr(
    not(any(target_os = "macos", target_os = "windows")),
    path = "autotype/stub.rs"
)]
mod imp;

pub(crate) fn execute(payload: &str) -> Result<(), String> {
    imp::execute(payload)
}

#[cfg(test)]
mod tests {
    use crate::autoType;
    use std::ffi::CString;

    #[test]
    fn exported_autotype_rejects_null() {
        // SAFETY: The export explicitly accepts null and rejects it before reading.
        assert!(!unsafe { autoType(std::ptr::null()) });
    }

    #[test]
    fn exported_autotype_accepts_empty_without_typing() {
        let payload = CString::new("").unwrap();
        // SAFETY: The readable, NUL-terminated string lives for the entire call.
        assert!(unsafe { autoType(payload.as_ptr()) });
    }

    #[test]
    fn exported_autotype_rejects_control_character_before_typing() {
        let payload = CString::new("test\u{001b}").unwrap();
        // SAFETY: The readable, NUL-terminated string lives for the entire call.
        // Every backend rejects this payload before emitting any keyboard events.
        assert!(!unsafe { autoType(payload.as_ptr()) });
    }
}
