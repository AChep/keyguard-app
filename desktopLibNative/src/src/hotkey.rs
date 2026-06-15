use crate::ffi::HotKeyPressedCallback;
use crate::platform;
use std::sync::mpsc;
use std::sync::Mutex;
use std::thread;

#[allow(dead_code)]
pub(crate) const REGISTER_STATUS_UNSUPPORTED_PLATFORM: i32 = -1;
#[allow(dead_code)]
pub(crate) const REGISTER_STATUS_UNSUPPORTED_SESSION: i32 = -2;
#[allow(dead_code)]
pub(crate) const REGISTER_STATUS_INVALID_SHORTCUT: i32 = -3;
#[allow(dead_code)]
pub(crate) const REGISTER_STATUS_UNAVAILABLE: i32 = -4;
pub(crate) const REGISTER_STATUS_INTERNAL_ERROR: i32 = -5;

#[allow(dead_code)]
#[derive(Clone)]
pub(crate) struct CallbackDispatcher {
    tx: mpsc::Sender<CallbackInvocation>,
}

#[allow(dead_code)]
#[derive(Clone, Copy)]
struct CallbackInvocation {
    callback: unsafe extern "C" fn(i32),
    id: i32,
}

#[allow(dead_code)]
impl CallbackDispatcher {
    pub(crate) fn start(thread_name: &str) -> Option<Self> {
        let (tx, rx) = mpsc::channel::<CallbackInvocation>();
        thread::Builder::new()
            .name(thread_name.to_owned())
            .spawn(move || {
                while let Ok(invocation) = rx.recv() {
                    unsafe {
                        (invocation.callback)(invocation.id);
                    }
                }
            })
            .ok()?;
        Some(Self { tx })
    }

    pub(crate) fn dispatch(&self, callback: HotKeyPressedCallback, id: i32) -> bool {
        let Some(callback) = callback else {
            return false;
        };
        self.tx.send(CallbackInvocation { callback, id }).is_ok()
    }
}

#[allow(dead_code)]
pub(crate) fn with_restartable_thread<T: Clone, R>(
    slot: &Mutex<Option<T>>,
    mut start: impl FnMut() -> Result<T, i32>,
    mut operation: impl FnMut(&T) -> Option<R>,
) -> Result<R, i32> {
    for attempt in 0..2 {
        let thread = {
            let mut guard = slot.lock().expect("hotkey thread slot mutex poisoned");
            if guard.is_none() {
                *guard = Some(start()?);
            }
            guard.clone().ok_or(REGISTER_STATUS_INTERNAL_ERROR)?
        };

        if let Some(value) = operation(&thread) {
            return Ok(value);
        }

        let mut guard = slot.lock().expect("hotkey thread slot mutex poisoned");
        *guard = None;
        if attempt == 1 {
            return Err(REGISTER_STATUS_INTERNAL_ERROR);
        }
    }

    Err(REGISTER_STATUS_INTERNAL_ERROR)
}

pub(crate) fn register(key_code: u32, modifiers: u32, callback: HotKeyPressedCallback) -> i32 {
    platform::hotkey::register(key_code, modifiers, callback)
}

pub(crate) fn unregister(id: i32) -> bool {
    platform::hotkey::unregister(id)
}

#[cfg(test)]
mod tests {
    use super::{
        with_restartable_thread, CallbackDispatcher, REGISTER_STATUS_INTERNAL_ERROR,
        REGISTER_STATUS_INVALID_SHORTCUT, REGISTER_STATUS_UNAVAILABLE,
        REGISTER_STATUS_UNSUPPORTED_PLATFORM, REGISTER_STATUS_UNSUPPORTED_SESSION,
    };
    use std::collections::VecDeque;
    use std::sync::mpsc;
    use std::sync::Mutex;
    use std::thread;

    static CALLBACK_SENDER: Mutex<Option<mpsc::Sender<String>>> = Mutex::new(None);

    unsafe extern "C" fn record_callback_thread(_id: i32) {
        if let Some(tx) = CALLBACK_SENDER
            .lock()
            .expect("callback sender mutex poisoned")
            .as_ref()
        {
            let name = thread::current().name().unwrap_or("unnamed").to_owned();
            let _ = tx.send(name);
        }
    }

    #[test]
    fn restartable_thread_does_not_cache_failed_start() {
        let slot = Mutex::new(None);
        let mut next = VecDeque::from([Err(REGISTER_STATUS_INTERNAL_ERROR), Ok(7)]);

        let first = with_restartable_thread(
            &slot,
            || next.pop_front().unwrap_or(Err(REGISTER_STATUS_INTERNAL_ERROR)),
            |thread| Some(*thread),
        );
        let second = with_restartable_thread(
            &slot,
            || next.pop_front().unwrap_or(Err(REGISTER_STATUS_INTERNAL_ERROR)),
            |thread| Some(*thread),
        );

        assert_eq!(Err(REGISTER_STATUS_INTERNAL_ERROR), first);
        assert_eq!(Ok(7), second);
    }

    #[test]
    fn restartable_thread_retries_after_operation_failure() {
        let slot = Mutex::new(None);
        let mut started = 0;
        let mut failed_once = false;

        let result = with_restartable_thread(
            &slot,
            || {
                started += 1;
                Ok(started)
            },
            |thread| {
                if !failed_once {
                    failed_once = true;
                    assert_eq!(1, *thread);
                    None
                } else {
                    Some(*thread)
                }
            },
        );

        assert_eq!(Ok(2), result);
    }

    #[test]
    fn callback_dispatcher_invokes_callbacks_off_caller_thread() {
        let dispatcher =
            CallbackDispatcher::start("keyguard-hotkey-callback-test").expect("dispatcher");
        let (tx, rx) = mpsc::channel();
        *CALLBACK_SENDER
            .lock()
            .expect("callback sender mutex poisoned") = Some(tx);

        let caller_name = thread::current().name().unwrap_or("unnamed").to_owned();
        assert!(dispatcher.dispatch(Some(record_callback_thread), 1));

        let callback_thread_name = rx.recv().expect("callback thread name");
        assert_eq!("keyguard-hotkey-callback-test", callback_thread_name);
        assert_ne!(caller_name, callback_thread_name);

        *CALLBACK_SENDER
            .lock()
            .expect("callback sender mutex poisoned") = None;
    }

    #[test]
    fn register_status_codes_match_jvm_contract() {
        assert_eq!(-1, REGISTER_STATUS_UNSUPPORTED_PLATFORM);
        assert_eq!(-2, REGISTER_STATUS_UNSUPPORTED_SESSION);
        assert_eq!(-3, REGISTER_STATUS_INVALID_SHORTCUT);
        assert_eq!(-4, REGISTER_STATUS_UNAVAILABLE);
        assert_eq!(-5, REGISTER_STATUS_INTERNAL_ERROR);
    }
}
