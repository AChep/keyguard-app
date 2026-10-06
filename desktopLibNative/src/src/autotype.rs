//! One-shot, destination-bound login typing. Credential text is never a command sequence.
use std::ffi::CStr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, PoisonError, TryLockError};
use std::thread;
use std::time::{Duration, Instant};

#[cfg_attr(target_os = "windows", path = "autotype/windows.rs")]
#[cfg_attr(target_os = "macos", path = "autotype/macos.rs")]
#[cfg_attr(
    not(any(target_os = "windows", target_os = "macos")),
    path = "autotype/stub.rs"
)]
mod platform;

const POLL: Duration = Duration::from_millis(10);
const RELEASE_TIMEOUT: Duration = Duration::from_secs(2);
const FOCUS_TIMEOUT: Duration = Duration::from_secs(1);
// Pauses after a character at the Fast speed.
const CHARACTER_PAUSE: Duration = Duration::from_millis(20);
const TAB_PAUSE: Duration = Duration::from_millis(100);
const MAX_CHARACTERS: usize = 4096;
static TARGETS: Registry<platform::Target> = Registry::new();

/// Why a login was not typed. The codes are part of the JVM bridge contract,
/// where 0 means success. Never return credential-derived errors.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub(crate) enum Failure {
    Unavailable = 1,
    InvalidText = 2,
    Interrupted = 3,
    InputFailed = 4,
    Busy = 5,
    // A key or mouse button stayed down for the whole release wait.
    KeysHeld = 6,
}

/// The discriminant is the JVM bridge delay multiplier.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Speed {
    Fast = 1,
    Normal = 2,
    Slow = 4,
}

impl TryFrom<i32> for Speed {
    type Error = String;

    fn try_from(multiplier: i32) -> Result<Self, Self::Error> {
        [Self::Fast, Self::Normal, Self::Slow]
            .into_iter()
            .find(|speed| *speed as i32 == multiplier)
            .ok_or_else(|| "Unsupported AutoType delay multiplier.".to_owned())
    }
}

impl Speed {
    fn pause_after(self, character: char) -> Duration {
        let pause = if character == '\t' {
            TAB_PAUSE
        } else {
            CHARACTER_PAUSE
        };
        pause * self as u32
    }

    fn startup_delay(self) -> Duration {
        Duration::from_millis(match self {
            Self::Fast => 200,
            Self::Normal => 400,
            Self::Slow => 600,
        })
    }
}

pub(crate) fn permission() -> bool {
    platform::permission()
}

pub(crate) fn capture() -> u64 {
    TARGETS.capture(platform::capture)
}

/// Returns 0 after typing the whole login, or a [`Failure`] code.
pub(crate) fn execute(
    id: u64,
    username: &CStr,
    password: &CStr,
    speed: Speed,
    active: impl Fn() -> bool,
) -> i32 {
    let result = match (username.to_str(), password.to_str()) {
        (Ok(username), Ok(password)) => TARGETS.execute(id, username, password, speed, active),
        _ => Err(Failure::InvalidText),
    };
    result.map_or_else(|failure| failure as i32, |()| 0)
}

/// Holds the latest captured destination. Every capture invalidates older tokens.
struct Registry<T> {
    generation: AtomicU64,
    // Each critical section is a single replace or take, so a poisoned value is still consistent.
    target: Mutex<Option<(u64, T)>>,
    // Never queue a second login. Acquired only after validating the input.
    execution: Mutex<()>,
}

impl<T: Destination> Registry<T> {
    const fn new() -> Self {
        Self {
            generation: AtomicU64::new(0),
            target: Mutex::new(None),
            execution: Mutex::new(()),
        }
    }

    fn capture(&self, capture: impl FnOnce() -> Option<T>) -> u64 {
        let id = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        let target = capture().map(|target| (id, target));
        let mut slot = self.target.lock().unwrap_or_else(PoisonError::into_inner);
        // A slower capture must not overwrite a more recent request.
        if self.generation.load(Ordering::SeqCst) != id {
            return 0;
        }
        let id = target.as_ref().map_or(0, |(id, _)| *id);
        // Keep the previous context alive until after the registry is unlocked.
        let _previous = std::mem::replace(&mut *slot, target);
        drop(slot);
        id
    }

