//! Reads the XKB group name of the core keyboard from the X server, so it
//! covers X11 sessions and XWayland windows such as the JVM's.

use crate::x11::{Display, XCloseDisplay, XInitThreads, XOpenDisplay};
use libc::{c_char, c_int, c_uchar, c_uint, c_ulong, c_ushort, c_void};
use std::ffi::CStr;
use std::ptr;

type Atom = c_ulong;
type Status = c_int;

const SUCCESS: Status = 0;
const TRUE: c_int = 1;
const NONE: Atom = 0;
const XKB_USE_CORE_KBD: c_uint = 0x0100;
const XKB_GROUP_NAMES_MASK: c_uint = 1 << 12;
const XKB_NUM_KBD_GROUPS: usize = 4;
const XKB_NUM_VIRTUAL_MODS: usize = 16;
const XKB_NUM_INDICATORS: usize = 32;

// <X11/extensions/XKBstr.h>
#[repr(C)]
#[derive(Default)]
struct XkbStateRec {
    group: c_uchar,
    locked_group: c_uchar,
    base_group: c_ushort,
    latched_group: c_ushort,
    mods: c_uchar,
    base_mods: c_uchar,
    latched_mods: c_uchar,
    locked_mods: c_uchar,
    compat_state: c_uchar,
    grab_mods: c_uchar,
    compat_grab_mods: c_uchar,
    lookup_mods: c_uchar,
    compat_lookup_mods: c_uchar,
    ptr_buttons: c_ushort,
}

#[repr(C)]
struct XkbNamesRec {
    keycodes: Atom,
    geometry: Atom,
    symbols: Atom,
    types: Atom,
    compat: Atom,
    vmods: [Atom; XKB_NUM_VIRTUAL_MODS],
    indicators: [Atom; XKB_NUM_INDICATORS],
    groups: [Atom; XKB_NUM_KBD_GROUPS],
    keys: *mut c_void,
    key_aliases: *mut c_void,
    radio_groups: *mut Atom,
    phys_symbols: Atom,
    num_keys: c_uchar,
    num_key_aliases: c_uchar,
    num_rg: c_ushort,
}

#[repr(C)]
struct XkbDescRec {
    dpy: *mut Display,
    flags: c_ushort,
    device_spec: c_ushort,
    min_key_code: c_uchar,
    max_key_code: c_uchar,
    ctrls: *mut c_void,
    server: *mut c_void,
    map: *mut c_void,
    indicators: *mut c_void,
    names: *mut XkbNamesRec,
    compat: *mut c_void,
    geom: *mut c_void,
}

#[link(name = "X11")]
unsafe extern "C" {
    fn XGetAtomName(display: *mut Display, atom: Atom) -> *mut c_char;
    fn XFree(data: *mut c_void) -> c_int;
    fn XkbGetState(display: *mut Display, device_spec: c_uint, state: *mut XkbStateRec) -> Status;
    fn XkbAllocKeyboard() -> *mut XkbDescRec;
    fn XkbGetNames(display: *mut Display, which: c_uint, xkb: *mut XkbDescRec) -> Status;
    fn XkbFreeKeyboard(xkb: *mut XkbDescRec, which: c_uint, free_all: c_int);
}

struct Connection(*mut Display);

impl Drop for Connection {
    fn drop(&mut self) {
        // SAFETY: The handle was opened by XOpenDisplay, is non-null, and is
        // not used after this call.
        unsafe {
            XCloseDisplay(self.0);
        }
    }
}

struct Keyboard(*mut XkbDescRec);

impl Drop for Keyboard {
    fn drop(&mut self) {
        // SAFETY: The description was allocated by XkbAllocKeyboard, is
        // non-null, and is freed together with its components exactly once.
        unsafe {
            XkbFreeKeyboard(self.0, 0, TRUE);
        }
    }
}

