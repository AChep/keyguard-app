unsafe extern "C" {
    fn kg_get_system_accent_color() -> i32;
}

pub(crate) fn get_system_accent_color() -> i32 {
    // SAFETY: The Objective-C shim takes no arguments, returns a plain integer,
    // and transfers no ownership.
    unsafe { kg_get_system_accent_color() }
}
