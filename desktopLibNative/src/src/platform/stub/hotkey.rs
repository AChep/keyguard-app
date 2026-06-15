use crate::ffi::HotKeyPressedCallback;
use crate::hotkey::REGISTER_STATUS_UNSUPPORTED_PLATFORM;

pub(crate) fn register(_key_code: u32, _modifiers: u32, _callback: HotKeyPressedCallback) -> i32 {
    REGISTER_STATUS_UNSUPPORTED_PLATFORM
}

pub(crate) fn unregister(_id: i32) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::{register, unregister};
    use crate::hotkey::REGISTER_STATUS_UNSUPPORTED_PLATFORM;

    #[test]
    fn register_reports_unsupported_platform_by_default() {
        assert_eq!(register(49, 768, None), REGISTER_STATUS_UNSUPPORTED_PLATFORM);
    }

    #[test]
    fn unregister_returns_false_by_default() {
        assert!(!unregister(1));
    }
}
