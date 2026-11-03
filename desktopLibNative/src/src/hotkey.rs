use crate::ffi::HotKeyPressedCallback;
use std::sync::mpsc;
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::thread;

#[cfg_attr(target_os = "linux", path = "hotkey/linux.rs")]
#[cfg_attr(target_os = "macos", path = "hotkey/macos.rs")]
#[cfg_attr(target_os = "windows", path = "hotkey/windows.rs")]
#[cfg_attr(
    not(any(target_os = "macos", target_os = "windows", target_os = "linux")),
    path = "hotkey/stub.rs"
)]
mod imp;
#[cfg(any(test, target_os = "linux", target_os = "windows"))]
mod worker;

#[allow(unused_imports)]
pub(crate) use crate::ffi::{REGISTER_STATUS_INTERNAL_ERROR, REGISTER_STATUS_UNSUPPORTED_PLATFORM};
#[allow(dead_code)]
pub(crate) const REGISTER_STATUS_UNSUPPORTED_SESSION: i32 = -2;
#[allow(dead_code)]
pub(crate) const REGISTER_STATUS_INVALID_SHORTCUT: i32 = -3;
#[allow(dead_code)]
pub(crate) const REGISTER_STATUS_UNAVAILABLE: i32 = -4;

#[allow(dead_code)]
#[derive(Clone)]
pub(crate) struct CallbackDispatcher {
    tx: mpsc::Sender<CallbackMessage>,
    thread_id: Arc<OnceLock<thread::ThreadId>>,
}

#[allow(dead_code)]
#[derive(Clone)]
struct CallbackInvocation {
    registration: CallbackRegistration,
    id: i32,
}

#[allow(dead_code)]
enum CallbackMessage {
    Invoke(CallbackInvocation),
}

#[allow(dead_code)]
#[derive(Clone)]
pub(crate) struct CallbackRegistration {
    callback: unsafe extern "C" fn(i32),
    state: Arc<CallbackRegistrationState>,
}

#[allow(dead_code)]
#[derive(Default)]
struct CallbackRegistrationState {
    lifecycle: Mutex<CallbackLifecycle>,
    idle: Condvar,
}

#[allow(dead_code)]
#[derive(Default)]
struct CallbackLifecycle {
    active: bool,
    retired: bool,
}

#[allow(dead_code)]
impl CallbackRegistration {
    pub(crate) fn new(callback: HotKeyPressedCallback) -> Option<Self> {
        Some(Self {
            callback: callback?,
            state: Arc::new(CallbackRegistrationState::default()),
        })
    }

    fn begin_invoke(&self) -> bool {
        let mut lifecycle = self
            .state
            .lifecycle
            .lock()
            .expect("hotkey callback lifecycle mutex poisoned");
        if lifecycle.retired {
            return false;
        }

        debug_assert!(!lifecycle.active);
        lifecycle.active = true;
        true
    }

    fn finish_invoke(&self) {
        let mut lifecycle = self
            .state
            .lifecycle
            .lock()
            .expect("hotkey callback lifecycle mutex poisoned");
        lifecycle.active = false;
        self.state.idle.notify_all();
    }

    pub(crate) fn retire(&self) {
        let mut lifecycle = self
            .state
            .lifecycle
            .lock()
            .expect("hotkey callback lifecycle mutex poisoned");
        lifecycle.retired = true;
    }

    fn is_active(&self) -> bool {
        self.state
            .lifecycle
            .lock()
            .expect("hotkey callback lifecycle mutex poisoned")
            .active
    }

    fn wait_until_idle(&self) {
        let mut lifecycle = self
            .state
            .lifecycle
            .lock()
            .expect("hotkey callback lifecycle mutex poisoned");
        while lifecycle.active {
            lifecycle = self
                .state
                .idle
                .wait(lifecycle)
                .expect("hotkey callback lifecycle mutex poisoned");
        }
    }
}

#[allow(dead_code)]
impl CallbackDispatcher {
    pub(crate) fn start(thread_name: &str) -> Option<Self> {
        let (tx, rx) = mpsc::channel::<CallbackMessage>();
        let thread_id = Arc::new(OnceLock::new());
        let thread_id_worker = Arc::clone(&thread_id);
        thread::Builder::new()
            .name(thread_name.to_owned())
            .spawn(move || {
                let _ = thread_id_worker.set(thread::current().id());
                while let Ok(message) = rx.recv() {
                    match message {
                        CallbackMessage::Invoke(invocation) => {
                            if !invocation.registration.begin_invoke() {
                                continue;
                            }
                            // SAFETY: CallbackRegistration contains a non-null
                            // callback from the FFI contract, whose owner keeps
                            // the callback code alive until unregister reports
                            // success. begin_invoke atomically established it
                            // was not retired, and a non-dispatcher unregister
                            // waits for finish_invoke before reporting success.
                            // A reentrant unregister can retire this
                            // already-entered call early, but it can only
                            // originate from inside the callback itself, and an
                            // owner cannot free code it is still executing.
                            // Retirement makes all later queued calls skip it.
                            unsafe {
                                (invocation.registration.callback)(invocation.id);
                            }
                            invocation.registration.finish_invoke();
                            #[cfg(any(test, target_os = "linux", target_os = "windows"))]
                            worker::callback_finished(invocation.id, &invocation.registration);
                        }
                    }
                }
            })
            .ok()?;
        Some(Self { tx, thread_id })
    }

    pub(crate) fn dispatch(&self, registration: &CallbackRegistration, id: i32) -> bool {
        self.tx
            .send(CallbackMessage::Invoke(CallbackInvocation {
                registration: registration.clone(),
                id,
            }))
            .is_ok()
    }

