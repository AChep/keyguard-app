use crate::ffi::HotKeyPressedCallback;
use crate::hotkey::{
    with_restartable_thread, CallbackDispatcher, REGISTER_STATUS_INTERNAL_ERROR,
    REGISTER_STATUS_INVALID_SHORTCUT,
    REGISTER_STATUS_UNAVAILABLE,
};
use std::collections::{HashMap, VecDeque};
use std::ffi::c_int;
use std::ffi::c_void;
use std::ptr;
use std::sync::mpsc;
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;

type Bool = i32;
type Uint = u32;
type Dword = u32;
type Wparam = usize;
type Lparam = isize;
type Hwnd = *mut c_void;

const PM_NOREMOVE: Uint = 0x0000;
const MOD_NOREPEAT: Uint = 0x4000;
const WM_APP: Uint = 0x8000;
const WM_HOTKEY: Uint = 0x0312;
const WM_KEYGUARD_HOTKEY: Uint = WM_APP + 1;

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Point {
    x: i32,
    y: i32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Msg {
    hwnd: Hwnd,
    message: Uint,
    wParam: Wparam,
    lParam: Lparam,
    time: Dword,
    pt: Point,
    lPrivate: Dword,
}

#[link(name = "user32")]
unsafe extern "system" {
    fn RegisterHotKey(hWnd: Hwnd, id: c_int, fsModifiers: Uint, vk: Uint) -> Bool;
    fn UnregisterHotKey(hWnd: Hwnd, id: c_int) -> Bool;
    fn GetMessageW(lpMsg: *mut Msg, hWnd: Hwnd, wMsgFilterMin: Uint, wMsgFilterMax: Uint) -> Bool;
    fn PeekMessageW(
        lpMsg: *mut Msg,
        hWnd: Hwnd,
        wMsgFilterMin: Uint,
        wMsgFilterMax: Uint,
        wRemoveMsg: Uint,
    ) -> Bool;
    fn PostThreadMessageW(idThread: Dword, msg: Uint, wParam: Wparam, lParam: Lparam) -> Bool;
    fn GetCurrentThreadId() -> Dword;
}

enum Command {
    Register {
        key_code: u32,
        modifiers: u32,
        callback: HotKeyPressedCallback,
        response_tx: mpsc::Sender<i32>,
    },
    Unregister {
        id: i32,
        response_tx: mpsc::Sender<bool>,
    },
}

struct PendingQueue<T> {
    next_token: u64,
    entries: VecDeque<(u64, T)>,
}

impl<T> PendingQueue<T> {
    fn new() -> Self {
        Self {
            next_token: 1,
            entries: VecDeque::new(),
        }
    }

    fn push(&mut self, value: T) -> u64 {
        let token = self.next_token;
        self.next_token += 1;
        self.entries.push_back((token, value));
        token
    }

    fn pop(&mut self) -> Option<T> {
        self.entries.pop_front().map(|(_, value)| value)
    }

    fn remove(&mut self, token: u64) -> Option<T> {
        let index = self
            .entries
            .iter()
            .position(|(entry_token, _)| *entry_token == token)?;
        self.entries.remove(index).map(|(_, value)| value)
    }
}

#[derive(Clone)]
struct HotKeyThread {
    thread_id: Dword,
    commands: Arc<Mutex<PendingQueue<Command>>>,
    _callback_dispatcher: CallbackDispatcher,
}

static HOTKEY_THREAD: OnceLock<Mutex<Option<HotKeyThread>>> = OnceLock::new();

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
        let commands = Arc::new(Mutex::new(PendingQueue::new()));
        let commands_worker = Arc::clone(&commands);
        let callback_dispatcher = CallbackDispatcher::start("keyguard-hotkey-windows-callback")
            .ok_or(REGISTER_STATUS_INTERNAL_ERROR)?;
        let callback_dispatcher_worker = callback_dispatcher.clone();
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);

        thread::Builder::new()
            .name("keyguard-hotkey-windows".to_owned())
            .spawn(move || {
                let mut msg = Msg::default();
                unsafe {
                    PeekMessageW(&mut msg, ptr::null_mut(), 0, 0, PM_NOREMOVE);
                }

                let thread_id = unsafe { GetCurrentThreadId() };
                if ready_tx.send(thread_id).is_err() {
                    return;
                }

                let mut next_id = 1;
                let mut callbacks = HashMap::<i32, HotKeyPressedCallback>::new();
                loop {
                    let result = unsafe { GetMessageW(&mut msg, ptr::null_mut(), 0, 0) };
                    if result <= 0 {
                        break;
                    }

                    match msg.message {
                        WM_HOTKEY => {
                            let id = msg.wParam as i32;
                            if let Some(callback) = callbacks.get(&id).copied() {
                                let _ = callback_dispatcher_worker.dispatch(callback, id);
                            }
                        }

                        WM_KEYGUARD_HOTKEY => {
                            drain_commands(&commands_worker, &mut callbacks, &mut next_id);
                        }

                        _ => {}
                    }
                }

                for id in callbacks.into_keys() {
                    unsafe {
                        UnregisterHotKey(ptr::null_mut(), id);
                    }
                }
            })
            .map_err(|_| REGISTER_STATUS_INTERNAL_ERROR)?;

        let thread_id = ready_rx.recv().map_err(|_| REGISTER_STATUS_INTERNAL_ERROR)?;
        Ok(Self {
            thread_id,
            commands,
            _callback_dispatcher: callback_dispatcher,
        })
    }

    fn register(
        &self,
        key_code: u32,
        modifiers: u32,
        callback: HotKeyPressedCallback,
    ) -> Option<i32> {
        let (response_tx, response_rx) = mpsc::channel();
        if !self.enqueue(Command::Register {
            key_code,
            modifiers,
            callback,
            response_tx,
        }) {
            return None;
        }

        response_rx.recv().ok()
    }

    fn unregister(&self, id: i32) -> Option<bool> {
        let (response_tx, response_rx) = mpsc::channel();
        if !self.enqueue(Command::Unregister { id, response_tx }) {
            return None;
        }

        response_rx.recv().ok()
    }

    fn enqueue(&self, command: Command) -> bool {
        let mut commands = self
            .commands
            .lock()
            .expect("windows hotkey commands mutex poisoned");
        let token = commands.push(command);
        let ok = unsafe { PostThreadMessageW(self.thread_id, WM_KEYGUARD_HOTKEY, 0, 0) != 0 };
        if !ok {
            let _ = commands.remove(token);
        }
        ok
    }
}

