//! Handles and scalar operations shared by the JNI and C adapters.
//!
//! Handles are positive, never 0 and never reused; listeners and connections
//! share one counter. At most [`MAX_LIVE_HANDLES`] are live. A lookup clones the
//! object's `Arc` and releases the lock before the call blocks, so closing never
//! waits for a blocked call: close removes the handle, then wakes the call. The
//! object itself is freed when its last call returns.
//!
//! Each adapter export runs in [`export`]. A failed operation leaves
//! `<operation>: <reason>[ (os N)]` in this thread's [`last_error`]: ASCII, at most
//! [`MAX_DIAGNOSTIC_BYTES`], never a path, a name or a payload.

use std::{
    cell::Cell,
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, AtomicUsize, Ordering},
    },
};

use keyguard_ffi::{BRIDGE_INTERNAL, BRIDGE_PANIC, PanicHook};

use crate::{Error, Failure, Listener, Location, Readiness, Result, platform};

/// Most handles live at once, listeners and connections together.
pub const MAX_LIVE_HANDLES: usize = 1024;
/// Most bytes one [`read`] fills; a larger buffer is filled partially.
pub const MAX_READ_BYTES: usize = 1 << 20;
/// Most bytes one [`write`] takes.
pub const MAX_WRITE_BYTES: usize = 32 << 20;
/// Longest diagnostic [`last_error`] returns.
pub const MAX_DIAGNOSTIC_BYTES: usize = 128;

thread_local! {
    static LAST_FAILURE: Cell<Option<Failure>> = const { Cell::new(None) };
}

/// Clears this thread's diagnostic.
pub fn clear_error() {
    LAST_FAILURE.set(None);
}

/// This thread's diagnostic from the last failed operation, if any. Reading it does
/// not clear it; the next [`export`] does.
#[must_use]
pub fn last_error() -> Option<String> {
    LAST_FAILURE.get().map(diagnostic)
}

fn diagnostic(failure: Failure) -> String {
    let mut text = failure.to_string();
    text.retain(|character| character.is_ascii());
    text.truncate(MAX_DIAGNOSTIC_BYTES);
    text
}

/// Records `failure` as this thread's diagnostic and returns its code.
fn fail(failure: Failure) -> i64 {
    LAST_FAILURE.set(Some(failure));
    failure.kind().code()
}

fn outcome(result: Result<i64>) -> i64 {
    result.unwrap_or_else(fail)
}

/// Runs one adapter export named `operation`.
///
/// It clears this thread's diagnostic, then runs `body` behind a panic boundary.
/// `Ok` carries an operation's result unchanged. `Err` carries a failure of the
/// adapter itself; keyguard-ffi's packed failures become this module's codes: a
/// panic is [`Error::Internal`], a rejected argument [`Error::InvalidArgument`].
///
/// `hook` is the adapter's [`PanicHook`]: `Keep` under its own `cfg(test)`,
/// otherwise `Redact`.
pub fn export(
    hook: PanicHook,
    operation: &'static str,
    body: impl FnOnce() -> std::result::Result<i64, i64>,
) -> i64 {
    clear_error();
    match keyguard_ffi::contained(hook, body) {
        Ok(Ok(code)) => code,
        Ok(Err(code)) | Err(code) => fail(adapter_failure(operation, code)),
    }
}

fn adapter_failure(operation: &'static str, code: i64) -> Failure {
    match code {
        BRIDGE_PANIC => Failure::new(Error::Internal, operation, "panic"),
        BRIDGE_INTERNAL => Failure::new(Error::Internal, operation, "bridge"),
        code if code == Error::Internal.code() => {
            Failure::new(Error::Internal, operation, "bridge")
        }
        // keyguard-ffi's argument readers and the adapters' own argument checks.
        _ => Failure::new(Error::InvalidArgument, operation, "invalid_argument"),
    }
}

