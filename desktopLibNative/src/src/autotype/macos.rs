use super::Destination;
use std::ffi::c_void;
use std::ptr::NonNull;

// Owned, retained Objective-C object.
pub(super) struct Target(NonNull<c_void>);

// SAFETY: Rust never dereferences the handle. The shim only holds thread-safe
// objects behind it and marshals the calls that need the AppKit thread itself.
unsafe impl Send for Target {}

unsafe extern "C" {
    safe fn kg_autotype_permission() -> bool;
    // Returns an owned target that must be released exactly once.
    safe fn kg_autotype_capture() -> Option<NonNull<c_void>>;
    fn kg_autotype_release(target: NonNull<c_void>);
    fn kg_autotype_activate(target: NonNull<c_void>) -> bool;
    fn kg_autotype_focused(target: NonNull<c_void>) -> bool;
    safe fn kg_autotype_keys_released() -> bool;
    fn kg_autotype_character(units: *const u16, count: usize, tab: bool) -> bool;
}
pub(super) fn permission() -> bool {
    // The shim marshals permission UI to the main thread.
    kg_autotype_permission()
}
pub(super) fn capture() -> Option<Target> {
    kg_autotype_capture().map(Target)
}
impl Drop for Target {
    fn drop(&mut self) {
        // SAFETY: This handle was returned by capture and is owned solely by this value.
        unsafe {
            kg_autotype_release(self.0);
        }
    }
}
impl Destination for Target {
    fn activate(&mut self) -> bool {
        // SAFETY: A live owned opaque target; the shim handles thread affinity.
        unsafe { kg_autotype_activate(self.0) }
    }
    fn focused(&mut self) -> bool {
        // SAFETY: A live owned opaque target; the probe runs on this thread.
        unsafe { kg_autotype_focused(self.0) }
    }
    fn keys_released(&mut self) -> bool {
        kg_autotype_keys_released()
    }
    fn press(&mut self, character: char) -> bool {
        let mut buffer = [0; 2];
        let units = character.encode_utf16(&mut buffer);
        // SAFETY: The UTF-16 slice remains readable for the synchronous call.
        unsafe { kg_autotype_character(units.as_ptr(), units.len(), character == '\t') }
    }
}
