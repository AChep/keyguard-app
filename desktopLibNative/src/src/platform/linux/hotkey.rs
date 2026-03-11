use crate::ffi::HotKeyPressedCallback;
use crate::hotkey::{
    with_restartable_thread, CallbackDispatcher, REGISTER_STATUS_INTERNAL_ERROR,
    REGISTER_STATUS_INVALID_SHORTCUT, REGISTER_STATUS_UNAVAILABLE,
    REGISTER_STATUS_UNSUPPORTED_SESSION,
};
use libc::{c_char, c_int, c_long, c_uchar, c_uint, c_ulong};
use std::collections::{HashMap, HashSet};
use std::env;
use std::mem::MaybeUninit;
use std::ptr;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::mpsc;
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::Duration;

const KEY_PRESS: c_int = 2;
const KEY_RELEASE: c_int = 3;
const GRAB_MODE_ASYNC: c_int = 1;
const LOCK_MASK: c_uint = 1 << 1;
const MOD2_MASK: c_uint = 1 << 4;
const IGNORED_MODIFIER_MASK: c_uint = LOCK_MASK | MOD2_MASK;

#[repr(C)]
struct Display {
    _private: [u8; 0],
}

type Window = c_ulong;
type KeySym = c_ulong;
type KeyCode = c_uchar;

#[repr(C)]
struct XErrorEvent {
    type_: c_int,
    display: *mut Display,
    resourceid: c_ulong,
    serial: c_ulong,
    error_code: c_uchar,
    request_code: c_uchar,
    minor_code: c_uchar,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct XKeyEvent {
    type_: c_int,
    serial: c_ulong,
    send_event: c_int,
    display: *mut Display,
    window: Window,
    root: Window,
    subwindow: Window,
    time: c_ulong,
    x: c_int,
    y: c_int,
    x_root: c_int,
    y_root: c_int,
    state: c_uint,
    keycode: c_uint,
    same_screen: c_int,
}

#[repr(C)]
union XEvent {
    type_: c_int,
    xkey: XKeyEvent,
    pad: [c_long; 24],
}

type XErrorHandler = Option<unsafe extern "C" fn(*mut Display, *mut XErrorEvent) -> c_int>;

#[link(name = "X11")]
unsafe extern "C" {
    fn XInitThreads() -> c_int;
    fn XOpenDisplay(display_name: *const c_char) -> *mut Display;
    fn XCloseDisplay(display: *mut Display) -> c_int;
    fn XDefaultRootWindow(display: *mut Display) -> Window;
    fn XKeysymToKeycode(display: *mut Display, keysym: KeySym) -> KeyCode;
    fn XGrabKey(
        display: *mut Display,
        keycode: c_int,
        modifiers: c_uint,
        grab_window: Window,
        owner_events: c_int,
        pointer_mode: c_int,
        keyboard_mode: c_int,
    ) -> c_int;
    fn XUngrabKey(
        display: *mut Display,
        keycode: c_int,
        modifiers: c_uint,
        grab_window: Window,
    ) -> c_int;
    fn XPending(display: *mut Display) -> c_int;
    fn XNextEvent(display: *mut Display, event_return: *mut XEvent) -> c_int;
    fn XPeekEvent(display: *mut Display, event_return: *mut XEvent) -> c_int;
    fn XSync(display: *mut Display, discard: c_int) -> c_int;
    fn XSetErrorHandler(handler: XErrorHandler) -> XErrorHandler;
}

#[derive(Clone, Copy)]
struct RegisteredHotKey {
    keycode: c_int,
    modifiers: c_uint,
    callback: HotKeyPressedCallback,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
struct HotKeyTrigger {
    keycode: c_int,
    modifiers: c_uint,
}

impl HotKeyTrigger {
    fn new(keycode: c_int, modifiers: c_uint) -> Self {
        Self {
            keycode,
            modifiers: normalize_modifiers(modifiers),
        }
    }
}

enum Command {
    Register {
        keysym: KeySym,
        modifiers: c_uint,
        callback: HotKeyPressedCallback,
        response_tx: mpsc::Sender<i32>,
    },
    Unregister {
        id: i32,
        response_tx: mpsc::Sender<bool>,
    },
}

#[derive(Clone)]
struct HotKeyThread {
    command_tx: mpsc::Sender<Command>,
    callback_dispatcher: CallbackDispatcher,
}

static HOTKEY_THREAD: OnceLock<Mutex<Option<HotKeyThread>>> = OnceLock::new();
static X11_ERROR_CODE: AtomicI32 = AtomicI32::new(0);
static X11_ERROR_LOCK: Mutex<()> = Mutex::new(());
static X11_PREVIOUS_ERROR_HANDLER: Mutex<Option<XErrorHandler>> = Mutex::new(None);

fn hotkey_thread_slot() -> &'static Mutex<Option<HotKeyThread>> {
    HOTKEY_THREAD.get_or_init(|| Mutex::new(None))
}

pub(crate) fn register(key_code: u32, modifiers: u32, callback: HotKeyPressedCallback) -> i32 {
    with_restartable_thread(hotkey_thread_slot(), HotKeyThread::start, |thread| {
        thread.register(key_code, modifiers, callback)
    })
    .unwrap_or(REGISTER_STATUS_INTERNAL_ERROR)
}

pub(crate) fn unregister(id: i32) -> bool {
    with_restartable_thread(hotkey_thread_slot(), HotKeyThread::start, |thread| {
        thread.unregister(id)
    })
    .unwrap_or(false)
}

impl HotKeyThread {
    fn start() -> Result<Self, i32> {
        unsafe {
            XInitThreads();
        }

        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let (command_tx, command_rx) = mpsc::channel();
        let callback_dispatcher = CallbackDispatcher::start("keyguard-hotkey-linux-callback")
            .ok_or(REGISTER_STATUS_INTERNAL_ERROR)?;
        let callback_dispatcher_worker = callback_dispatcher.clone();
        thread::Builder::new()
            .name("keyguard-hotkey-linux".to_owned())
            .spawn(move || hotkey_thread_main(command_rx, ready_tx, callback_dispatcher_worker))
            .map_err(|_| REGISTER_STATUS_INTERNAL_ERROR)?;

        ready_rx
            .recv()
            .map_err(|_| REGISTER_STATUS_INTERNAL_ERROR)??;
        Ok(Self {
            command_tx,
            callback_dispatcher,
        })
    }

