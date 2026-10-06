use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};
use windows::Win32::Foundation::HWND;
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP,
    KEYEVENTF_UNICODE, VIRTUAL_KEY, VK_BACK, VK_CONTROL, VK_LWIN, VK_MENU, VK_RETURN, VK_RWIN,
    VK_SHIFT, VK_TAB,
};
use windows::Win32::UI::WindowsAndMessaging::GetForegroundWindow;

const CHARACTER_DELAY: Duration = Duration::from_millis(20);
const MODIFIER_POLL_INTERVAL: Duration = Duration::from_millis(10);
const MODIFIER_TIMEOUT: Duration = Duration::from_secs(1);
static EXECUTION_LOCK: Mutex<()> = Mutex::new(());

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

trait InputPlatform {
    fn foreground_window(&mut self) -> HWND;
    fn modifiers_pressed(&mut self) -> bool;
    fn send_input(&mut self, events: &[KeyEvent]) -> usize;
    fn elapsed(&self) -> Duration;
    fn sleep(&mut self, duration: Duration);
}

struct WindowsInput {
    started: Instant,
}

impl InputPlatform for WindowsInput {
    fn foreground_window(&mut self) -> HWND {
        // SAFETY: This API takes no pointers and returns a borrowed window handle.
        unsafe { GetForegroundWindow() }
    }

    fn modifiers_pressed(&mut self) -> bool {
        [VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN]
            .into_iter()
            .any(|key| {
                // SAFETY: Each value is a valid virtual-key code. Only the high
                // bit describes the current state; the low bit is not reliable.
                unsafe { GetAsyncKeyState(i32::from(key.0)) < 0 }
            })
    }

    fn send_input(&mut self, events: &[KeyEvent]) -> usize {
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

    fn elapsed(&self) -> Duration {
        self.started.elapsed()
    }

    fn sleep(&mut self, duration: Duration) {
        thread::sleep(duration);
    }
}

pub(crate) fn execute(payload: &str) -> Result<(), String> {
    if payload.is_empty() {
        return Ok(());
    }
    execute_with(
        payload,
        &mut WindowsInput {
            started: Instant::now(),
        },
        &EXECUTION_LOCK,
    )
}

fn execute_with(
    payload: &str,
    platform: &mut impl InputPlatform,
    execution_lock: &Mutex<()>,
) -> Result<(), String> {
    // Validate the entire payload before sending even its first character.
    if payload
        .chars()
        .any(|ch| ch.is_control() && control_key(ch).is_none())
    {
        return Err("Payload contains an unsupported control character.".to_owned());
    }
    if payload.is_empty() {
        return Ok(());
    }

    // Never queue a second payload: its intended foreground window may have
    // changed by the time the first call finishes. Poisoning also fails closed.
    let _execution = execution_lock
        .try_lock()
        .map_err(|_| "AutoType is already running or unavailable.".to_owned())?;
    let target = platform.foreground_window();
    if target.is_invalid() {
        return Err("No foreground window for AutoType.".to_owned());
    }

    let started_waiting = platform.elapsed();
    loop {
        ensure_target(platform, target)?;
        if !platform.modifiers_pressed() {
            break;
        }
        if platform.elapsed().saturating_sub(started_waiting) >= MODIFIER_TIMEOUT {
            return Err("AutoType modifier keys were not released.".to_owned());
        }
        platform.sleep(MODIFIER_POLL_INTERVAL);
    }

    let mut characters = payload.chars().peekable();
    while let Some(ch) = characters.next() {
        ensure_target(platform, target)?;
        if platform.modifiers_pressed() {
            return Err("A modifier key interrupted AutoType.".to_owned());
        }
        let (events, count) = character_events(ch);
        let events = &events[..count];
        let accepted = platform.send_input(events);
        if accepted != events.len() {
            // Release accepted key-downs that have no matching accepted key-up.
            // A supplementary character can leave both surrogate units down.
            // Never replay text or release keys physically held by the user.
            if accepted < events.len() {
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
                    let _ = platform.send_input(&releases[..count]);
                }
            }
            // SendInput cannot reliably distinguish UIPI blocking from other
            // failures. Do not include payload text or key codes in the error.
            return Err("Failed to send AutoType input.".to_owned());
        }
        if characters.peek().is_some() {
            platform.sleep(CHARACTER_DELAY);
        }
    }
    Ok(())
}

fn ensure_target(platform: &mut impl InputPlatform, target: HWND) -> Result<(), String> {
    // This is a best-effort check: SendInput cannot atomically target an HWND.
    if platform.foreground_window() != target {
        return Err("The foreground window changed during AutoType.".to_owned());
    }
    Ok(())
}

fn control_key(ch: char) -> Option<VIRTUAL_KEY> {
    match ch {
        '\t' => Some(VK_TAB),
        '\u{0008}' => Some(VK_BACK),
        '\r' | '\n' => Some(VK_RETURN),
        _ => None,
    }
}

fn character_events(ch: char) -> ([KeyEvent; 4], usize) {
    let mut events = [KeyEvent {
        key: Key::Unicode(0),
        key_up: false,
    }; 4];
    if let Some(key) = control_key(ch) {
        events[0].key = Key::Virtual(key);
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
