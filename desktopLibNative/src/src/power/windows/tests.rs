use super::*;
use std::sync::atomic::AtomicUsize;
use std::sync::Condvar;
use std::time::Duration;
use windows::core::GUID;
use windows::Win32::Foundation::E_FAIL;

// Foreign callbacks cannot capture test fixtures. Serialize the tests sharing
// this callback and communicate only through synchronized process-local state.
static TEST_LOCK: Mutex<()> = Mutex::new(());
static EVENTS: Mutex<Vec<(i32, thread::ThreadId)>> = Mutex::new(Vec::new());
static BLOCKED: Mutex<(bool, bool)> = Mutex::new((false, false));
static RELEASE: Condvar = Condvar::new();
static PARTIAL_WINDOW: AtomicUsize = AtomicUsize::new(0);
static SESSION_ATTEMPTS: AtomicUsize = AtomicUsize::new(0);
static SESSION_READY: AtomicBool = AtomicBool::new(false);
static SESSION_REGISTERED: Mutex<bool> = Mutex::new(false);
static SESSION_CHANGED: Condvar = Condvar::new();

fn register_session_when_ready(window: HWND) -> Result<()> {
    SESSION_ATTEMPTS.fetch_add(1, Ordering::Relaxed);
    if !SESSION_READY.load(Ordering::Acquire) {
        return Err(Error::from_hresult(HRESULT::from_win32(
            RPC_S_INVALID_BINDING.0 as u32,
        )));
    }
    register_session_notifications(window)?;
    *SESSION_REGISTERED.lock().unwrap() = true;
    SESSION_CHANGED.notify_all();
    Ok(())
}

fn subscribe_while_session_service_is_starting(observer: &mut NativeObserver) -> Result<()> {
    observer.register_session = register_session_when_ready;
    observer.subscribe()?;
    assert!(!observer.session_registered);
    assert!(observer.session_retry_timer);
    assert!(observer.display_notification.is_some());
    assert!(observer.suspend_notification.is_some());
    Ok(())
}

fn reset_session_service() {
    SESSION_ATTEMPTS.store(0, Ordering::Relaxed);
    SESSION_READY.store(false, Ordering::Release);
    *SESSION_REGISTERED.lock().unwrap() = false;
}

fn register_synthetic(callback: PowerEventCallback) -> i32 {
    // Exercise the real window procedure without racing the initial display
    // state notification Windows sends when subscribing. Separate lifecycle
    // tests below exercise actual subscription and removal.
    register_with(callback, |_| Ok(()))
}

unsafe extern "C" fn record(event: i32) {
    EVENTS.lock().unwrap().push((event, thread::current().id()));
}

unsafe extern "C" fn blocking_record(event: i32) {
    if event == SYSTEM_SLEEP {
        let mut blocked = BLOCKED.lock().unwrap();
        blocked.0 = true;
        RELEASE.notify_all();
        // Always time out if a regression prevents the test from releasing us.
        let _ = RELEASE
            .wait_timeout_while(blocked, Duration::from_secs(5), |state| !state.1)
            .unwrap();
    }
    // SAFETY: record is our static callback with no pointer arguments.
    unsafe { record(event) };
}

fn send(window: HWND, message: u32, wparam: usize, lparam: isize) -> LRESULT {
    // SAFETY: Tests target only their own observer window. Any pointer payload
    // belongs to the calling test and lives through this synchronous call.
    unsafe { SendMessageW(window, message, Some(WPARAM(wparam)), Some(LPARAM(lparam))) }
}

fn window_for(id: i32) -> HWND {
    let observer = registry().lock().unwrap().get(&id).unwrap().clone();
    let window = observer.lock().unwrap().window;
    HWND(window as *mut _)
}

fn current_session() -> u32 {
    let mut session = 0;
    // SAFETY: session is writable storage and the process ID is current.
    unsafe { ProcessIdToSessionId(GetCurrentProcessId(), &mut session) }.unwrap();
    session
}

#[repr(C)]
struct DisplaySetting {
    guid: GUID,
    length: u32,
    value: u32,
}

fn send_display(window: HWND, guid: GUID, length: u32, value: u32) {
    let setting = DisplaySetting {
        guid,
        length,
        value,
    };
    send(
        window,
        WM_POWERBROADCAST,
        PBT_POWERSETTINGCHANGE as usize,
        &setting as *const _ as isize,
    );
}

