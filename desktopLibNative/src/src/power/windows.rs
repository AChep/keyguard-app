use crate::ffi::{with_ffi_boundary, PowerEventCallback, REGISTER_STATUS_INTERNAL_ERROR};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use windows::core::{Error, Result, HRESULT, PCWSTR};
use windows::Win32::Foundation::{HANDLE, HINSTANCE, HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Power::{
    RegisterPowerSettingNotification, RegisterSuspendResumeNotification,
    UnregisterPowerSettingNotification, UnregisterSuspendResumeNotification, HPOWERNOTIFY,
    POWERBROADCAST_SETTING,
};
use windows::Win32::System::RemoteDesktop::{
    ProcessIdToSessionId, WTSRegisterSessionNotification, WTSUnRegisterSessionNotification,
    NOTIFY_FOR_THIS_SESSION,
};
use windows::Win32::System::Rpc::RPC_S_INVALID_BINDING;
use windows::Win32::System::SystemServices::GUID_SESSION_DISPLAY_STATUS;
use windows::Win32::System::Threading::GetCurrentProcessId;
use windows::Win32::UI::WindowsAndMessaging::*;

// These values are part of the DesktopPowerEvent JVM bridge contract.
const DISPLAY_SLEEP: i32 = 1;
const DISPLAY_WAKE: i32 = 2;
const SYSTEM_SLEEP: i32 = 3;
const SYSTEM_WAKE: i32 = 4;
const SESSION_INACTIVE: i32 = 5;
const SESSION_ACTIVE: i32 = 6;
const WM_STOP_OBSERVER: u32 = WM_APP + 1;
const SESSION_RETRY_TIMER: usize = 1;
const SESSION_RETRY_INTERVAL_MS: u32 = 1_000;

type Registry = Mutex<HashMap<i32, Arc<Mutex<PowerThread>>>>;
static OBSERVERS: OnceLock<Registry> = OnceLock::new();
static NEXT_ID: AtomicI32 = AtomicI32::new(1);

fn registry() -> &'static Registry {
    OBSERVERS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn register(callback: PowerEventCallback) -> i32 {
    register_with(callback, NativeObserver::subscribe)
}

fn register_with(
    callback: PowerEventCallback,
    subscribe: fn(&mut NativeObserver) -> Result<()>,
) -> i32 {
    if callback.is_none() {
        return REGISTER_STATUS_INTERNAL_ERROR;
    }
    let Ok(id) = NEXT_ID.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
    else {
        return REGISTER_STATUS_INTERNAL_ERROR;
    };
    let Some(observer) = PowerThread::start(id, callback, subscribe) else {
        return REGISTER_STATUS_INTERNAL_ERROR;
    };
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(id, Arc::new(Mutex::new(observer)));
    id
}

pub(crate) fn unregister(id: i32) -> bool {
    let observer = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&id)
        .cloned();
    let Some(observer) = observer else {
        return false;
    };
    // Never hold the registry lock while waiting for a callback to finish.
    if !observer.lock().unwrap_or_else(|e| e.into_inner()).stop() {
        return false;
    }
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&id);
    true
}

struct PowerThread {
    // HWND is used only as an opaque message destination across threads. All
    // window ownership and borrowed window state remain on the worker.
    window: usize,
    active: Arc<AtomicBool>,
    worker: Option<JoinHandle<bool>>,
}