fn drain_commands(
    commands: &Mutex<PendingQueue<Command>>,
    callbacks: &mut HashMap<i32, HotKeyPressedCallback>,
    next_id: &mut i32,
) {
    loop {
        let command = {
            let mut commands = commands
                .lock()
                .expect("windows hotkey commands mutex poisoned");
            commands.pop()
        };
        let Some(command) = command else {
            return;
        };

        match command {
            Command::Register {
                key_code,
                modifiers,
                callback,
                response_tx,
            } => {
                let id = *next_id;
                if key_code == 0 {
                    let _ = response_tx.send(REGISTER_STATUS_INVALID_SHORTCUT);
                    continue;
                }

                if id <= 0 || callback.is_none() {
                    let _ = response_tx.send(REGISTER_STATUS_INTERNAL_ERROR);
                    continue;
                }

                let ok = unsafe {
                    RegisterHotKey(ptr::null_mut(), id, register_modifiers(modifiers), key_code)
                        != 0
                };
                if ok {
                    callbacks.insert(id, callback);
                    *next_id += 1;
                    let _ = response_tx.send(id);
                } else {
                    let _ = response_tx.send(REGISTER_STATUS_UNAVAILABLE);
                }
            }

            Command::Unregister { id, response_tx } => {
                let Some(_callback) = callbacks.get(&id).copied() else {
                    let _ = response_tx.send(false);
                    continue;
                };

                let ok = unsafe { UnregisterHotKey(ptr::null_mut(), id) != 0 };
                if ok {
                    callbacks.remove(&id);
                }
                let _ = response_tx.send(ok);
            }
        }
    }
}

fn register_modifiers(modifiers: u32) -> Uint {
    modifiers | MOD_NOREPEAT
}

#[cfg(test)]
mod tests {
    use super::{register_modifiers, PendingQueue, MOD_NOREPEAT};

    #[test]
    fn register_modifiers_adds_no_repeat_flag() {
        assert_eq!(
            0x0002 | 0x0004 | MOD_NOREPEAT,
            register_modifiers(0x0002 | 0x0004)
        );
    }

    #[test]
    fn pending_queue_remove_discards_only_requested_entry() {
        let mut queue = PendingQueue::new();
        let first = queue.push(1);
        let second = queue.push(2);

        assert_eq!(Some(1), queue.remove(first));
        assert_eq!(Some(2), queue.pop());
        assert_eq!(None, queue.remove(second));
    }
}
