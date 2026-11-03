//! Registration identities survive replacement of the native command worker.
//! IDs never repeat within the process; each handle retains its original owner
//! and callback until native release and callback quiescence are established.
use super::{CallbackDispatcher, CallbackRegistration, REGISTER_STATUS_INTERNAL_ERROR};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

pub(super) struct Generation {
    dispatcher: CallbackDispatcher,
    stopped: AtomicBool,
}

struct Handle {
    owner: Arc<Generation>,
    callback: CallbackRegistration,
    released: AtomicBool,
}

struct Registry {
    next_id: i32,
    handles: HashMap<i32, Arc<Handle>>,
}

static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();

fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| {
        Mutex::new(Registry {
            next_id: 1,
            handles: HashMap::new(),
        })
    })
}

impl Generation {
    pub(super) fn new(dispatcher: CallbackDispatcher) -> Arc<Self> {
        Arc::new(Self {
            dispatcher,
            stopped: AtomicBool::new(false),
        })
    }

    pub(super) fn dispatcher(&self) -> &CallbackDispatcher {
        &self.dispatcher
    }

    /// Call before any native side effect. An ID left unused by a failed
    /// native registration is never handed out again.
    pub(super) fn allocate_id(&self) -> Result<i32, i32> {
        let mut registry = registry().lock().unwrap_or_else(|p| p.into_inner());
        let id = registry.next_id;
        registry.next_id = id.checked_add(1).ok_or(REGISTER_STATUS_INTERNAL_ERROR)?;
        Ok(id)
    }

    /// Call from the owning worker after the native registration succeeded.
    pub(super) fn insert(self: &Arc<Self>, id: i32, callback: CallbackRegistration) {
        debug_assert!(!self.stopped.load(Ordering::Acquire));
        let mut registry = registry().lock().unwrap_or_else(|p| p.into_inner());
        registry.handles.insert(
            id,
            Arc::new(Handle {
                owner: self.clone(),
                callback,
                released: AtomicBool::new(false),
            }),
        );
    }

    /// Call only after this worker's native registrations have been released.
    /// Retirement prevents queued callbacks from starting; unregister waits for
    /// callbacks already executing without blocking the command worker.
    pub(super) fn stopped(self: &Arc<Self>) {
        let registry = registry().lock().unwrap_or_else(|p| p.into_inner());
        for handle in registry
            .handles
            .values()
            .filter(|h| Arc::ptr_eq(&h.owner, self))
        {
            handle.callback.retire();
        }
        self.stopped.store(true, Ordering::Release);
    }
}

pub(super) fn unregister(id: i32, release: impl FnOnce(&Arc<Generation>) -> bool) -> bool {
    let handle = {
        let registry = registry().lock().unwrap_or_else(|p| p.into_inner());
        match registry.handles.get(&id) {
            Some(handle) => handle.clone(),
            // Previously completed release is idempotent; arbitrary IDs aren't.
            None => return id > 0 && id < registry.next_id,
        }
    };
    if !handle.released.load(Ordering::Acquire)
        && !handle.owner.stopped.load(Ordering::Acquire)
        && !release(&handle.owner)
        && !handle.owner.stopped.load(Ordering::Acquire)
    {
        return false;
    }
    handle.callback.retire();
    handle.released.store(true, Ordering::Release);
    if !handle.owner.dispatcher.is_current_thread() {
        handle.callback.wait_until_idle();
    }
    // A reentrant release must leave the handle visible while its callback is
    // executing, so another caller still waits before freeing the FFI callback.
    if !handle.callback.is_active() {
        registry()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .handles
            .remove(&id);
    }
    true
}