impl PowerThread {
    fn start(
        id: i32,
        callback: PowerEventCallback,
        subscribe: fn(&mut NativeObserver) -> Result<()>,
    ) -> Option<Self> {
        let active = Arc::new(AtomicBool::new(true));
        let worker_active = Arc::clone(&active);
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let worker = thread::Builder::new()
            .name("keyguard-power-windows".to_owned())
            .spawn(move || {
                // RAII also tears down partially initialized subscriptions on
                // failure. Tests inject a failure after allocating OS resources.
                let mut observer = match NativeObserver::new(id, callback, worker_active) {
                    Ok(observer) => observer,
                    Err(error) => {
                        eprintln!("keyguard-lib::power window initialization failed: {error}");
                        return false;
                    }
                };
                if let Err(error) = subscribe(&mut observer) {
                    eprintln!("keyguard-lib::power subscription failed: {error}");
                    return false;
                }
                if ready_tx.send(observer.window.unwrap().0 as usize).is_err() {
                    return false;
                }
                let mut message = MSG::default();
                loop {
                    // SAFETY: message is writable MSG storage. This worker owns
                    // the window and reads a message only after a positive result.
                    let result = unsafe { GetMessageW(&mut message, None, 0, 0) }.0;
                    if result <= 0 {
                        if result < 0 {
                            eprintln!("keyguard-lib::power message loop failed");
                        }
                        break;
                    }
                    if message.message == WM_TIMER
                        && Some(message.hwnd) == observer.window
                        && message.wParam.0 == SESSION_RETRY_TIMER
                    {
                        if let Err(error) = observer.retry_session_subscription() {
                            eprintln!(
                                "keyguard-lib::power session subscription retry failed: {error}"
                            );
                        }
                        continue;
                    }
                    // SAFETY: GetMessageW initialized message; its window and
                    // WindowState are alive on this same thread until cleanup.
                    unsafe { DispatchMessageW(&message) };
                }
                observer.cleanup()
            })
            .ok()?;
        match ready_rx.recv() {
            Ok(window) => Some(Self {
                window,
                active,
                worker: Some(worker),
            }),
            Err(_) => {
                active.store(false, Ordering::Release);
                // A negative registration result lets the JVM release its
                // callback, so even failed startup must join the worker first.
                let _ = worker.join();
                None
            }
        }
    }

    fn stop(&mut self) -> bool {
        let Some(worker) = self.worker.as_ref() else {
            return false;
        };
        // The callback contract forbids self-unregistration. Fail instead of
        // trying to join the calling worker if a native consumer violates it.
        if worker.thread().id() == thread::current().id() {
            return false;
        }
        self.active.store(false, Ordering::Release);
        if !worker.is_finished() {
            // SAFETY: window is an opaque HWND created by this worker. The
            // private message carries no pointers or borrowed Rust memory.
            if unsafe {
                PostMessageW(
                    Some(HWND(self.window as *mut _)),
                    WM_STOP_OBSERVER,
                    WPARAM(0),
                    LPARAM(0),
                )
            }
            .is_err()
            {
                return false;
            }
        }
        self.worker.take().unwrap().join().unwrap_or(false)
    }
}

struct WindowState {
    callback: PowerEventCallback,
    active: Arc<AtomicBool>,
    session: u32,
}

struct NativeObserver {
    instance: HINSTANCE,
    class_name: Vec<u16>,
    class_registered: bool,
    window: Option<HWND>,
    display_notification: Option<HPOWERNOTIFY>,
    suspend_notification: Option<HPOWERNOTIFY>,
    session_registered: bool,
    session_retry_timer: bool,
    register_session: fn(HWND) -> Result<()>,
    // Raw ownership keeps the window's pointer stable without retaining a Rust
    // reference across reentrant Windows calls. Cleanup reclaims the box.
    state: *mut WindowState,
}