#[test]
fn real_window_delivers_power_and_session_events_synchronously() {
    let _serial = TEST_LOCK.lock().unwrap();
    let id = register_synthetic(Some(record));
    assert!(id > 0);
    let window = window_for(id);
    send(window, WM_NULL, 0, 0);
    EVENTS.lock().unwrap().clear();
    let session = current_session() as isize;

    send_display(window, GUID_SESSION_DISPLAY_STATUS, 4, 0);
    send_display(window, GUID_SESSION_DISPLAY_STATUS, 4, 1);
    for event in [
        PBT_APMSUSPEND,
        PBT_APMRESUMEAUTOMATIC,
        PBT_APMRESUMESUSPEND,
        PBT_APMRESUMECRITICAL,
    ] {
        assert_eq!(send(window, WM_POWERBROADCAST, event as usize, 0).0, 1);
    }
    for event in [
        WTS_SESSION_LOCK,
        WTS_SESSION_LOGOFF,
        WTS_CONSOLE_DISCONNECT,
        WTS_REMOTE_DISCONNECT,
    ] {
        send(window, WM_WTSSESSION_CHANGE, event as usize, session);
    }
    for event in [WTS_SESSION_UNLOCK, WTS_CONSOLE_CONNECT, WTS_REMOTE_CONNECT] {
        send(window, WM_WTSSESSION_CHANGE, event as usize, session);
    }
    // This message reaches only our window; it does not end the Windows session.
    send(window, WM_ENDSESSION, 1, ENDSESSION_LOGOFF as isize);
    send(window, WM_ENDSESSION, 1, 0);
    let observed = EVENTS.lock().unwrap().clone();
    assert_eq!(
        observed.iter().map(|(event, _)| *event).collect::<Vec<_>>(),
        vec![1, 2, 3, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 5, 5],
    );
    assert!(observed.iter().all(|(_, thread)| *thread == observed[0].1));
    assert_ne!(observed[0].1, thread::current().id());
    assert!(unregister(id));
}

#[test]
fn irrelevant_or_invalid_events_and_canceled_logoff_are_ignored() {
    let _serial = TEST_LOCK.lock().unwrap();
    let id = register_synthetic(Some(record));
    assert!(id > 0);
    let window = window_for(id);
    send(window, WM_NULL, 0, 0);
    EVENTS.lock().unwrap().clear();
    let session = current_session();

    send_display(window, GUID_SESSION_DISPLAY_STATUS, 4, 2);
    send_display(window, GUID_SESSION_DISPLAY_STATUS, 4, 99);
    send_display(window, GUID_SESSION_DISPLAY_STATUS, 0, 0);
    send_display(window, GUID_SESSION_DISPLAY_STATUS, 3, 0);
    send_display(window, GUID_SESSION_DISPLAY_STATUS, 5, 0);
    send_display(window, GUID::zeroed(), 4, 0);
    send(
        window,
        WM_POWERBROADCAST,
        PBT_POWERSETTINGCHANGE as usize,
        0,
    );
    send(
        window,
        WM_POWERBROADCAST,
        PBT_APMPOWERSTATUSCHANGE as usize,
        0,
    );
    send(
        window,
        WM_WTSSESSION_CHANGE,
        WTS_SESSION_LOCK as usize,
        session.wrapping_add(1) as isize,
    );
    send(
        window,
        WM_WTSSESSION_CHANGE,
        WTS_SESSION_LOGON as usize,
        session as isize,
    );
    assert_eq!(
        send(window, WM_QUERYENDSESSION, 0, ENDSESSION_LOGOFF as isize).0,
        1
    );
    send(window, WM_ENDSESSION, 0, ENDSESSION_LOGOFF as isize);
    assert!(EVENTS.lock().unwrap().is_empty());
    assert!(unregister(id));
}

#[test]
fn repeated_registrations_have_independent_lifetimes() {
    let _serial = TEST_LOCK.lock().unwrap();
    assert_eq!(register(None), REGISTER_STATUS_INTERNAL_ERROR);
    assert!(!unregister(-1));
    let first = register(Some(record));
    let second = register(Some(record));
    assert!(first > 0 && second > first);
    let first_window = window_for(first);
    let second_window = window_for(second);
    assert!(unregister(first));
    assert!(!unregister(first));
    // SAFETY: IsWindow accepts an opaque, potentially stale HWND without borrowing it.
    assert!(!unsafe { IsWindow(Some(first_window)) }.as_bool());
    send(second_window, WM_NULL, 0, 0);
    EVENTS.lock().unwrap().clear();
    send(second_window, WM_POWERBROADCAST, PBT_APMSUSPEND as usize, 0);
    assert_eq!(
        EVENTS
            .lock()
            .unwrap()
            .iter()
            .filter(|(event, _)| *event == SYSTEM_SLEEP)
            .count(),
        1
    );
    assert!(unregister(second));
    let third = register(Some(record));
    assert!(third > second);
    assert!(unregister(third));
}

