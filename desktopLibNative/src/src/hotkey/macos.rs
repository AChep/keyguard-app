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
    // SAFETY: The scalar arguments use matching fixed-width C ABI types. Any
    // callback comes from the FFI registration contract and its owner retains
    // it until successful unregister. The shim serializes callback delivery
    // and unregister on the main thread, so they cannot overlap there.
    unsafe { kg_register_native_global_hotkey(key_code, modifiers, callback) }
}

pub(crate) fn unregister(id: i32) -> bool {
    // SAFETY: The Objective-C shim accepts the registration id by value with
    // the declared C ABI and does not access Rust-managed memory.
    unsafe { kg_unregister_native_global_hotkey(id) }
}