/// Binds a listener and returns its handle. The listener is connectable on return.
///
/// An empty `parent` or `exact_name` is none. Unix needs a parent and Windows takes
/// none. Without an exact name the endpoint gets a random one.
#[must_use]
pub fn listen(parent: &str, prefix: &str, exact_name: &str) -> i64 {
    let exact = (!exact_name.is_empty()).then_some(exact_name);
    outcome(location(parent, prefix, exact).and_then(|location| {
        let listener = Listener::bind(location)?.0;
        let objects = objects();
        objects
            .register(&objects.listeners, &listener, "listen")
            .inspect_err(|_| listener.close())
    }))
}

#[cfg(unix)]
fn location<'a>(parent: &'a str, prefix: &'a str, exact: Option<&'a str>) -> Result<Location<'a>> {
    if parent.is_empty() {
        return Err(Failure::new(
            Error::InvalidArgument,
            "listen",
            "invalid_parent",
        ));
    }
    Ok(Location::Unix {
        parent,
        prefix,
        exact,
    })
}

#[cfg(windows)]
fn location<'a>(parent: &'a str, prefix: &'a str, exact: Option<&'a str>) -> Result<Location<'a>> {
    if !parent.is_empty() {
        return Err(Failure::new(
            Error::InvalidArgument,
            "listen",
            "invalid_parent",
        ));
    }
    Ok(Location::Windows { prefix, exact })
}

/// Passes the listener's endpoint to `visit`, or returns a negative code.
pub fn endpoint<R>(listener: u64, visit: impl FnOnce(&str) -> R) -> std::result::Result<R, i64> {
    let objects = objects();
    match objects.listeners.get(listener, "endpoint") {
        Ok(listener) => Ok(visit(listener.endpoint())),
        Err(failure) => Err(fail(failure)),
    }
}

/// Blocks until a client connects and returns the connection's handle. `-3` after
/// the listener is closed; `-6` while another accept runs on it.
#[must_use]
pub fn accept(listener: u64) -> i64 {
    let objects = objects();
    outcome(
        objects
            .listeners
            .get(listener, "accept")
            .and_then(|listener| {
                let connection = Arc::new(listener.accept()?);
                objects
                    .register(&objects.connections, &connection, "accept")
                    .inspect_err(|_| connection.close())
            }),
    )
}

/// Closes a listener and consumes its handle. Wakes a blocked [`accept`]. Returns 0.
#[must_use]
pub fn close_listener(listener: u64) -> i64 {
    let objects = objects();
    outcome(
        objects
            .release(&objects.listeners, listener, "close_listener")
            .map(|listener| {
                listener.close();
                0
            }),
    )
}

/// The peer's process ID from the accept snapshot. `-7` when the peer is not
/// verifiable.
#[must_use]
pub fn peer_pid(connection: u64) -> i64 {
    let objects = objects();
    outcome(
        objects
            .connections
            .get(connection, "peer_pid")
            .and_then(|connection| connection.peer_pid())
            .map(i64::from),
    )
}

/// Reads up to `min(out.len(), MAX_READ_BYTES)` bytes and returns their count, or 0
/// at end of stream. An empty `out` is `-1`.
#[must_use]
pub fn read(connection: u64, out: &mut [u8]) -> i64 {
    if out.is_empty() {
        return fail(Failure::new(Error::InvalidArgument, "read", "empty_buffer"));
    }
    let objects = objects();
    outcome(
        objects
            .connections
            .get(connection, "read")
            .and_then(|connection| {
                let length = out.len().min(MAX_READ_BYTES);
                connection.read(&mut out[..length])
            })
            .map(|count| count as i64),
    )
}

/// Writes all of `data` and returns 0. Empty data returns 0 without a system call;
/// more than [`MAX_WRITE_BYTES`] is `-1`.
#[must_use]
pub fn write(connection: u64, data: &[u8]) -> i64 {
    if data.len() > MAX_WRITE_BYTES {
        return fail(Failure::new(Error::InvalidArgument, "write", "too_long"));
    }
    let objects = objects();
    outcome(
        objects
            .connections
            .get(connection, "write")
            .and_then(|connection| {
                if data.is_empty() {
                    return Ok(0);
                }
                connection.write_all(data).map(|()| 0)
            }),
    )
}