#[test]
fn session_service_startup_is_retried_without_stopping_power_delivery() {
    let _serial = TEST_LOCK.lock().unwrap();
    reset_session_service();
    let id = register_with(Some(record), subscribe_while_session_service_is_starting);
    assert!(id > 0);
    let window = window_for(id);
    send(window, WM_NULL, 0, 0);
    EVENTS.lock().unwrap().clear();
    send(window, WM_POWERBROADCAST, PBT_APMSUSPEND as usize, 0);
    assert!(EVENTS
        .lock()
        .unwrap()
        .iter()
        .any(|(event, _)| *event == SYSTEM_SLEEP));

    SESSION_READY.store(true, Ordering::Release);
    // Use the real window timer and message loop to recover the subscription.
    let (registered, timeout) = SESSION_CHANGED
        .wait_timeout_while(
            SESSION_REGISTERED.lock().unwrap(),
            Duration::from_secs(5),
            |ready| !*ready,
        )
        .unwrap();
    assert!(!timeout.timed_out());
    assert!(*registered);
    drop(registered);
    assert!(SESSION_ATTEMPTS.load(Ordering::Relaxed) >= 2);
    send(
        window,
        WM_WTSSESSION_CHANGE,
        WTS_SESSION_LOCK as usize,
        current_session() as isize,
    );
    assert!(EVENTS
        .lock()
        .unwrap()
        .iter()
        .any(|(event, _)| *event == SESSION_INACTIVE));
    assert!(unregister(id));
}

#[test]
fn successful_retry_cancels_the_timer_and_ignores_stale_ticks() {
    let _serial = TEST_LOCK.lock().unwrap();
    reset_session_service();
    let mut observer = NativeObserver::new(-11, None, Arc::new(AtomicBool::new(true))).unwrap();
    subscribe_while_session_service_is_starting(&mut observer).unwrap();
    observer.retry_session_subscription().unwrap();
    assert_eq!(SESSION_ATTEMPTS.load(Ordering::Relaxed), 2);
    assert!(observer.session_retry_timer);
    assert!(!observer.session_registered);

    SESSION_READY.store(true, Ordering::Release);
    observer.retry_session_subscription().unwrap();
    assert!(observer.session_registered);
    assert!(!observer.session_retry_timer);
    observer.retry_session_subscription().unwrap();
    assert_eq!(SESSION_ATTEMPTS.load(Ordering::Relaxed), 3);
    assert!(observer.cleanup());
}

#[test]
fn unregister_cancels_session_retry_while_the_service_is_unavailable() {
    let _serial = TEST_LOCK.lock().unwrap();
    reset_session_service();
    let id = register_with(Some(record), subscribe_while_session_service_is_starting);
    assert!(id > 0);
    let window = window_for(id);
    let (done_tx, done_rx) = mpsc::channel();
    let cleanup = thread::spawn(move || done_tx.send(unregister(id)).unwrap());
    assert!(done_rx.recv_timeout(Duration::from_secs(5)).unwrap());
    cleanup.join().unwrap();
    assert!(!*SESSION_REGISTERED.lock().unwrap());
    // SAFETY: IsWindow accepts the scalar handle after the observer has joined.
    assert!(!unsafe { IsWindow(Some(window)) }.as_bool());
}