impl NativeObserver {
    fn new(id: i32, callback: PowerEventCallback, active: Arc<AtomicBool>) -> Result<Self> {
        let mut session = 0;
        // SAFETY: The process ID is current and session is writable scalar storage.
        unsafe { ProcessIdToSessionId(GetCurrentProcessId(), &mut session)? };
        // SAFETY: A null module name borrows the process module for its lifetime.
        let instance = unsafe { GetModuleHandleW(None)? }.into();
        let mut observer = Self {
            instance,
            class_name: format!("KeyguardPowerObserver-{id}")
                .encode_utf16()
                .chain(Some(0))
                .collect(),
            class_registered: false,
            window: None,
            display_notification: None,
            suspend_notification: None,
            session_registered: false,
            session_retry_timer: false,
            register_session: register_session_notifications,
            state: Box::into_raw(Box::new(WindowState {
                callback,
                active,
                session,
            })),
        };
        let class = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: instance,
            lpszClassName: PCWSTR(observer.class_name.as_ptr()),
            ..Default::default()
        };
        // SAFETY: The class name is terminated and remains alive until the
        // class is unregistered. The procedure has the required system ABI.
        if unsafe { RegisterClassW(&class) } == 0 {
            return Err(Error::from_thread());
        }
        observer.class_registered = true;
        // SAFETY: WindowState is boxed and outlives the window. WM_NCCREATE
        // installs its stable address. No parent and no WS_VISIBLE create a
        // hidden top-level window: message-only windows miss power broadcasts.
        observer.window = Some(unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                PCWSTR(observer.class_name.as_ptr()),
                windows::core::w!("Keyguard power observer"),
                WINDOW_STYLE::default(),
                0,
                0,
                0,
                0,
                None,
                None,
                Some(instance),
                Some(observer.state.cast()),
            )?
        });
        Ok(observer)
    }

    fn subscribe(&mut self) -> Result<()> {
        let window = self.window.unwrap();
        if !self.subscribe_session()? {
            // Remote Desktop Services may still be starting at login. Retry on
            // this window's message loop so power delivery and stop stay live.
            // SAFETY: This thread owns window. No timer callback or borrowed
            // pointer is installed; cleanup cancels the timer before destruction.
            if unsafe {
                SetTimer(
                    Some(window),
                    SESSION_RETRY_TIMER,
                    SESSION_RETRY_INTERVAL_MS,
                    None,
                )
            } == 0
            {
                return Err(Error::from_thread());
            }
            self.session_retry_timer = true;
        }
        // Modern Standby requires explicitly opting in to suspend/resume messages.
        // SAFETY: Our live window receives notifications until cleanup unregisters
        // this handle, before destroying the window on its owning thread.
        self.suspend_notification = Some(unsafe {
            RegisterSuspendResumeNotification(HANDLE(window.0), DEVICE_NOTIFY_WINDOW_HANDLE)?
        });
        // SAFETY: DEVICE_NOTIFY_WINDOW_HANDLE interprets the recipient as our
        // live window. The returned registration handle is owned until cleanup.
        self.display_notification = Some(unsafe {
            RegisterPowerSettingNotification(
                HANDLE(window.0),
                &GUID_SESSION_DISPLAY_STATUS,
                DEVICE_NOTIFY_WINDOW_HANDLE,
            )?
        });
        Ok(())
    }

    fn subscribe_session(&mut self) -> Result<bool> {
        if self.session_registered {
            return Ok(true);
        }
        match (self.register_session)(self.window.unwrap()) {
            Ok(()) => {
                self.session_registered = true;
                Ok(true)
            }
            Err(error) if error.code() == HRESULT::from_win32(RPC_S_INVALID_BINDING.0 as u32) => {
                Ok(false)
            }
            Err(error) => Err(error),
        }
    }

    fn retry_session_subscription(&mut self) -> Result<()> {
        // KillTimer does not remove already queued messages. Once registration
        // succeeds, stale timer messages must not register the window again.
        if self.session_retry_timer && self.subscribe_session()? {
            self.cancel_session_retry()?;
        }
        Ok(())
    }

    fn cancel_session_retry(&mut self) -> Result<()> {
        if self.session_retry_timer {
            // SAFETY: This is the timer installed on our still-live window,
            // and cancellation runs only on that window's owning thread.
            unsafe { KillTimer(self.window, SESSION_RETRY_TIMER)? };
            self.session_retry_timer = false;
        }
        Ok(())
    }

    fn cleanup(&mut self) -> bool {
        if !self.state.is_null() {
            // SAFETY: The raw pointer owns the box allocated during new(); only
            // this thread can free it, after the recipient window is gone.
            unsafe { (*self.state).active.store(false, Ordering::Release) };
        }
        let mut success = self.cancel_session_retry().is_ok();
        // DestroyWindow also removes its timers if explicit cancellation fails.
        self.session_retry_timer = false;
        if let Some(notification) = self.display_notification.take() {
            // SAFETY: This handle was returned by power-setting registration and is
            // consumed once, before the recipient window is destroyed.
            success &= unsafe { UnregisterPowerSettingNotification(notification) }.is_ok();
        }
        if let Some(notification) = self.suspend_notification.take() {
            // SAFETY: This handle was returned by suspend/resume registration and is
            // consumed once, before the recipient window is destroyed.
            success &= unsafe { UnregisterSuspendResumeNotification(notification) }.is_ok();
        }
        if let Some(window) = self.window.take() {
            if self.session_registered {
                self.session_registered = false;
                // SAFETY: This thread owns the still-live registered window.
                success &= unsafe { WTSUnRegisterSessionNotification(window) }.is_ok();
            }
            // SAFETY: Destruction occurs on the creating thread, with the
            // boxed state still alive for any synchronous final window messages.
            if unsafe { DestroyWindow(window) }.is_err() {
                success = false;
                // Keep inert state alive if Windows could still reference it
                // during thread exit. Never leave GWLP_USERDATA dangling.
                self.state = std::ptr::null_mut();
            }
        }
        if self.class_registered {
            self.class_registered = false;
            // SAFETY: The terminated name and process module remain valid.
            // No new windows can be created for this registration's unique class.
            success &=
                unsafe { UnregisterClassW(PCWSTR(self.class_name.as_ptr()), Some(self.instance)) }
                    .is_ok();
        }
        if !self.state.is_null() {
            let state = std::mem::replace(&mut self.state, std::ptr::null_mut());
            // SAFETY: The window was destroyed (or never created) and cannot
            // access this uniquely owned allocation. Reclaim it exactly once.
            unsafe { drop(Box::from_raw(state)) };
        }
        success
    }
}

