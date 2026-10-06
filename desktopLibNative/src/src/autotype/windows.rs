use super::Destination;
use windows::core::Owned;
use windows::Win32::Foundation::{FILETIME, HWND};
use windows::Win32::System::Threading::{
    GetProcessTimes, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP,
    KEYEVENTF_UNICODE, VIRTUAL_KEY, VK_DBE_ALPHANUMERIC, VK_DBE_NOROMAN, VK_HANJA, VK_KANA,
    VK_PACKET, VK_TAB,
};
use windows::Win32::UI::WindowsAndMessaging::{
    GetAncestor, GetForegroundWindow, GetGUIThreadInfo, GetWindowThreadProcessId, IsWindow,
    SetForegroundWindow, GA_ROOT, GUITHREADINFO,
};

pub(super) struct Target {
    window: HWND,
    pid: u32,
    created: u64,
}

// SAFETY: A window handle identifies a window system-wide; it is not a pointer
// into memory owned by the capturing thread. Every use validates the window first.
unsafe impl Send for Target {}

pub(super) fn permission() -> bool {
    true
}

fn owner(window: HWND) -> Option<u32> {
    // SAFETY: The handle is borrowed and the output buffer lives for the call.
    unsafe {
        if !IsWindow(Some(window)).as_bool() {
            return None;
        }
        let mut pid = 0;
        GetWindowThreadProcessId(window, Some(&mut pid));
        (pid != 0).then_some(pid)
    }
}

fn created(pid: u32) -> Option<u64> {
    // SAFETY: Opening a process by ID has no memory-safety preconditions.
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) }.ok()?;
    // SAFETY: OpenProcess returned a uniquely owned process handle, and Owned
    // closes it exactly once with CloseHandle.
    let process = unsafe { Owned::new(process) };
    let mut creation = FILETIME::default();
    let mut exit = FILETIME::default();
    let mut kernel = FILETIME::default();
    let mut user = FILETIME::default();
    // SAFETY: The handle is live and all output buffers are initialized and live for the call.
    unsafe { GetProcessTimes(*process, &mut creation, &mut exit, &mut kernel, &mut user) }.ok()?;
    Some((u64::from(creation.dwHighDateTime) << 32) | u64::from(creation.dwLowDateTime))
}

pub(super) fn capture() -> Option<Target> {
    // SAFETY: No arguments; returns a borrowed window handle.
    let window = unsafe { GetForegroundWindow() };
    let pid = owner(window).filter(|pid| *pid != std::process::id())?;
    let created = created(pid)?;
    Some(Target {
        window,
        pid,
        created,
    })
}

impl Target {
    // Input is bound to the window and its owner, never to the title: titles
    // change on their own (unread counters, edited documents).
    fn owned(&self) -> bool {
        owner(self.window) == Some(self.pid)
    }
    // Also rejects a reused process ID. Checked once, before activation; the
    // owner cannot exit and be replaced between two characters.
    fn matches(&self) -> bool {
        self.owned() && created(self.pid) == Some(self.created)
    }
}

// Keys that never block typing.
fn ignored(key: VIRTUAL_KEY) -> bool {
    // Async state also reflects injected input. Only injected Unicode,
    // including our own characters, produces VK_PACKET; no physical key does.
    // Japanese and Korean layouts send some lock and input-mode keys without a
    // key-up, so they can read as held indefinitely. macOS ignores Caps Lock likewise.
    matches!(key, VK_PACKET | VK_KANA | VK_HANJA)
        || (VK_DBE_ALPHANUMERIC.0..=VK_DBE_NOROMAN.0).contains(&key.0)
}

