use windows::Win32::Globalization::LCIDToLocaleName;
use windows::Win32::UI::Input::KeyboardAndMouse::GetKeyboardLayout;
use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};

// LOCALE_NAME_MAX_LENGTH, including the terminating NUL.
const LOCALE_NAME_CAPACITY: usize = 85;

pub(super) fn input_language() -> Option<String> {
    // SAFETY: No arguments; returns a borrowed window handle, or null.
    let window = unsafe { GetForegroundWindow() };
    // Each GUI thread has its own layout. Thread 0 is the calling thread,
    // whose layout does not follow the user's switches; it is only a fallback.
    let thread_id = if window.is_invalid() {
        0
    } else {
        // SAFETY: The handle is passed by value and only read; a stale handle
        // makes the call return 0. No process id output is requested.
        unsafe { GetWindowThreadProcessId(window, None) }
    };
    // SAFETY: The thread id is a plain value that the system validates.
    let layout = unsafe { GetKeyboardLayout(thread_id) };
    let lcid = lcid_from_layout(layout.0 as usize);
    let mut name = [0_u16; LOCALE_NAME_CAPACITY];
    // SAFETY: The buffer is live and writable for the length passed with it.
    let written = unsafe { LCIDToLocaleName(lcid, Some(&mut name), 0) };
    locale_name(&name, written)
}

/// The low word of a keyboard layout handle is its input language id, which
/// is also a valid LCID: `MAKELCID(id, SORT_DEFAULT)` with `SORT_DEFAULT = 0`.
fn lcid_from_layout(layout: usize) -> u32 {
    (layout & 0xFFFF) as u32
}

/// Decodes a name that LCIDToLocaleName wrote, `written` counting its NUL.
fn locale_name(buffer: &[u16], written: i32) -> Option<String> {
    let length = usize::try_from(written).ok()?.checked_sub(1)?;
    String::from_utf16(buffer.get(..length)?).ok()
}

#[cfg(test)]
mod tests {
    use super::{lcid_from_layout, locale_name};

    #[test]
    fn lcid_from_layout_keeps_language_id_with_default_sort() {
        assert_eq!(0x0419, lcid_from_layout(0x0419_0419));
        // An IME layout handle, Japanese input language.
        assert_eq!(0x0411, lcid_from_layout(0xE001_0411));
        assert_eq!(0x0409, lcid_from_layout(0xF002_0409));
    }

    #[test]
    fn locale_name_drops_terminating_nul() {
        let buffer: Vec<u16> = "ru-RU\0".encode_utf16().collect();
        assert_eq!(Some("ru-RU".to_owned()), locale_name(&buffer, 6));
    }

    #[test]
    fn locale_name_rejects_failed_or_out_of_range_lengths() {
        let buffer: Vec<u16> = "en\0".encode_utf16().collect();
        assert_eq!(None, locale_name(&buffer, 0));
        assert_eq!(None, locale_name(&buffer, -1));
        assert_eq!(None, locale_name(&buffer, 8));
    }
}
