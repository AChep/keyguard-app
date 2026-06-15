use crate::ffi::HotKeyPressedCallback;

unsafe extern "C" {
    fn kg_register_native_global_hotkey(
        native_key_code: u32,
        native_modifiers: u32,
        callback: HotKeyPressedCallback,
    ) -> i32;

    fn kg_unregister_native_global_hotkey(id: i32) -> bool;
}

pub(crate) fn register(key_code: u32, modifiers: u32, callback: HotKeyPressedCallback) -> i32 {
    unsafe { kg_register_native_global_hotkey(key_code, modifiers, callback) }
}

pub(crate) fn unregister(id: i32) -> bool {
    unsafe { kg_unregister_native_global_hotkey(id) }
}