/// Waits without consuming input: 1 when input is readable, 0 at end of stream.
#[must_use]
pub fn await_input(connection: u64) -> i64 {
    let objects = objects();
    outcome(
        objects
            .connections
            .get(connection, "await_input")
            .and_then(|connection| connection.await_input())
            .map(|readiness| match readiness {
                Readiness::Input => 1,
                Readiness::Eof => 0,
            }),
    )
}

/// Closes a connection and consumes its handle. Wakes every blocked call on it.
/// Returns 0.
#[must_use]
pub fn close(connection: u64) -> i64 {
    let objects = objects();
    outcome(
        objects
            .release(&objects.connections, connection, "close")
            .map(|connection| {
                connection.close();
                0
            }),
    )
}

type Objects = Registry<platform::Listener, platform::Connection>;

fn objects() -> &'static Objects {
    static OBJECTS: OnceLock<Objects> = OnceLock::new();
    OBJECTS.get_or_init(Registry::new)
}

fn registry_failure(operation: &'static str) -> Failure {
    Failure::new(Error::Internal, operation, "registry")
}

/// Live objects of one kind by handle.
struct Table<T>(Mutex<HashMap<u64, Arc<T>>>);

impl<T> Table<T> {
    fn new() -> Self {
        Self(Mutex::new(HashMap::new()))
    }

    /// Clones the object's `Arc`; the lock is released on return.
    fn get(&self, handle: u64, operation: &'static str) -> Result<Arc<T>> {
        let entries = self.0.lock().map_err(|_| registry_failure(operation))?;
        entries.get(&handle).cloned().ok_or(Failure::new(
            Error::InvalidHandle,
            operation,
            "invalid_handle",
        ))
    }

    fn insert(&self, handle: u64, value: Arc<T>, operation: &'static str) -> Result<()> {
        let mut entries = self.0.lock().map_err(|_| registry_failure(operation))?;
        entries.insert(handle, value);
        Ok(())
    }

    fn remove(&self, handle: u64, operation: &'static str) -> Result<Arc<T>> {
        let mut entries = self.0.lock().map_err(|_| registry_failure(operation))?;
        entries.remove(&handle).ok_or(Failure::new(
            Error::InvalidHandle,
            operation,
            "invalid_handle",
        ))
    }
}

/// Listeners and connections, with one handle counter and one live-handle cap.
struct Registry<L, C> {
    next: AtomicU64,
    live: AtomicUsize,
    listeners: Table<L>,
    connections: Table<C>,
}

impl<L, C> Registry<L, C> {
    fn new() -> Self {
        Self {
            next: AtomicU64::new(1),
            live: AtomicUsize::new(0),
            listeners: Table::new(),
            connections: Table::new(),
        }
    }