impl Destination for Target {
    fn activate(&mut self) -> bool {
        if !self.matches() {
            return false;
        }
        // SAFETY: This borrowed handle was just checked. Activation may be denied
        // or delayed even when it eventually succeeds; `focused` confirms it.
        let _ = unsafe { SetForegroundWindow(self.window) };
        true
    }
    fn focused(&mut self) -> bool {
        if !self.owned() {
            return false;
        }
        // SAFETY: Initialized GUI thread info has the required size. No pointers escape.
        unsafe {
            if GetForegroundWindow() != self.window {
                return false;
            }
            let mut info = GUITHREADINFO {
                cbSize: size_of::<GUITHREADINFO>() as u32,
                ..Default::default()
            };
            if GetGUIThreadInfo(0, &mut info).is_err() {
                return false;
            }
            // Hosted content (UWP behind ApplicationFrameHost) keeps keyboard focus
            // on another thread, so the foreground thread may report no focus window.
            // Reject only a focus window that belongs to a different top-level window.
            info.hwndFocus.is_invalid() || GetAncestor(info.hwndFocus, GA_ROOT) == self.window
        }
    }
    fn keys_released(&mut self) -> bool {
        // Include the trigger key, modifiers, and mouse buttons (1, 2, 4-6).
        // Never synthesize releases of user-held keys.
        (1..=254)
            .map(VIRTUAL_KEY)
            .filter(|key| !ignored(*key))
            .all(|key| {
                // SAFETY: Values are valid virtual-key codes; only the current-state high bit is read.
                unsafe { GetAsyncKeyState(i32::from(key.0)) >= 0 }
            })
    }
    fn press(&mut self, character: char) -> bool {
        send_character_with(character, send_events)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Key {
    Unicode(u16),
    Virtual(VIRTUAL_KEY),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct KeyEvent {
    key: Key,
    key_up: bool,
}

impl KeyEvent {
    fn input(self) -> INPUT {
        let (virtual_key, scan, mut flags) = match self.key {
            Key::Unicode(unit) => (VIRTUAL_KEY(0), unit, KEYEVENTF_UNICODE),
            Key::Virtual(key) => (key, 0, Default::default()),
        };
        if self.key_up {
            flags |= KEYEVENTF_KEYUP;
        }
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: virtual_key,
                    wScan: scan,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        }
    }
}

fn send_events(events: &[KeyEvent]) -> usize {
    // Each batch contains one Unicode scalar (at most two UTF-16 units)
    // or one control key. Keep both surrogate units in the same OS call.
    let mut inputs = [INPUT::default(); 4];
    for (input, event) in inputs.iter_mut().zip(events) {
        *input = event.input();
    }
    // SAFETY: The slice contains initialized keyboard INPUTs, stays alive
    // for the call, and uses the platform ABI's actual INPUT size.
    unsafe { SendInput(&inputs[..events.len()], size_of::<INPUT>() as i32) as usize }
}

/// Sends one character. Returns false unless every event was accepted.
fn send_character_with(ch: char, mut send: impl FnMut(&[KeyEvent]) -> usize) -> bool {
    let (events, count) = character_events(ch);
    let events = &events[..count];
    let accepted = send(events);
    if accepted < events.len() {
        // Release accepted key-downs that have no matching accepted key-up.
        // A supplementary character can leave both surrogate units down.
        // Never replay text or release keys physically held by the user.
        let accepted_events = &events[..accepted];
        let mut releases = [events[0]; 2];
        let mut count = 0;
        for event in accepted_events.iter().filter(|event| !event.key_up) {
            let release = KeyEvent {
                key_up: true,
                ..*event
            };
            if !accepted_events.contains(&release) {
                releases[count] = release;
                count += 1;
            }
        }
        if count != 0 {
            let _ = send(&releases[..count]);
        }
    }
    accepted == events.len()
}

fn character_events(ch: char) -> ([KeyEvent; 4], usize) {
    let mut events = [KeyEvent {
        key: Key::Unicode(0),
        key_up: false,
    }; 4];
    // Tab is the only control character: it separates the login fields.
    if ch == '\t' {
        events[0].key = Key::Virtual(VK_TAB);
        events[1] = KeyEvent {
            key_up: true,
            ..events[0]
        };
        return (events, 2);
    }
    let mut buffer = [0; 2];
    let units = ch.encode_utf16(&mut buffer);
    // Keep surrogate key-downs adjacent. Some text controls discard a pending
    // high surrogate if its key-up arrives before the low surrogate key-down.
    for (index, &unit) in units.iter().enumerate() {
        events[index] = KeyEvent {
            key: Key::Unicode(unit),
            key_up: false,
        };
        events[index + units.len()] = KeyEvent {
            key_up: true,
            ..events[index]
        };
    }
    (events, units.len() * 2)
}

#[cfg(test)]
#[path = "windows/tests.rs"]
mod tests;
