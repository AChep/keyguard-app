unsafe extern "C" {
    fn kg_post_keyboard_event(key_code: u16, key_down: bool, flags: u64) -> bool;
}

pub(crate) fn post_keyboard_event(key_code: u16, key_down: bool, flags: u64) -> bool {
    unsafe { kg_post_keyboard_event(key_code, key_down, flags) }
}