/// Opens a private connection per call and closes it before returning, so no
/// Xlib state is shared with AWT or the hotkey worker. Every request here
/// either returns a status or cannot fail for these arguments, which avoids
/// asynchronous X errors and leaves the process-wide error handler alone.
pub(super) fn input_language() -> Option<String> {
    // SAFETY: XInitThreads takes no pointers and is idempotent. As in the
    // hotkey worker, thread safety does not rest on it: the connection below
    // is confined to this call.
    unsafe {
        XInitThreads();
    }
    // SAFETY: XOpenDisplay accepts null to select the display from the
    // environment and returns either null or an owned live handle.
    let display = unsafe { XOpenDisplay(ptr::null()) };
    if display.is_null() {
        return None;
    }
    let display = Connection(display);

    let mut state = XkbStateRec::default();
    // SAFETY: The display is live and the state is a writable record with the
    // XkbStateRec layout. Without the XKB extension the call returns a
    // failure status instead of sending a request.
    if unsafe { XkbGetState(display.0, XKB_USE_CORE_KBD, &mut state) } != SUCCESS {
        return None;
    }

    // SAFETY: No arguments; returns null or a zeroed description owned by the
    // caller, with its device set to the core keyboard.
    let keyboard = unsafe { XkbAllocKeyboard() };
    if keyboard.is_null() {
        return None;
    }
    let keyboard = Keyboard(keyboard);
    // SAFETY: The display and the description are live. XkbGetNames allocates
    // the names record into the description, which owns it from then on.
    if unsafe { XkbGetNames(display.0, XKB_GROUP_NAMES_MASK, keyboard.0) } != SUCCESS {
        return None;
    }
    // SAFETY: The description is live; its names pointer is null or points to
    // the record that XkbGetNames filled, which lives as long as the description.
    let names = unsafe { (*keyboard.0).names.as_ref() }?;
    let atom = group_name_atom(&names.groups, state.group)?;

    // SAFETY: The display is live and the atom is one the server just named,
    // so the request cannot fail with BadAtom. The result is null or an Xlib
    // allocation owned by the caller.
    let name = unsafe { XGetAtomName(display.0, atom) };
    if name.is_null() {
        return None;
    }
    // SAFETY: XGetAtomName returned a NUL-terminated string that stays live
    // until the XFree below.
    let value = unsafe { CStr::from_ptr(name) }
        .to_string_lossy()
        .into_owned();
    // SAFETY: The string was allocated by Xlib and is freed exactly once.
    unsafe {
        XFree(name.cast());
    }
    Some(value)
}

/// Returns the name of the effective group, or `None` when it is out of range
/// or unnamed.
fn group_name_atom(groups: &[Atom; XKB_NUM_KBD_GROUPS], group: c_uchar) -> Option<Atom> {
    groups
        .get(usize::from(group))
        .copied()
        .filter(|&atom| atom != NONE)
}

#[cfg(test)]
mod tests {
    use super::{group_name_atom, XkbDescRec, XkbNamesRec, XkbStateRec};
    use std::mem::{offset_of, size_of};

    #[test]
    fn group_name_atom_reads_the_effective_group() {
        let groups = [11, 22, 0, 44];
        assert_eq!(Some(11), group_name_atom(&groups, 0));
        assert_eq!(Some(44), group_name_atom(&groups, 3));
    }

    #[test]
    fn group_name_atom_rejects_unnamed_or_out_of_range_groups() {
        let groups = [11, 22, 0, 44];
        assert_eq!(None, group_name_atom(&groups, 2));
        assert_eq!(None, group_name_atom(&groups, 4));
        assert_eq!(None, group_name_atom(&groups, u8::MAX));
    }

    // Offsets of the C structs on LP64, as compiled from <X11/XKBlib.h>.
    #[cfg(target_pointer_width = "64")]
    #[test]
    fn xkb_records_match_the_c_layout() {
        assert_eq!(18, size_of::<XkbStateRec>());
        assert_eq!(16, offset_of!(XkbStateRec, ptr_buttons));
        assert_eq!(424, offset_of!(XkbNamesRec, groups));
        assert_eq!(496, size_of::<XkbNamesRec>());
        assert_eq!(48, offset_of!(XkbDescRec, names));
        assert_eq!(72, size_of::<XkbDescRec>());
    }
}