impl Drop for NativeObserver {
    fn drop(&mut self) {
        if !self.cleanup() {
            eprintln!("keyguard-lib::power observer cleanup failed");
        }
    }
}

fn register_session_notifications(window: HWND) -> Result<()> {
    // SAFETY: The observer calls this only with its live window on the owning
    // thread, and balances successful registration before destroying the window.
    unsafe { WTSRegisterSessionNotification(window, NOTIFY_FOR_THIS_SESSION) }
}

unsafe extern "system" fn window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    // No Rust panic may cross the Windows callback boundary. In particular,
    // this observer must never veto Windows session termination.
    let fallback = LRESULT(isize::from(message == WM_QUERYENDSESSION));
    with_ffi_boundary("power::window_proc", fallback, || {
        // SAFETY: Windows invokes this procedure with message-specific data
        // valid for the duration of this call on the owning window thread.
        Ok(unsafe { handle_message(window, message, wparam, lparam) })
    })
}

unsafe fn handle_message(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if message == WM_NCCREATE {
        // SAFETY: WM_NCCREATE supplies CREATESTRUCTW, whose lpCreateParams is
        // the stable Box<WindowState> address supplied to CreateWindowExW.
        unsafe {
            let create = &*(lparam.0 as *const CREATESTRUCTW);
            SetWindowLongPtrW(window, GWLP_USERDATA, create.lpCreateParams as isize);
        }
    }
    // SAFETY: Only WM_NCCREATE writes this field, with the owning observer's
    // boxed state. That box is retained until after DestroyWindow completes.
    let state = unsafe { GetWindowLongPtrW(window, GWLP_USERDATA) } as *const WindowState;
    if message == WM_QUERYENDSESSION {
        return LRESULT(1);
    }
    if message == WM_STOP_OBSERVER || message == WM_CLOSE || message == WM_NCDESTROY {
        if !state.is_null() {
            // SAFETY: State is live on the creating thread as described above.
            unsafe { (*state).active.store(false, Ordering::Release) };
        }
        // SAFETY: We clear our own window's borrowed pointer before destruction
        // and request termination of only the calling observer's message loop.
        unsafe {
            if message == WM_NCDESTROY {
                SetWindowLongPtrW(window, GWLP_USERDATA, 0);
            }
            PostQuitMessage(0);
        }
        if message != WM_NCDESTROY {
            return LRESULT(0);
        }
    }
    if !state.is_null() {
        // SAFETY: Copy values before calling foreign code; no mutable borrow
        // of window state spans a potentially reentrant callback.
        let (callback, session) = unsafe {
            let state = &*state;
            (
                state
                    .callback
                    .filter(|_| state.active.load(Ordering::Acquire)),
                state.session,
            )
        };
        if let Some(callback) = callback {
            // SAFETY: Windows owns any power payload for this invocation.
            if let Some(event) = unsafe { message_event(message, wparam, lparam, session) } {
                // SAFETY: Registration requires callback retention through a
                // successful unregister, which joins this thread. Delivery is
                // synchronous so suspend waits for the bounded JVM lock attempt.
                unsafe { callback(event) };
            }
        }
    }
    if message == WM_POWERBROADCAST {
        return LRESULT(1);
    }
    if message == WM_WTSSESSION_CHANGE || message == WM_ENDSESSION {
        return LRESULT(0);
    }
    // SAFETY: Unhandled messages retain their original Windows parameters.
    unsafe { DefWindowProcW(window, message, wparam, lparam) }
}