pub(super) fn callback_finished(id: i32, callback: &CallbackRegistration) {
    let mut registry = registry().lock().unwrap_or_else(|p| p.into_inner());
    if registry.handles.get(&id).is_some_and(|handle| {
        handle.released.load(Ordering::Acquire)
            && Arc::ptr_eq(&handle.callback.state, &callback.state)
    }) {
        registry.handles.remove(&id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;

    unsafe extern "C" fn noop(_: i32) {}

    fn owner() -> Arc<Generation> {
        Generation::new(CallbackDispatcher::start("hotkey-generation-test").unwrap())
    }

    fn registration(owner: &Arc<Generation>) -> (i32, CallbackRegistration) {
        registration_with(owner, noop)
    }

    fn registration_with(
        owner: &Arc<Generation>,
        callback: unsafe extern "C" fn(i32),
    ) -> (i32, CallbackRegistration) {
        let callback = CallbackRegistration::new(Some(callback)).unwrap();
        let id = owner.allocate_id().unwrap();
        owner.insert(id, callback.clone());
        (id, callback)
    }

    #[test]
    fn stopped_owner_releases_without_contacting_replacement() {
        let old = owner();
        let (old_id, old_callback) = registration(&old);
        old.stopped();
        let new = owner();
        let (new_id, new_callback) = registration(&new);
        assert_ne!(old_id, new_id);
        assert!(unregister(old_id, |_| panic!(
            "released stale ID on replacement"
        )));
        assert!(unregister(old_id, |_| panic!(
            "repeated cleanup contacted worker"
        )));
        assert!(!old_callback.begin_invoke()); // Queued callbacks cannot start.
        assert!(new_callback.begin_invoke());
        new_callback.finish_invoke();
        new.stopped();
        assert!(unregister(new_id, |_| unreachable!()));
    }

    #[test]
    fn live_owner_cleanup_failure_is_not_mistaken_for_termination() {
        let owner = owner();
        let (id, callback) = registration(&owner);
        assert!(!unregister(id, |_| false));
        assert!(callback.begin_invoke());
        callback.finish_invoke();
        // Worker can finish shutting down during the failed command.
        assert!(unregister(id, |actual| {
            actual.stopped();
            false
        }));
        assert!(!callback.begin_invoke());
        assert!(!unregister(0, |_| unreachable!()));
        assert!(!unregister(i32::MAX, |_| unreachable!()));
    }

    #[test]
    fn terminated_generation_waits_for_executing_callback() {
        let owner = owner();
        let (id, callback) = registration(&owner);
        assert!(callback.begin_invoke());
        owner.stopped();
        let (done_tx, done_rx) = mpsc::channel();
        let waiter = thread::spawn(move || {
            done_tx.send(unregister(id, |_| unreachable!())).unwrap();
        });
        assert!(done_rx.recv_timeout(Duration::from_millis(30)).is_err());
        callback.finish_invoke();
        assert!(done_rx.recv_timeout(Duration::from_secs(1)).unwrap());
        waiter.join().unwrap();
    }

    struct ReentrantContext {
        entered: mpsc::Sender<bool>,
        resume: mpsc::Receiver<()>,
        other_id: i32,
    }

    static REENTRANT: Mutex<Option<ReentrantContext>> = Mutex::new(None);

    unsafe extern "C" fn release_from_callback(id: i32) {
        let ReentrantContext {
            entered,
            resume,
            other_id,
        } = REENTRANT.lock().unwrap().take().unwrap();
        let result = unregister(id, |_| true) && unregister(other_id, |_| true);
        entered.send(result).unwrap();
        resume.recv().unwrap();
    }

    #[test]
    fn reentrant_release_keeps_external_cleanup_waiting_until_callback_returns() {
        let owner = owner();
        let (id, callback) = registration_with(&owner, release_from_callback);
        let (entered_tx, entered_rx) = mpsc::channel();
        let (resume_tx, resume_rx) = mpsc::channel();
        let (other_id, _) = registration(&owner);
        *REENTRANT.lock().unwrap() = Some(ReentrantContext {
            entered: entered_tx,
            resume: resume_rx,
            other_id,
        });
        assert!(owner.dispatcher().dispatch(&callback, id));
        assert!(entered_rx.recv_timeout(Duration::from_secs(1)).unwrap());
        assert!(!registry().lock().unwrap().handles.contains_key(&other_id));
        let (done_tx, done_rx) = mpsc::channel();
        let waiter = thread::spawn(move || {
            done_tx
                .send(unregister(id, |_| {
                    panic!("native release already completed")
                }))
                .unwrap();
        });
        assert!(done_rx.recv_timeout(Duration::from_millis(30)).is_err());
        resume_tx.send(()).unwrap();
        assert!(done_rx.recv_timeout(Duration::from_secs(1)).unwrap());
        waiter.join().unwrap();
        assert!(!callback.begin_invoke());
        assert!(unregister(id, |_| unreachable!()));
        assert!(!registry().lock().unwrap().handles.contains_key(&id));
    }

    #[test]
    fn delayed_completion_cannot_remove_a_different_callback() {
        let owner = owner();
        let (id, callback) = registration(&owner);
        let unrelated = CallbackRegistration::new(Some(noop)).unwrap();
        // Model the retained handle after reentrant unregister.
        registry().lock().unwrap().handles[&id]
            .released
            .store(true, Ordering::Release);
        callback_finished(id, &unrelated);
        assert!(registry().lock().unwrap().handles.contains_key(&id));
        callback_finished(id, &callback);
        assert!(!registry().lock().unwrap().handles.contains_key(&id));
        assert!(unregister(id, |_| unreachable!()));
    }

    static CALLBACK_IDS: Mutex<Option<mpsc::Sender<i32>>> = Mutex::new(None);

    fn send_callback_id(id: i32) {
        if let Some(tx) = CALLBACK_IDS.lock().unwrap().as_ref() {
            let _ = tx.send(id);
        }
    }

    unsafe extern "C" fn release_self_from_callback(id: i32) {
        send_callback_id(if unregister(id, |_| true) { -id } else { id });
    }

    unsafe extern "C" fn record_id(id: i32) {
        send_callback_id(id);
    }

    #[test]
    fn reentrant_release_skips_queued_invocations() {
        let owner = owner();
        let (tx, rx) = mpsc::channel();
        *CALLBACK_IDS.lock().unwrap() = Some(tx);
        let (id, callback) = registration_with(&owner, release_self_from_callback);
        assert!(owner.dispatcher().dispatch(&callback, id));
        // The dispatcher thread skips the wait instead of waiting on itself.
        assert_eq!(-id, rx.recv_timeout(Duration::from_secs(1)).unwrap());
        assert!(owner.dispatcher().dispatch(&callback, id));
        let (live_id, live_callback) = registration_with(&owner, record_id);
        assert!(owner.dispatcher().dispatch(&live_callback, live_id));
        assert_eq!(live_id, rx.recv_timeout(Duration::from_secs(1)).unwrap());
        assert!(unregister(live_id, |_| true));
        *CALLBACK_IDS.lock().unwrap() = None;
    }

    struct WaitingContext {
        entered: mpsc::Sender<()>,
        proceed: mpsc::Receiver<()>,
        other_id: i32,
        result: mpsc::Sender<bool>,
    }

    static WAITING: Mutex<Option<WaitingContext>> = Mutex::new(None);

    unsafe extern "C" fn release_other_while_external_waits(_: i32) {
        let WaitingContext {
            entered,
            proceed,
            other_id,
            result,
        } = WAITING.lock().unwrap().take().unwrap();
        entered.send(()).unwrap();
        proceed.recv().unwrap();
        result.send(unregister(other_id, |_| true)).unwrap();
    }

    #[test]
    fn external_wait_does_not_block_reentrant_release() {
        let owner = owner();
        let (id, callback) = registration_with(&owner, release_other_while_external_waits);
        let (other_id, _) = registration(&owner);
        let (entered_tx, entered_rx) = mpsc::channel();
        let (proceed_tx, proceed_rx) = mpsc::channel();
        let (result_tx, result_rx) = mpsc::channel();
        *WAITING.lock().unwrap() = Some(WaitingContext {
            entered: entered_tx,
            proceed: proceed_rx,
            other_id,
            result: result_tx,
        });
        assert!(owner.dispatcher().dispatch(&callback, id));
        entered_rx.recv_timeout(Duration::from_secs(1)).unwrap();
        let (released_tx, released_rx) = mpsc::channel();
        let (done_tx, done_rx) = mpsc::channel();
        let external = thread::spawn(move || {
            let result = unregister(id, |_| {
                released_tx.send(()).unwrap();
                true
            });
            done_tx.send(result).unwrap();
        });
        // Native release is done; the external caller waits for the callback.
        released_rx.recv_timeout(Duration::from_secs(1)).unwrap();
        assert!(done_rx.recv_timeout(Duration::from_millis(30)).is_err());
        proceed_tx.send(()).unwrap();
        assert!(result_rx.recv_timeout(Duration::from_secs(1)).unwrap());
        assert!(done_rx.recv_timeout(Duration::from_secs(1)).unwrap());
        external.join().unwrap();
    }
}