#[test]
fn unregister_waits_for_an_in_flight_callback_and_stops_queued_delivery() {
    let _serial = TEST_LOCK.lock().unwrap();
    *BLOCKED.lock().unwrap() = (false, false);
    let id = register_synthetic(Some(blocking_record));
    assert!(id > 0);
    let window = window_for(id);
    send(window, WM_NULL, 0, 0);
    EVENTS.lock().unwrap().clear();
    // SAFETY: Our live window receives only scalar synthetic power messages.
    unsafe {
        PostMessageW(
            Some(window),
            WM_POWERBROADCAST,
            WPARAM(PBT_APMSUSPEND as usize),
            LPARAM(0),
        )
        .unwrap();
        PostMessageW(
            Some(window),
            WM_POWERBROADCAST,
            WPARAM(PBT_APMSUSPEND as usize),
            LPARAM(0),
        )
        .unwrap();
    }
    let (blocked, timeout) = RELEASE
        .wait_timeout_while(BLOCKED.lock().unwrap(), Duration::from_secs(5), |state| {
            !state.0
        })
        .unwrap();
    assert!(!timeout.timed_out());
    drop(blocked);
    let active = registry()
        .lock()
        .unwrap()
        .get(&id)
        .unwrap()
        .lock()
        .unwrap()
        .active
        .clone();
    let (done_tx, done_rx) = mpsc::channel();
    let cleanup = thread::spawn(move || done_tx.send(unregister(id)).unwrap());
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while active.load(Ordering::Acquire) && std::time::Instant::now() < deadline {
        thread::yield_now();
    }
    assert!(!active.load(Ordering::Acquire));
    assert!(done_rx.try_recv().is_err());
    BLOCKED.lock().unwrap().1 = true;
    RELEASE.notify_all();
    assert!(done_rx.recv_timeout(Duration::from_secs(5)).unwrap());
    cleanup.join().unwrap();
    assert_eq!(EVENTS.lock().unwrap().len(), 1);
    // SAFETY: IsWindow only tests the scalar handle after the worker has joined.
    assert!(!unsafe { IsWindow(Some(window)) }.as_bool());
}

fn fail_after_session_subscription(observer: &mut NativeObserver) -> Result<()> {
    let window = observer.window.unwrap();
    PARTIAL_WINDOW.store(window.0 as usize, Ordering::Release);
    // SAFETY: The test's observer owns this live window and its cleanup removes
    // the real subscription even though the following power setup is simulated to fail.
    unsafe { WTSRegisterSessionNotification(window, NOTIFY_FOR_THIS_SESSION)? };
    observer.session_registered = true;
    Err(Error::from_hresult(E_FAIL))
}

fn fail_after_suspend_subscription(observer: &mut NativeObserver) -> Result<()> {
    let window = observer.window.unwrap();
    PARTIAL_WINDOW.store(window.0 as usize, Ordering::Release);
    // SAFETY: The test owns this live window and records both real subscriptions
    // for cleanup when the subsequent display subscription is simulated to fail.
    unsafe {
        WTSRegisterSessionNotification(window, NOTIFY_FOR_THIS_SESSION)?;
        observer.session_registered = true;
        observer.suspend_notification = Some(RegisterSuspendResumeNotification(
            HANDLE(window.0),
            DEVICE_NOTIFY_WINDOW_HANDLE,
        )?);
    }
    Err(Error::from_hresult(E_FAIL))
}

fn fail_after_all_subscriptions(observer: &mut NativeObserver) -> Result<()> {
    observer.subscribe()?;
    PARTIAL_WINDOW.store(observer.window.unwrap().0 as usize, Ordering::Release);
    Err(Error::from_hresult(E_FAIL))
}

fn fail_session_subscription(observer: &mut NativeObserver) -> Result<()> {
    PARTIAL_WINDOW.store(observer.window.unwrap().0 as usize, Ordering::Release);
    // Non-transient errors must still fail registration instead of starting a timer.
    observer.register_session = |_| Err(Error::from_hresult(E_FAIL));
    observer.subscribe()
}

fn fail_after_session_retry_is_scheduled(observer: &mut NativeObserver) -> Result<()> {
    reset_session_service();
    subscribe_while_session_service_is_starting(observer)?;
    PARTIAL_WINDOW.store(observer.window.unwrap().0 as usize, Ordering::Release);
    Err(Error::from_hresult(E_FAIL))
}

#[test]
fn partial_initialization_is_cleaned_up_before_registration_fails() {
    let _serial = TEST_LOCK.lock().unwrap();
    for subscribe in [
        fail_session_subscription as fn(&mut NativeObserver) -> Result<()>,
        fail_after_session_retry_is_scheduled,
        fail_after_session_subscription,
        fail_after_suspend_subscription,
        fail_after_all_subscriptions,
    ] {
        PARTIAL_WINDOW.store(0, Ordering::Release);
        // Negative test IDs cannot collide with production registration IDs.
        assert!(PowerThread::start(-10, Some(record), subscribe).is_none());
        let window = HWND(PARTIAL_WINDOW.load(Ordering::Acquire) as *mut _);
        assert!(!window.is_invalid());
        // SAFETY: IsWindow accepts stale handles and does not dereference them.
        assert!(!unsafe { IsWindow(Some(window)) }.as_bool());
        // Reusing the class name also verifies that failure removed the class.
        let mut retry = PowerThread::start(-10, Some(record), NativeObserver::subscribe).unwrap();
        assert!(retry.stop());
    }
}