    fn execute(
        &self,
        id: u64,
        username: &str,
        password: &str,
        speed: Speed,
        active: impl Fn() -> bool,
    ) -> Result<(), Failure> {
        if !valid_text(username)
            || !valid_text(password)
            || (username.is_empty() && password.is_empty())
        {
            return Err(Failure::InvalidText);
        }
        let _execution = match self.execution.try_lock() {
            Ok(execution) => execution,
            // The lock guards no data, so a panic during an earlier login leaves nothing to repair.
            Err(TryLockError::Poisoned(poisoned)) => poisoned.into_inner(),
            Err(TryLockError::WouldBlock) => return Err(Failure::Busy),
        };
        // A token is consumed by its first use. Tokens start at 1, so 0 never matches.
        let mut target = self
            .target
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take_if(|(token, _)| *token == id)
            .map(|(_, target)| target)
            .ok_or(Failure::Unavailable)?;
        let active = || active() && self.generation.load(Ordering::SeqCst) == id;
        execute_with(&mut target, username, password, speed, active)
    }
}

fn valid_text(text: &str) -> bool {
    // Line and paragraph separators act like Enter in multi-line fields.
    let typeable = |c: char| !c.is_control() && !matches!(c, '\u{2028}' | '\u{2029}');
    let mut characters = text.chars();
    characters.by_ref().take(MAX_CHARACTERS).all(typeable) && characters.next().is_none()
}

trait Destination {
    // Requests focus. The OS may refuse or delay it, so this fails only when the
    // destination is gone or changed; `focused` confirms the outcome.
    fn activate(&mut self) -> bool;
    fn focused(&mut self) -> bool;
    // No physical key or mouse button is held. A click can switch tabs inside the same window.
    fn keys_released(&mut self) -> bool;
    fn press(&mut self, character: char) -> bool;
    fn now(&mut self) -> Instant {
        Instant::now()
    }
    fn wait(&mut self, duration: Duration) {
        thread::sleep(duration);
    }
}

fn execute_with(
    target: &mut impl Destination,
    username: &str,
    password: &str,
    speed: Speed,
    active: impl Fn() -> bool,
) -> Result<(), Failure> {
    if !active() {
        return Err(Failure::Interrupted);
    }
    // Wait for the complete triggering chord (including its letter key) before restoring focus.
    // A stuck key is not a cancellation: the user needs to know why nothing was typed.
    wait_until(
        target,
        &active,
        RELEASE_TIMEOUT,
        Failure::KeysHeld,
        |target| target.keys_released(),
    )?;
    if !active() {
        return Err(Failure::Interrupted);
    }
    if !target.activate() {
        return Err(Failure::Unavailable);
    }
    wait_until(
        target,
        &active,
        FOCUS_TIMEOUT,
        Failure::Unavailable,
        |target| target.focused(),
    )?;
    // Let the destination process its activation before the first character.
    settle(target, speed.startup_delay(), &active)?;
    let tab = (!username.is_empty() && !password.is_empty()).then_some('\t');
    let mut characters = username
        .chars()
        .chain(tab)
        .chain(password.chars())
        .peekable();
    while let Some(character) = characters.next() {
        if !target.press(character) {
            return Err(Failure::InputFailed);
        }
        // Activity after the final character cannot change what was typed.
        if characters.peek().is_some() {
            settle(target, speed.pause_after(character), &active)?;
        }
    }
    Ok(())
}

// Polls until the probe passes. Fails with `on_timeout` once the deadline is
// reached, or with `Interrupted` when the session retires while waiting.
fn wait_until<T: Destination>(
    target: &mut T,
    active: &impl Fn() -> bool,
    timeout: Duration,
    on_timeout: Failure,
    probe: impl Fn(&mut T) -> bool,
) -> Result<(), Failure> {
    let deadline = target.now() + timeout;
    while !probe(target) {
        if !active() {
            return Err(Failure::Interrupted);
        }
        if target.now() >= deadline {
            return Err(on_timeout);
        }
        target.wait(POLL);
    }
    Ok(())
}

