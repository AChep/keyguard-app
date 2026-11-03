use super::worker::Generation;
use super::{
    with_restartable_thread, CallbackDispatcher, CallbackRegistration,
    REGISTER_STATUS_INTERNAL_ERROR, REGISTER_STATUS_INVALID_SHORTCUT, REGISTER_STATUS_UNAVAILABLE,
};
use crate::ffi::HotKeyPressedCallback;
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
// RegisterHotKey accepts application ids in 0x0000..=0xBFFF. Native ids are
// reused once released; the registry id handed to the JVM never is.
const MAX_NATIVE_ID: i32 = 0xBFFF;

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
    generation: Arc<Generation>,
}

impl PartialEq for HotKeyThread {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.generation, &other.generation)
    }
}

struct RegisteredHotKey {
    id: i32,
    callback: CallbackRegistration,
}

/// Keyed by native id, which WM_HOTKEY carries in wParam.
type Registrations = HashMap<i32, RegisteredHotKey>;

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

pub(crate) fn unregister(id: i32, owner: &Arc<Generation>) -> bool {
    with_restartable_thread(hotkey_thread_slot(), HotKeyThread::start, |thread| {
        if !Arc::ptr_eq(owner, &thread.generation) {
            return Some(false);
        }
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
        let generation = Generation::new(callback_dispatcher);
        let worker_generation = generation.clone();
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);

        thread::Builder::new()
            .name("keyguard-hotkey-windows".to_owned())
            .spawn(move || {
                let mut msg = Msg::default();
                // SAFETY: msg is valid, aligned writable storage for MSG. A
                // null HWND and PM_NOREMOVE are documented to initialize this
                // thread's message queue without retaining the pointer.
                unsafe {
                    PeekMessageW(&mut msg, ptr::null_mut(), 0, 0, PM_NOREMOVE);
                }

                // SAFETY: GetCurrentThreadId takes no arguments and returns a
                // scalar identifier for the calling worker thread.
                let thread_id = unsafe { GetCurrentThreadId() };
                if ready_tx.send(thread_id).is_err() {
                    return;
                }

                let mut registrations = Registrations::new();
                loop {
                    // SAFETY: msg remains valid writable storage for the whole
                    // call. A null HWND selects this thread's messages, and the
                    // struct is read only when the positive result says it was
                    // initialized with a retrieved message.
                    let result = unsafe { GetMessageW(&mut msg, ptr::null_mut(), 0, 0) };
                    if result <= 0 {
                        break;
                    }

                    match msg.message {
                        WM_HOTKEY => {
                            if let Some(registered) = registrations.get(&(msg.wParam as i32)) {
                                let _ = worker_generation
                                    .dispatcher()
                                    .dispatch(&registered.callback, registered.id);
                            }
                        }

                        WM_KEYGUARD_HOTKEY => {
                            drain_commands(
                                &commands_worker,
                                &mut registrations,
                                &worker_generation,
                            );
                        }

                        _ => {}
                    }
                }

                // Worker shutdown does not report a successful unregister to
                // any foreign owner, so callbacks remain retained while queued
                // invocations drain.
                for native_id in registrations.into_keys() {
                    // SAFETY: Each native id is still registered by this worker
                    // with a null HWND, which denotes a thread-associated
                    // hotkey. The call receives no Rust memory.
                    unsafe {
                        UnregisterHotKey(ptr::null_mut(), native_id);
                    }
                }
                worker_generation.stopped();
            })
            .map_err(|_| REGISTER_STATUS_INTERNAL_ERROR)?;

        let thread_id = ready_rx
            .recv()
            .map_err(|_| REGISTER_STATUS_INTERNAL_ERROR)?;
        Ok(Self {
            thread_id,
            commands,
            generation,
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
        // SAFETY: thread_id was obtained from the live worker itself after its
        // message queue was initialized. The custom message carries only zero
        // scalar payloads and does not borrow Rust-managed memory.
        let ok = unsafe { PostThreadMessageW(self.thread_id, WM_KEYGUARD_HOTKEY, 0, 0) != 0 };
        if !ok {
            let _ = commands.remove(token);
        }
        ok
    }
}

fn drain_commands(
    commands: &Mutex<PendingQueue<Command>>,
    registrations: &mut Registrations,
    generation: &Arc<Generation>,
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
                if key_code == 0 {
                    let _ = response_tx.send(REGISTER_STATUS_INVALID_SHORTCUT);
                    continue;
                }

                let Some(callback) = CallbackRegistration::new(callback) else {
                    let _ = response_tx.send(REGISTER_STATUS_INTERNAL_ERROR);
                    continue;
                };
                let Some(native_id) = free_native_id(registrations) else {
                    let _ = response_tx.send(REGISTER_STATUS_INTERNAL_ERROR);
                    continue;
                };
                let id = match generation.allocate_id() {
                    Ok(id) => id,
                    Err(error) => {
                        let _ = response_tx.send(error);
                        continue;
                    }
                };

                // SAFETY: native_id is in RegisterHotKey's application range and
                // unused by this worker, key_code was checked nonzero, and a
                // null HWND requests a hotkey owned by the current message-loop
                // thread. All inputs are scalars.
                let ok = unsafe {
                    RegisterHotKey(
                        ptr::null_mut(),
                        native_id,
                        register_modifiers(modifiers),
                        key_code,
                    ) != 0
                };
                if !ok {
                    let _ = response_tx.send(REGISTER_STATUS_UNAVAILABLE);
                    continue;
                }

                generation.insert(id, callback.clone());
                registrations.insert(native_id, RegisteredHotKey { id, callback });
                let _ = response_tx.send(id);
            }

            Command::Unregister { id, response_tx } => {
                let Some((&native_id, _)) = registrations
                    .iter()
                    .find(|(_, registered)| registered.id == id)
                else {
                    let _ = response_tx.send(false);
                    continue;
                };

                // SAFETY: The registrations map proves native_id is currently
                // registered by this worker with a null HWND; it is removed only
                // when this matching UnregisterHotKey call succeeds.
                let ok = unsafe { UnregisterHotKey(ptr::null_mut(), native_id) != 0 };
                if ok {
                    // Retire before reporting success so queued invocations
                    // skip this callback. The caller waits for an active
                    // invocation, leaving this worker free to process commands
                    // submitted reentrantly.
                    if let Some(registered) = registrations.remove(&native_id) {
                        registered.callback.retire();
                    }
                }
                let _ = response_tx.send(ok);
            }
        }
    }
}

fn register_modifiers(modifiers: u32) -> Uint {
    modifiers | MOD_NOREPEAT
}

fn free_native_id<T>(used: &HashMap<i32, T>) -> Option<i32> {
    (1..=MAX_NATIVE_ID).find(|id| !used.contains_key(id))
}

#[cfg(test)]
mod tests {
    use super::{free_native_id, register_modifiers, PendingQueue, MAX_NATIVE_ID, MOD_NOREPEAT};
    use std::collections::HashMap;

    #[test]
    fn register_modifiers_adds_no_repeat_flag() {
        assert_eq!(
            0x0002 | 0x0004 | MOD_NOREPEAT,
            register_modifiers(0x0002 | 0x0004)
        );
    }

    #[test]
    fn native_ids_reuse_released_slots_within_the_api_range() {
        assert_eq!(Some(1), free_native_id(&HashMap::<i32, ()>::new()));
        assert_eq!(Some(2), free_native_id(&HashMap::from([(1, 10), (3, 11)])));
        let full = (1..=MAX_NATIVE_ID).map(|id| (id, id)).collect();
        assert_eq!(None, free_native_id(&full));
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