unsafe fn message_event(message: u32, wparam: WPARAM, lparam: LPARAM, session: u32) -> Option<i32> {
    match message {
        WM_POWERBROADCAST => match wparam.0 as u32 {
            PBT_APMSUSPEND => Some(SYSTEM_SLEEP),
            PBT_APMRESUMEAUTOMATIC | PBT_APMRESUMESUSPEND | PBT_APMRESUMECRITICAL => {
                Some(SYSTEM_WAKE)
            }
            PBT_POWERSETTINGCHANGE if lparam.0 != 0 => {
                let setting = lparam.0 as *const POWERBROADCAST_SETTING;
                // SAFETY: Windows supplies at least the fixed header of the
                // power payload. Read the flexible data only after checking its
                // length, without assuming its DWORD has Rust reference alignment.
                unsafe {
                    if (*setting).PowerSetting != GUID_SESSION_DISPLAY_STATUS
                        || (*setting).DataLength != size_of::<u32>() as u32
                    {
                        return None;
                    }
                    match std::ptr::addr_of!((*setting).Data)
                        .cast::<u32>()
                        .read_unaligned()
                    {
                        0 => Some(DISPLAY_SLEEP),
                        1 => Some(DISPLAY_WAKE),
                        _ => None,
                    }
                }
            }
            _ => None,
        },
        WM_WTSSESSION_CHANGE if lparam.0 == session as isize => match wparam.0 as u32 {
            WTS_SESSION_LOCK
            | WTS_SESSION_LOGOFF
            | WTS_CONSOLE_DISCONNECT
            | WTS_REMOTE_DISCONNECT => Some(SESSION_INACTIVE),
            WTS_SESSION_UNLOCK | WTS_CONSOLE_CONNECT | WTS_REMOTE_CONNECT => Some(SESSION_ACTIVE),
            _ => None,
        },
        WM_ENDSESSION if wparam.0 != 0 => Some(SESSION_INACTIVE),
        _ => None,
    }
}

#[cfg(test)]
mod tests;