    pub(crate) fn is_current_thread(&self) -> bool {
        self.thread_id
            .get()
            .is_some_and(|thread_id| *thread_id == thread::current().id())
    }
}

#[allow(dead_code)]
pub(crate) fn with_restartable_thread<T: Clone + PartialEq, R>(
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
        if guard.as_ref() == Some(&thread) {
            *guard = None;
        }
        if attempt == 1 {
            return Err(REGISTER_STATUS_INTERNAL_ERROR);
        }
    }

    Err(REGISTER_STATUS_INTERNAL_ERROR)
}

pub(crate) fn register(key_code: u32, modifiers: u32, callback: HotKeyPressedCallback) -> i32 {
    imp::register(key_code, modifiers, callback)
}

pub(crate) fn unregister(id: i32) -> bool {
    #[cfg(any(target_os = "linux", target_os = "windows"))]
    {
        worker::unregister(id, |owner| imp::unregister(id, owner))
    }
    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    {
        imp::unregister(id)
    }
}

#[cfg(test)]
mod tests {
    use super::{
        with_restartable_thread, CallbackDispatcher, CallbackRegistration,
        REGISTER_STATUS_INTERNAL_ERROR, REGISTER_STATUS_INVALID_SHORTCUT,
        REGISTER_STATUS_UNAVAILABLE, REGISTER_STATUS_UNSUPPORTED_PLATFORM,
        REGISTER_STATUS_UNSUPPORTED_SESSION,
    };
    use std::collections::VecDeque;
    use std::sync::mpsc;
    use std::sync::Mutex;
    use std::thread;
    use std::time::Duration;

    static CALLBACK_SENDER: Mutex<Option<mpsc::Sender<String>>> = Mutex::new(None);
    static BLOCKING_CALLBACK: Mutex<Option<(mpsc::SyncSender<()>, mpsc::Receiver<()>)>> =
        Mutex::new(None);

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

    unsafe extern "C" fn record_blocking_callback(_id: i32) {
        let Some((entered_tx, release_rx)) = BLOCKING_CALLBACK
            .lock()
            .expect("blocking callback mutex poisoned")
            .take()
        else {
            return;
        };
        let _ = entered_tx.send(());
        let _ = release_rx.recv();
    }

    #[test]
    fn restartable_thread_does_not_cache_failed_start() {
        let slot = Mutex::new(None);
        let mut next = VecDeque::from([Err(REGISTER_STATUS_INTERNAL_ERROR), Ok(7)]);

        let first = with_restartable_thread(
            &slot,
            || {
                next.pop_front()
                    .unwrap_or(Err(REGISTER_STATUS_INTERNAL_ERROR))
            },
            |thread| Some(*thread),
        );
        let second = with_restartable_thread(
            &slot,
            || {
                next.pop_front()
                    .unwrap_or(Err(REGISTER_STATUS_INTERNAL_ERROR))
            },
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
    fn failed_old_operation_does_not_discard_a_replacement_worker() {
        let slot = Mutex::new(Some(1));
        let result = with_restartable_thread(
            &slot,
            || panic!("replacement already exists"),
            |worker| {
                if *worker == 1 {
                    *slot.lock().unwrap() = Some(2);
                    None
                } else {
                    Some(*worker)
                }
            },
        );
        assert_eq!(Ok(2), result);
        assert_eq!(Some(2), *slot.lock().unwrap());
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
        let registration =
            CallbackRegistration::new(Some(record_callback_thread)).expect("callback registration");
        assert!(dispatcher.dispatch(&registration, 1));

        let callback_thread_name = rx.recv().expect("callback thread name");
        assert_eq!("keyguard-hotkey-callback-test", callback_thread_name);
        assert_ne!(caller_name, callback_thread_name);

        *CALLBACK_SENDER
            .lock()
            .expect("callback sender mutex poisoned") = None;
    }

    #[test]
    fn callback_retirement_is_non_blocking_and_waits_separately_for_active_invocation() {
        let dispatcher =
            CallbackDispatcher::start("keyguard-hotkey-retire-test").expect("dispatcher");
        let (entered_tx, entered_rx) = mpsc::sync_channel(1);
        let (release_tx, release_rx) = mpsc::sync_channel(1);
        *BLOCKING_CALLBACK
            .lock()
            .expect("blocking callback mutex poisoned") = Some((entered_tx, release_rx));

        let registration = CallbackRegistration::new(Some(record_blocking_callback))
            .expect("callback registration");
        assert!(dispatcher.dispatch(&registration, 1));
        entered_rx
            .recv_timeout(Duration::from_secs(1))
            .expect("callback did not start");

        let (retired_tx, retired_rx) = mpsc::sync_channel(1);
        let registration_worker = registration.clone();
        let retire_thread = thread::spawn(move || {
            registration_worker.retire();
            let _ = retired_tx.send(());
        });

        retired_rx
            .recv_timeout(Duration::from_secs(1))
            .expect("callback retirement waited for the active invocation");
        retire_thread.join().expect("retire thread panicked");

        let lifecycle = registration
            .state
            .lifecycle
            .lock()
            .expect("hotkey callback lifecycle mutex poisoned");
        assert!(lifecycle.retired);
        assert!(lifecycle.active);
        drop(lifecycle);

        let (idle_tx, idle_rx) = mpsc::sync_channel(1);
        let registration_waiter = registration.clone();
        let wait_thread = thread::spawn(move || {
            registration_waiter.wait_until_idle();
            let _ = idle_tx.send(());
        });
        assert_eq!(Err(mpsc::TryRecvError::Empty), idle_rx.try_recv());

        release_tx.send(()).expect("release callback");
        idle_rx
            .recv_timeout(Duration::from_secs(1))
            .expect("callback registration did not become idle");
        wait_thread.join().expect("wait thread panicked");
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