    /// Stores a clone of `value` in `table` under a new handle. The caller closes
    /// `value` when this fails.
    fn register<T>(
        &self,
        table: &Table<T>,
        value: &Arc<T>,
        operation: &'static str,
    ) -> Result<i64> {
        self.live
            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |live| {
                (live < MAX_LIVE_HANDLES).then_some(live + 1)
            })
            .map_err(|_| Failure::new(Error::Io, operation, "handle_limit"))?;
        let handle = self.next.fetch_add(1, Ordering::SeqCst);
        let registered = i64::try_from(handle)
            .map_err(|_| Failure::new(Error::Internal, operation, "handle_exhausted"))
            .and_then(|code| {
                table
                    .insert(handle, Arc::clone(value), operation)
                    .map(|()| code)
            });
        if registered.is_err() {
            self.live.fetch_sub(1, Ordering::SeqCst);
        }
        registered
    }

    /// Removes the handle from `table`; it is consumed even if closing then fails.
    fn release<T>(&self, table: &Table<T>, handle: u64, operation: &'static str) -> Result<Arc<T>> {
        let value = table.remove(handle, operation)?;
        self.live.fetch_sub(1, Ordering::SeqCst);
        Ok(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    type TestRegistry = Registry<&'static str, u32>;

    fn last() -> String {
        last_error().unwrap_or_default()
    }

    #[test]
    fn handles_start_at_one_and_are_shared_and_never_reused() {
        let registry = TestRegistry::new();
        let listener = Arc::new("listener");
        let connection = Arc::new(7);
        assert_eq!(
            registry.register(&registry.listeners, &listener, "t"),
            Ok(1)
        );
        assert_eq!(
            registry.register(&registry.connections, &connection, "t"),
            Ok(2)
        );
        assert_eq!(
            registry.register(&registry.connections, &connection, "t"),
            Ok(3)
        );
        assert!(registry.release(&registry.connections, 2, "t").is_ok());
        assert_eq!(
            registry.register(&registry.connections, &connection, "t"),
            Ok(4)
        );
        // A handle of the other kind is unknown in this table.
        let failure = registry.connections.get(1, "read").err().unwrap();
        assert_eq!(failure.kind(), Error::InvalidHandle);
        assert_eq!(failure.to_string(), "read: invalid_handle");
        assert_eq!(*registry.listeners.get(1, "t").unwrap(), "listener");
    }

    #[test]
    fn lookups_share_the_object_without_holding_the_lock() {
        let registry = TestRegistry::new();
        let connection = Arc::new(7);
        let handle = registry
            .register(&registry.connections, &connection, "t")
            .unwrap() as u64;
        let first = registry.connections.get(handle, "t").unwrap();
        let second = registry.connections.get(handle, "t").unwrap();
        assert!(Arc::ptr_eq(&first, &second));
        assert_eq!(Arc::strong_count(&connection), 4);
        // Releasing returns the object while lookups that already hold it keep it alive.
        let released = registry
            .release(&registry.connections, handle, "t")
            .unwrap();
        assert!(Arc::ptr_eq(&released, &first));
        assert_eq!(
            registry.connections.get(handle, "t").err().unwrap().kind(),
            Error::InvalidHandle
        );
        assert_eq!(
            registry
                .release(&registry.connections, handle, "close")
                .err()
                .unwrap()
                .kind(),
            Error::InvalidHandle
        );
        assert_eq!(*first, 7);
    }

    #[test]
    fn live_handles_are_capped_across_both_kinds() {
        let registry = TestRegistry::new();
        let listener = Arc::new("listener");
        let connection = Arc::new(7);
        assert_eq!(
            registry.register(&registry.listeners, &listener, "t"),
            Ok(1)
        );
        for _ in 1..MAX_LIVE_HANDLES {
            registry
                .register(&registry.connections, &connection, "t")
                .unwrap();
        }
        let failure = registry
            .register(&registry.connections, &connection, "accept")
            .err()
            .unwrap();
        assert_eq!(failure.kind(), Error::Io);
        assert_eq!(failure.to_string(), "accept: handle_limit");
        assert_eq!(
            registry
                .register(&registry.listeners, &listener, "listen")
                .err()
                .unwrap()
                .kind(),
            Error::Io
        );
        assert!(registry.release(&registry.listeners, 1, "t").is_ok());
        // A released slot is free again, under a new handle.
        let next = registry
            .register(&registry.connections, &connection, "t")
            .unwrap();
        assert!(next > MAX_LIVE_HANDLES as i64);
        assert_eq!(Arc::strong_count(&connection), MAX_LIVE_HANDLES + 1);
    }

    #[test]
    fn exports_clear_the_diagnostic_and_map_adapter_failures() {
        assert_eq!(fail(Failure::new(Error::Io, "read", "recv")), -2);
        assert_eq!(export(PanicHook::Keep, "abi", || Ok(1)), 1);
        assert_eq!(last_error(), None);

        let rejected = keyguard_ffi::BRIDGE_INVALID_ARGUMENT;
        assert_eq!(export(PanicHook::Keep, "listen", || Err(rejected)), -1);
        assert_eq!(last(), "listen: invalid_argument");
        assert_eq!(export(PanicHook::Keep, "read", || Err(-1)), -1);
        assert_eq!(last(), "read: invalid_argument");

        assert_eq!(export(PanicHook::Keep, "read", || Err(BRIDGE_INTERNAL)), -8);
        assert_eq!(last(), "read: bridge");
        assert_eq!(export(PanicHook::Keep, "read", || Err(-8)), -8);
        assert_eq!(last(), "read: bridge");

        let panicked = export(
            PanicHook::Keep,
            "write",
            || -> std::result::Result<i64, i64> { panic!("fixture") },
        );
        assert_eq!(panicked, -8);
        assert_eq!(last(), "write: panic");

        // An operation's own result passes through with its own diagnostic.
        assert_eq!(export(PanicHook::Keep, "close", || Ok(close(0))), -5);
        assert_eq!(last(), "close: invalid_handle");
    }

    #[test]
    fn diagnostics_are_per_thread() {
        assert_eq!(close(0), -5);
        std::thread::spawn(|| {
            assert_eq!(last_error(), None);
            assert_eq!(accept(0), -5);
            assert_eq!(last(), "accept: invalid_handle");
        })
        .join()
        .unwrap();
        assert_eq!(last(), "close: invalid_handle");
        clear_error();
        assert_eq!(last_error(), None);
    }

    #[test]
    fn diagnostics_are_bounded_ascii() {
        assert_eq!(
            diagnostic(Failure::new(Error::Io, "listen", "random").with_os_code(-5)),
            "listen: random (os -5)"
        );
        let long: &'static str = "r".repeat(200).leak();
        assert_eq!(
            diagnostic(Failure::new(Error::Io, "listen", long)).len(),
            MAX_DIAGNOSTIC_BYTES
        );
        assert_eq!(
            diagnostic(Failure::new(Error::Io, "listén", "é")),
            "listn: "
        );
    }

    #[test]
    fn unknown_handles_and_bad_buffers_are_rejected() {
        assert_eq!(accept(0), -5);
        assert_eq!(close_listener(u64::MAX), -5);
        assert_eq!(peer_pid(0), -5);
        assert_eq!(await_input(0), -5);
        assert_eq!(close(0), -5);
        assert_eq!(endpoint(0, str::len), Err(-5));
        assert_eq!(last(), "endpoint: invalid_handle");
        assert_eq!(read(0, &mut [0; 4]), -5);
        assert_eq!(read(0, &mut []), -1);
        assert_eq!(last(), "read: empty_buffer");
        assert_eq!(write(0, &[]), -5);
        assert_eq!(write(0, &vec![0; MAX_WRITE_BYTES + 1]), -1);
        assert_eq!(last(), "write: too_long");
    }

    #[test]
    fn listen_validates_arguments_before_creating_anything() {
        assert_eq!(listen(OTHER_PARENT, "kg-", ""), -1);
        assert_eq!(last(), "listen: invalid_parent");
        assert_eq!(listen(PARENT, "kg/", ""), -1);
        assert_eq!(last(), "listen: invalid_prefix");
        assert_eq!(listen(PARENT, "kg-", ".."), -1);
        assert_eq!(last(), "listen: invalid_name");
    }

    #[cfg(unix)]
    const PARENT: &str = "/tmp";
    #[cfg(unix)]
    const OTHER_PARENT: &str = "";
    #[cfg(windows)]
    const PARENT: &str = "";
    #[cfg(windows)]
    const OTHER_PARENT: &str = "C:\\";
}