fn ensure_ready(target: &mut impl Destination, active: &impl Fn() -> bool) -> Result<(), Failure> {
    // A native focus probe can block. Recheck the vault lifetime after it returns.
    if !active() || !target.focused() || !target.keys_released() || !active() {
        return Err(Failure::Interrupted);
    }
    Ok(())
}

// Check throughout each pause and stop on the first observed interruption.
// Returns right after a passing check, so input may follow without another one.
// Polling is best effort: activity between probes and focus changes between
// validation and input injection can still be missed.
fn settle(
    target: &mut impl Destination,
    duration: Duration,
    active: &impl Fn() -> bool,
) -> Result<(), Failure> {
    // Include time spent in native probes in the requested pause.
    let deadline = target.now() + duration;
    loop {
        let remaining = deadline.saturating_duration_since(target.now());
        target.wait(POLL.min(remaining));
        ensure_ready(target, active)?;
        if target.now() >= deadline {
            return Ok(());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::{Cell, RefCell};
    use std::rc::Rc;

    #[derive(Default)]
    struct Fake {
        typed: String,
        press_times: Vec<Duration>,
        waits: Vec<Duration>,
        lose_focus_after: Option<usize>,
        hold_key_after: Option<usize>,
        refuse_input_after: Option<usize>,
        retire_during_probe: Option<Rc<Cell<bool>>>,
        retire_on_activate: Option<Rc<Cell<bool>>>,
        epoch: Option<Instant>,
        clock: Duration,
        focus_probe_delay: Duration,
        focus_loss: Option<(Duration, Duration)>,
        click: Option<(Duration, Duration)>,
    }
    impl Destination for Fake {
        fn activate(&mut self) -> bool {
            if let Some(active) = &self.retire_on_activate {
                active.set(false);
            }
            true
        }
        fn focused(&mut self) -> bool {
            self.clock += self.focus_probe_delay;
            let focus_lost = self
                .focus_loss
                .is_some_and(|(start, end)| (start..end).contains(&self.clock));
            self.lose_focus_after != Some(self.typed.len()) && !focus_lost
        }
        fn keys_released(&mut self) -> bool {
            if !self.typed.is_empty() {
                if let Some(active) = &self.retire_during_probe {
                    active.set(false);
                }
            }
            let clicking = self
                .click
                .is_some_and(|(start, end)| (start..end).contains(&self.clock));
            self.hold_key_after != Some(self.typed.len()) && !clicking
        }
        fn press(&mut self, c: char) -> bool {
            if self.refuse_input_after == Some(self.typed.len()) {
                return false;
            }
            self.typed.push(c);
            self.press_times.push(self.clock);
            true
        }
        fn wait(&mut self, duration: Duration) {
            self.waits.push(duration);
            self.clock += duration;
        }
        fn now(&mut self) -> Instant {
            *self.epoch.get_or_insert_with(Instant::now) + self.clock
        }
    }

    #[test]
    fn speed_sets_startup_delay_and_scales_character_and_tab_pauses() {
        for (speed, multiplier, startup) in [
            (Speed::Fast, 1, 200),
            (Speed::Normal, 2, 400),
            (Speed::Slow, 4, 600),
        ] {
            assert_eq!(Speed::try_from(multiplier as i32), Ok(speed));
            let mut target = Fake::default();
            assert_eq!(execute_with(&mut target, "u", "p", speed, || true), Ok(()));
            assert_eq!(target.typed, "u\tp");
            assert_eq!(
                target.press_times,
                [
                    startup,
                    startup + 20 * multiplier,
                    startup + 120 * multiplier
                ]
                .map(Duration::from_millis)
            );
            // No pause follows the final character.
            assert_eq!(
                target.clock,
                Duration::from_millis(startup + 120 * multiplier)
            );
            assert!(target.waits.iter().all(|&duration| duration == POLL));
        }
    }

    #[test]
    fn activity_after_the_final_character_keeps_the_login_typed() {
        for (hold_key_after, lose_focus_after) in [(Some(3), None), (None, Some(3))] {
            let mut target = Fake {
                hold_key_after,
                lose_focus_after,
                ..Fake::default()
            };
            assert_eq!(
                execute_with(&mut target, "u", "p", Speed::Fast, || true),
                Ok(())
            );
            assert_eq!(target.typed, "u\tp");
        }
    }

    #[test]
    fn slow_typing_still_interrupts_on_a_short_click_during_the_tab_pause() {
        let mut target = Fake {
            click: Some((Duration::from_millis(690), Duration::from_millis(710))),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut target, "u", "secret", Speed::Slow, || true),
            Err(Failure::Interrupted)
        );
        assert_eq!(target.typed, "u\t");
        assert_eq!(target.clock, Duration::from_millis(690));
    }

    #[test]
    fn transient_focus_loss_during_each_pause_interrupts_without_resuming() {
        // Startup, character, and Tab pauses respectively. Focus would return
        // before the next character in all three cases without pause checks.
        for (start, end, expected) in [(50, 70, ""), (205, 215, "u"), (250, 270, "u\t")] {
            let mut target = Fake {
                focus_loss: Some((Duration::from_millis(start), Duration::from_millis(end))),
                ..Fake::default()
            };
            assert_eq!(
                execute_with(&mut target, "u", "secret", Speed::Fast, || true),
                Err(Failure::Interrupted)
            );
            assert_eq!(target.typed, expected);
            assert!(target.clock < Duration::from_millis(end));
            target.wait(Duration::from_millis(end));
            assert!(target.focused());
            assert_eq!(target.typed, expected);
        }
    }

    #[test]
    fn native_focus_probe_time_counts_toward_the_pause() {
        for (probe_ms, elapsed_ms, polls) in [(15, 100, 4), (200, 210, 1)] {
            let mut target = Fake {
                focus_probe_delay: Duration::from_millis(probe_ms),
                ..Fake::default()
            };
            assert_eq!(
                settle(&mut target, Duration::from_millis(100), &|| true),
                Ok(())
            );
            assert_eq!(target.clock, Duration::from_millis(elapsed_ms));
            assert_eq!(target.waits, vec![POLL; polls]);
        }
    }

    #[test]
    fn startup_click_or_session_retirement_never_types() {
        let mut clicked = Fake {
            click: Some((Duration::from_millis(50), Duration::from_millis(70))),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut clicked, "u", "secret", Speed::Fast, || true),
            Err(Failure::Interrupted)
        );
        assert!(clicked.typed.is_empty());

        let active = Rc::new(Cell::new(true));
        let mut retired = Fake {
            retire_on_activate: Some(Rc::clone(&active)),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut retired, "u", "secret", Speed::Fast, || active.get()),
            Err(Failure::Interrupted)
        );
        assert!(retired.typed.is_empty());
    }

    #[test]
    fn focus_acquisition_and_trigger_key_waits_remain_bounded() {
        for (hold_key_after, lose_focus_after, status, milliseconds) in [
            (Some(0), None, Failure::KeysHeld, 2000),
            (None, Some(0), Failure::Unavailable, 1000),
        ] {
            let mut target = Fake {
                hold_key_after,
                lose_focus_after,
                ..Fake::default()
            };
            assert_eq!(
                execute_with(&mut target, "u", "secret", Speed::Fast, || true),
                Err(status)
            );
            assert!(target.typed.is_empty());
            assert_eq!(target.clock, Duration::from_millis(milliseconds));
        }
    }

    #[test]
    fn fields_are_separated_only_when_both_are_present() {
        for (username, password, expected) in [
            ("u", "p", "u\tp"),
            ("", "p", "p"),
            ("u", "", "u"),
            ("ю😀", "密碼", "ю😀\t密碼"),
        ] {
            let mut target = Fake::default();
            assert_eq!(
                execute_with(&mut target, username, password, Speed::Fast, || true),
                Ok(())
            );
            assert_eq!(target.typed, expected);
        }
    }
    #[test]
    fn focus_change_stops_before_password() {
        let mut target = Fake {
            lose_focus_after: Some(2),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut target, "u", "secret", Speed::Fast, || true),
            Err(Failure::Interrupted)
        );
        assert_eq!(target.typed, "u\t");
    }
    #[test]
    fn retired_session_never_types() {
        let mut target = Fake::default();
        assert_eq!(
            execute_with(&mut target, "u", "p", Speed::Fast, || false),
            Err(Failure::Interrupted)
        );
        assert!(target.typed.is_empty());
    }

    #[test]
    fn retirement_during_a_native_probe_stops_before_the_next_character() {
        let active = Rc::new(Cell::new(true));
        let mut target = Fake {
            retire_during_probe: Some(Rc::clone(&active)),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut target, "user", "secret", Speed::Fast, || active.get()),
            Err(Failure::Interrupted)
        );
        assert_eq!(target.typed, "u");
    }

    #[test]
    fn retirement_while_waiting_for_focus_is_an_interruption() {
        let active = Rc::new(Cell::new(true));
        let mut target = Fake {
            lose_focus_after: Some(0),
            retire_on_activate: Some(Rc::clone(&active)),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut target, "user", "secret", Speed::Fast, || active.get()),
            Err(Failure::Interrupted)
        );
        assert!(target.typed.is_empty());
    }

    #[test]
    fn physical_key_press_interrupts_without_replaying_text() {
        let mut target = Fake {
            hold_key_after: Some(1),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut target, "user", "secret", Speed::Fast, || true),
            Err(Failure::Interrupted)
        );
        assert_eq!(target.typed, "u");
    }

    #[test]
    fn click_released_before_the_next_character_interrupts() {
        // Typing starts at 200 ms; the pause after Tab spans 220–320 ms.
        let mut target = Fake {
            click: Some((Duration::from_millis(250), Duration::from_millis(270))),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut target, "u", "secret", Speed::Fast, || true),
            Err(Failure::Interrupted)
        );
        assert_eq!(target.typed, "u\t");
    }

    #[test]
    fn refused_input_stops_the_remaining_sequence() {
        let mut target = Fake {
            refuse_input_after: Some(2),
            ..Fake::default()
        };
        assert_eq!(
            execute_with(&mut target, "u", "secret", Speed::Fast, || true),
            Err(Failure::InputFailed)
        );
        assert_eq!(target.typed, "u\t");
    }
    #[derive(Default)]
    struct Recorder(Rc<RefCell<String>>, Fake);
    impl Destination for Recorder {
        fn activate(&mut self) -> bool {
            true
        }
        fn focused(&mut self) -> bool {
            true
        }
        fn keys_released(&mut self) -> bool {
            true
        }
        fn press(&mut self, c: char) -> bool {
            self.0.borrow_mut().push(c);
            true
        }
        fn wait(&mut self, duration: Duration) {
            self.1.wait(duration);
        }
        fn now(&mut self) -> Instant {
            self.1.now()
        }
    }
    fn recorder(typed: &Rc<RefCell<String>>) -> Option<Recorder> {
        Some(Recorder(Rc::clone(typed), Fake::default()))
    }

    #[test]
    fn only_the_latest_token_types_and_only_once() {
        let registry = Registry::new();
        let (old, new) = (Rc::default(), Rc::default());
        let stale = registry.capture(|| recorder(&old));
        let latest = registry.capture(|| recorder(&new));
        assert!(stale != 0 && latest != 0 && stale != latest);
        for id in [0, stale] {
            assert_eq!(
                registry.execute(id, "u", "p", Speed::Fast, || true),
                Err(Failure::Unavailable)
            );
        }
        assert_eq!(
            registry.execute(latest, "u", "p", Speed::Fast, || true),
            Ok(())
        );
        assert_eq!(
            registry.execute(latest, "u", "p", Speed::Fast, || true),
            Err(Failure::Unavailable)
        );
        assert!(old.borrow().is_empty());
        assert_eq!(*new.borrow(), "u\tp");
    }

    #[test]
    fn failed_capture_discards_the_previous_destination() {
        let registry = Registry::new();
        let typed = Rc::default();
        let id = registry.capture(|| recorder(&typed));
        assert_eq!(registry.capture(|| None), 0);
        assert_eq!(
            registry.execute(id, "u", "p", Speed::Fast, || true),
            Err(Failure::Unavailable)
        );
        assert!(typed.borrow().is_empty());
    }

    #[test]
    fn slower_capture_never_replaces_a_newer_one() {
        let registry = Registry::new();
        let (old, new) = (Rc::default(), Rc::default());
        let mut latest = 0;
        let slow = registry.capture(|| {
            latest = registry.capture(|| recorder(&new));
            recorder(&old)
        });
        assert_eq!(slow, 0);
        assert_eq!(
            registry.execute(latest, "u", "p", Speed::Fast, || true),
            Ok(())
        );
        assert_eq!(*new.borrow(), "u\tp");
    }

    #[test]
    fn busy_execution_keeps_the_destination() {
        let registry = Registry::new();
        let typed = Rc::default();
        let id = registry.capture(|| recorder(&typed));
        let held = registry.execution.lock().unwrap();
        assert_eq!(
            registry.execute(id, "u", "p", Speed::Fast, || true),
            Err(Failure::Busy)
        );
        drop(held);
        assert_eq!(registry.execute(id, "u", "p", Speed::Fast, || true), Ok(()));
        assert_eq!(*typed.borrow(), "u\tp");
    }

    #[test]
    fn a_panic_while_holding_the_locks_never_disables_typing() {
        let registry = Registry::new();
        let typed = Rc::default();
        let _ = std::panic::catch_unwind(|| {
            let _target = registry.target.lock();
            let _execution = registry.execution.lock();
            panic!("poison both locks");
        });
        assert!(registry.target.is_poisoned() && registry.execution.is_poisoned());
        let id = registry.capture(|| recorder(&typed));
        assert_eq!(registry.execute(id, "u", "p", Speed::Fast, || true), Ok(()));
        assert_eq!(*typed.borrow(), "u\tp");
    }

    #[test]
    fn newer_capture_interrupts_typing() {
        let registry = Registry::new();
        let typed = Rc::default();
        let id = registry.capture(|| recorder(&typed));
        let recaptured = Cell::new(false);
        let active = || {
            if typed.borrow().len() == 1 && !recaptured.replace(true) {
                registry.capture(|| recorder(&Rc::default()));
            }
            true
        };
        assert_eq!(
            registry.execute(id, "user", "secret", Speed::Fast, active),
            Err(Failure::Interrupted)
        );
        assert_eq!(*typed.borrow(), "u");
    }

    #[test]
    fn rejects_commands_inside_fields() {
        for text in [
            "a\tb",
            "a\nb",
            "a\rb",
            "a\u{8}b",
            "a\0b",
            "a\u{7f}b",
            "a\u{85}b",
            "a\u{2028}b",
            "a\u{2029}b",
        ] {
            assert!(!valid_text(text));
        }
        assert!(!valid_text(&"a".repeat(4097)));
        assert!(valid_text(&"😀".repeat(4096)));
        assert!(valid_text("密碼😀{ENTER}"));
    }

    extern "C" fn exported_active() -> i32 {
        1
    }

    #[test]
    fn export_rejects_null_fields() {
        let text = std::ffi::CString::new("u").unwrap();
        for (username, password) in [
            (std::ptr::null(), text.as_ptr()),
            (text.as_ptr(), std::ptr::null()),
        ] {
            // SAFETY: The export rejects null before reading; the other buffer outlives the call.
            let status =
                unsafe { crate::autoTypeLogin(0, username, password, 1, Some(exported_active)) };
            assert_eq!(status, Failure::InputFailed as i32);
        }
    }

    #[test]
    fn export_rejects_invalid_utf8_as_invalid_text() {
        let valid = std::ffi::CString::new("u").unwrap();
        let invalid = std::ffi::CString::new([b'u', 0xff]).unwrap();
        for (username, password) in [(&invalid, &valid), (&valid, &invalid)] {
            // SAFETY: Both buffers and the callback remain valid throughout the call.
            let status = unsafe {
                crate::autoTypeLogin(
                    0,
                    username.as_ptr(),
                    password.as_ptr(),
                    1,
                    Some(exported_active),
                )
            };
            assert_eq!(status, Failure::InvalidText as i32);
        }
    }

    #[test]
    fn export_rejects_invalid_multipliers_before_typing() {
        let text = std::ffi::CString::new("").unwrap();
        for multiplier in [i32::MIN, -1, 0, 3, 5, i32::MAX] {
            // SAFETY: Both buffers and the callback remain valid throughout the call.
            let status = unsafe {
                crate::autoTypeLogin(
                    0,
                    text.as_ptr(),
                    text.as_ptr(),
                    multiplier,
                    Some(exported_active),
                )
            };
            assert_eq!(status, Failure::InputFailed as i32);
        }
    }
}