    fn register(
        &self,
        key_code: u32,
        modifiers: u32,
        callback: HotKeyPressedCallback,
    ) -> Option<i32> {
        let (response_tx, response_rx) = mpsc::channel();
        if self
            .command_tx
            .send(Command::Register {
                keysym: key_code as KeySym,
                modifiers: modifiers as c_uint,
                callback,
                response_tx,
            })
            .is_err()
        {
            return None;
        }

        response_rx.recv().ok()
    }

    fn unregister(&self, id: i32) -> Option<bool> {
        let (response_tx, response_rx) = mpsc::channel();
        if self
            .command_tx
            .send(Command::Unregister { id, response_tx })
            .is_err()
        {
            return None;
        }

        response_rx.recv().ok()
    }
}

fn hotkey_thread_main(
    command_rx: mpsc::Receiver<Command>,
    ready_tx: mpsc::SyncSender<Result<(), i32>>,
    callback_dispatcher: CallbackDispatcher,
) {
    if !supports_global_hotkeys_in_session(
        env::var("WAYLAND_DISPLAY").ok().as_deref(),
        env::var("XDG_SESSION_TYPE").ok().as_deref(),
    ) {
        eprintln!(
            "keyguard-lib::registerNativeGlobalHotKey failed: Linux global hotkeys require an X11 session"
        );
        let _ = ready_tx.send(Err(REGISTER_STATUS_UNSUPPORTED_SESSION));
        return;
    }

    let display = unsafe { XOpenDisplay(ptr::null()) };
    if display.is_null() {
        eprintln!(
            "keyguard-lib::registerNativeGlobalHotKey failed: Linux global hotkeys require an X11 session"
        );
        let _ = ready_tx.send(Err(REGISTER_STATUS_UNSUPPORTED_SESSION));
        return;
    }

    let root = unsafe { XDefaultRootWindow(display) };
    let _ = ready_tx.send(Ok(()));

    let mut next_id = 1;
    let mut registrations = HashMap::<i32, RegisteredHotKey>::new();
    let mut pressed_hotkeys = HashSet::<HotKeyTrigger>::new();

    loop {
        process_x11_events(
            display,
            &registrations,
            &mut pressed_hotkeys,
            &callback_dispatcher,
        );

        match command_rx.recv_timeout(Duration::from_millis(16)) {
            Ok(command) => {
                handle_command(
                    display,
                    root,
                    command,
                    &mut registrations,
                    &mut pressed_hotkeys,
                    &mut next_id,
                );
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }

    for registered in registrations.into_values() {
        let _ = ungrab_hotkey(display, root, registered.keycode, registered.modifiers);
    }
    unsafe {
        XCloseDisplay(display);
    }
}

fn supports_global_hotkeys_in_session(
    wayland_display: Option<&str>,
    session_type: Option<&str>,
) -> bool {
    if wayland_display.is_some_and(|value| !value.is_empty()) {
        return false;
    }

    !session_type.is_some_and(|value| value.eq_ignore_ascii_case("wayland"))
}

fn handle_command(
    display: *mut Display,
    root: Window,
    command: Command,
    registrations: &mut HashMap<i32, RegisteredHotKey>,
    pressed_hotkeys: &mut HashSet<HotKeyTrigger>,
    next_id: &mut i32,
) {
    match command {
        Command::Register {
            keysym,
            modifiers,
            callback,
            response_tx,
        } => {
            if callback.is_none() {
                let _ = response_tx.send(REGISTER_STATUS_INTERNAL_ERROR);
                return;
            }

            let keycode = unsafe { XKeysymToKeycode(display, keysym) } as c_int;
            if keycode == 0 {
                let _ = response_tx.send(REGISTER_STATUS_INVALID_SHORTCUT);
                return;
            }

            let modifiers = normalize_modifiers(modifiers);
            if registrations.values().any(|registered| {
                registered.keycode == keycode && registered.modifiers == modifiers
            }) {
                let _ = response_tx.send(REGISTER_STATUS_UNAVAILABLE);
                return;
            }

            if !grab_hotkey(display, root, keycode, modifiers) {
                let _ = response_tx.send(REGISTER_STATUS_UNAVAILABLE);
                return;
            }

            let id = *next_id;
            *next_id += 1;
            registrations.insert(
                id,
                RegisteredHotKey {
                    keycode,
                    modifiers,
                    callback,
                },
            );
            let _ = response_tx.send(id);
        }

        Command::Unregister { id, response_tx } => {
            let Some(registered) = registrations.get(&id) else {
                let _ = response_tx.send(false);
                return;
            };

            let ok = ungrab_hotkey(display, root, registered.keycode, registered.modifiers);
            if ok {
                pressed_hotkeys.remove(&HotKeyTrigger::new(
                    registered.keycode,
                    registered.modifiers,
                ));
                registrations.remove(&id);
            }
            let _ = response_tx.send(ok);
        }
    }
}

fn process_x11_events(
    display: *mut Display,
    registrations: &HashMap<i32, RegisteredHotKey>,
    pressed_hotkeys: &mut HashSet<HotKeyTrigger>,
    callback_dispatcher: &CallbackDispatcher,
) {
    while unsafe { XPending(display) } > 0 {
        let mut event = MaybeUninit::<XEvent>::uninit();
        unsafe {
            XNextEvent(display, event.as_mut_ptr());
        }
        let event = unsafe { event.assume_init() };

        let event_type = unsafe { event.type_ };
        if event_type != KEY_PRESS && event_type != KEY_RELEASE {
            continue;
        }

        let xkey = unsafe { event.xkey };
        let trigger = HotKeyTrigger::new(xkey.keycode as c_int, xkey.state);
        let is_auto_repeat_release =
            event_type == KEY_RELEASE && is_auto_repeat_release(display, xkey);
        if let Some((id, callback)) = resolve_hotkey_event(
            event_type,
            trigger,
            is_auto_repeat_release,
            registrations,
            pressed_hotkeys,
        ) {
            let _ = callback_dispatcher.dispatch(callback, id);
        }
    }
}

fn resolve_hotkey_event(
    event_type: c_int,
    trigger: HotKeyTrigger,
    is_auto_repeat_release: bool,
    registrations: &HashMap<i32, RegisteredHotKey>,
    pressed_hotkeys: &mut HashSet<HotKeyTrigger>,
) -> Option<(i32, HotKeyPressedCallback)> {
    let (id, registered) = registrations
        .iter()
        .find(|(_, registered)| {
            registered.keycode == trigger.keycode && registered.modifiers == trigger.modifiers
        })
        .map(|(id, registered)| (*id, *registered))?;

    match event_type {
        KEY_PRESS => {
            if pressed_hotkeys.insert(trigger) {
                Some((id, registered.callback))
            } else {
                None
            }
        }
        KEY_RELEASE => {
            if !is_auto_repeat_release {
                pressed_hotkeys.remove(&trigger);
            }
            None
        }
        _ => None,
    }
}

fn is_auto_repeat_release(display: *mut Display, release_event: XKeyEvent) -> bool {
    if unsafe { XPending(display) } <= 0 {
        return false;
    }

    let mut next_event = MaybeUninit::<XEvent>::uninit();
    unsafe {
        XPeekEvent(display, next_event.as_mut_ptr());
    }
    let next_event = unsafe { next_event.assume_init() };
    if unsafe { next_event.type_ } != KEY_PRESS {
        return false;
    }

    let next_key = unsafe { next_event.xkey };
    next_key.keycode == release_event.keycode && next_key.time == release_event.time
}

fn grab_hotkey(display: *mut Display, root: Window, keycode: c_int, modifiers: c_uint) -> bool {
    let ok = with_x11_error_boundary(display, || {
        for variant in modifier_variants(modifiers) {
            unsafe {
                XGrabKey(
                    display,
                    keycode,
                    variant,
                    root,
                    0,
                    GRAB_MODE_ASYNC,
                    GRAB_MODE_ASYNC,
                );
            }
        }
    });
    if !ok {
        let _ = ungrab_hotkey(display, root, keycode, modifiers);
    }
    ok
}

fn ungrab_hotkey(display: *mut Display, root: Window, keycode: c_int, modifiers: c_uint) -> bool {
    with_x11_error_boundary(display, || {
        for variant in modifier_variants(modifiers) {
            unsafe {
                XUngrabKey(display, keycode, variant, root);
            }
        }
    })
}

fn with_x11_error_boundary(display: *mut Display, operation: impl FnOnce()) -> bool {
    let _guard = X11_ERROR_LOCK.lock().expect("x11 error lock poisoned");
    X11_ERROR_CODE.store(0, Ordering::SeqCst);

    let previous_handler = unsafe { XSetErrorHandler(Some(x11_error_handler)) };
    *X11_PREVIOUS_ERROR_HANDLER
        .lock()
        .expect("x11 previous error handler mutex poisoned") = previous_handler;

    operation();

    unsafe {
        XSync(display, 0);
        XSetErrorHandler(previous_handler);
    }
    *X11_PREVIOUS_ERROR_HANDLER
        .lock()
        .expect("x11 previous error handler mutex poisoned") = None;

    X11_ERROR_CODE.load(Ordering::SeqCst) == 0
}

fn modifier_variants(modifiers: c_uint) -> [c_uint; 4] {
    [
        modifiers,
        modifiers | LOCK_MASK,
        modifiers | MOD2_MASK,
        modifiers | LOCK_MASK | MOD2_MASK,
    ]
}

fn normalize_modifiers(modifiers: c_uint) -> c_uint {
    modifiers & !IGNORED_MODIFIER_MASK
}

unsafe extern "C" fn x11_error_handler(display: *mut Display, event: *mut XErrorEvent) -> c_int {
    if let Some(event) = unsafe { event.as_ref() } {
        X11_ERROR_CODE.store(event.error_code as i32, Ordering::SeqCst);
    }

    let previous_handler = *X11_PREVIOUS_ERROR_HANDLER
        .lock()
        .expect("x11 previous error handler mutex poisoned");
    if let Some(handler) = previous_handler {
        unsafe { handler(display, event) }
    } else {
        0
    }
}

#[cfg(test)]
mod tests {
    use super::{
        resolve_hotkey_event, supports_global_hotkeys_in_session, HotKeyTrigger, RegisteredHotKey,
        KEY_PRESS, KEY_RELEASE, LOCK_MASK,
    };
    use std::collections::{HashMap, HashSet};

    fn registration_map() -> HashMap<i32, RegisteredHotKey> {
        HashMap::from([(
            7,
            RegisteredHotKey {
                keycode: 49,
                modifiers: 1 << 2,
                callback: None,
            },
        )])
    }

    fn dispatched_id(
        event_type: i32,
        keycode: i32,
        modifiers: u32,
        is_auto_repeat_release: bool,
        registrations: &HashMap<i32, RegisteredHotKey>,
        pressed_hotkeys: &mut HashSet<HotKeyTrigger>,
    ) -> Option<i32> {
        resolve_hotkey_event(
            event_type,
            HotKeyTrigger::new(keycode, modifiers),
            is_auto_repeat_release,
            registrations,
            pressed_hotkeys,
        )
        .map(|(id, _)| id)
    }

    #[test]
    fn hotkey_dispatches_only_once_until_release() {
        let registrations = registration_map();
        let mut pressed_hotkeys = HashSet::new();

        assert_eq!(
            Some(7),
            dispatched_id(
                KEY_PRESS,
                49,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            None,
            dispatched_id(
                KEY_PRESS,
                49,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            None,
            dispatched_id(
                KEY_RELEASE,
                49,
                1 << 2,
                true,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            None,
            dispatched_id(
                KEY_PRESS,
                49,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            None,
            dispatched_id(
                KEY_RELEASE,
                49,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            Some(7),
            dispatched_id(
                KEY_PRESS,
                49,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
    }

    #[test]
    fn hotkey_release_with_ignored_modifiers_clears_pressed_state() {
        let registrations = registration_map();
        let mut pressed_hotkeys = HashSet::new();

        assert_eq!(
            Some(7),
            dispatched_id(
                KEY_PRESS,
                49,
                (1 << 2) | LOCK_MASK,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            None,
            dispatched_id(
                KEY_RELEASE,
                49,
                (1 << 2) | LOCK_MASK,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            Some(7),
            dispatched_id(
                KEY_PRESS,
                49,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
    }

    #[test]
    fn unrelated_release_does_not_clear_pressed_hotkey() {
        let registrations = registration_map();
        let mut pressed_hotkeys = HashSet::new();

        assert_eq!(
            Some(7),
            dispatched_id(
                KEY_PRESS,
                49,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            None,
            dispatched_id(
                KEY_RELEASE,
                50,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
        assert_eq!(
            None,
            dispatched_id(
                KEY_PRESS,
                49,
                1 << 2,
                false,
                &registrations,
                &mut pressed_hotkeys,
            ),
        );
    }

    #[test]
    fn global_hotkeys_require_non_wayland_session() {
        assert!(!supports_global_hotkeys_in_session(
            Some("wayland-0"),
            Some("wayland"),
        ));
        assert!(!supports_global_hotkeys_in_session(None, Some("wayland")));
        assert!(!supports_global_hotkeys_in_session(
            Some("wayland-1"),
            Some("x11"),
        ));
        assert!(supports_global_hotkeys_in_session(None, Some("x11")));
        assert!(supports_global_hotkeys_in_session(None, None));
    }
}
