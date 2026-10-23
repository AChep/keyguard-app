//! Overlapped named pipes (Windows x64 and ARM64).
//!
//! Pipes are created with `bInheritHandle = 0` and events with NULL attributes,
//! so no handle is inheritable. Both objects are shared through `Arc`; a handle
//! closes only when the last `Arc` drops, so it is never freed while a call uses
//! it. Overlapped calls follow the guard rule in [`operation`]; the owner-only
//! DACL and the same-user check are in [`security`].
//!
//! The pipe name always has a listening instance: the next instance is created
//! before an accepted one is handed out, a failed instance is closed only after
//! its replacement exists, and an instance whose client vanished is recycled in
//! place.

mod operation;
mod security;

use std::{
    iter,
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    ptr,
    sync::{
        Mutex, MutexGuard, TryLockError,
        atomic::{AtomicBool, Ordering},
    },
};

use windows_sys::Win32::{
    Foundation::{
        ERROR_ACCESS_DENIED, ERROR_BROKEN_PIPE, ERROR_HANDLE_EOF, ERROR_NO_DATA,
        ERROR_OPERATION_ABORTED, ERROR_PIPE_BUSY, ERROR_PIPE_CONNECTED, ERROR_PIPE_NOT_CONNECTED,
        GetLastError, INVALID_HANDLE_VALUE,
    },
    Storage::FileSystem::{
        FILE_FLAG_FIRST_PIPE_INSTANCE, FILE_FLAG_OVERLAPPED, PIPE_ACCESS_DUPLEX, ReadFile,
        WriteFile,
    },
    System::{
        Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, PIPE_READMODE_BYTE,
            PIPE_REJECT_REMOTE_CLIENTS, PIPE_TYPE_BYTE, PIPE_WAIT,
        },
        Threading::{CreateEventW, SetEvent},
    },
};
use zeroize::{Zeroize, Zeroizing};

use self::{
    operation::{Completion, Context, Operation},
    security::{SecurityDescriptor, Sid},
};
use crate::{Error, Failure, Readiness, Result, bridge::MAX_READ_BYTES, name::WindowsName};

/// `PIPE_UNLIMITED_INSTANCES`.
const MAX_INSTANCES: u32 = 255;
/// Each direction's pipe buffer.
const BUFFER_BYTES: u32 = 65536;

fn closed(operation: &'static str) -> Failure {
    Failure::new(Error::Closed, operation, "closed")
}

/// `ERROR_NO_DATA`, `ERROR_BROKEN_PIPE`, `ERROR_PIPE_NOT_CONNECTED`: the client
/// closed its end.
fn is_disconnected(code: u32) -> bool {
    matches!(
        code,
        ERROR_NO_DATA | ERROR_BROKEN_PIPE | ERROR_PIPE_NOT_CONNECTED
    )
}

/// An unnamed manual-reset event: not signalled, not inheritable.
fn event(operation: &'static str) -> Result<OwnedHandle> {
    // SAFETY: NULL attributes and name; the result is checked before use.
    let event = unsafe { CreateEventW(ptr::null(), 1, 0, ptr::null()) };
    if event.is_null() {
        return Err(Failure::last_os_error(Error::Io, operation, "create_event"));
    }
    // SAFETY: CreateEventW succeeded and transferred this handle.
    Ok(unsafe { OwnedHandle::from_raw_handle(event) })
}

/// Signals a manual-reset event. It stays signalled: nothing resets it.
fn signal(event: &OwnedHandle) {
    // SAFETY: The event is open while its owner lives.
    unsafe { SetEvent(event.as_raw_handle()) };
}

/// Takes a lane, or fails with `Busy` while another call holds it.
fn lane<'a, T>(lane: &'a Mutex<T>, operation: &'static str) -> Result<MutexGuard<'a, T>> {
    match lane.try_lock() {
        Ok(guard) => Ok(guard),
        // Every call drains before it unwinds, so the state is consistent.
        Err(TryLockError::Poisoned(poisoned)) => Ok(poisoned.into_inner()),
        Err(TryLockError::WouldBlock) => Err(Failure::new(Error::Busy, operation, "busy")),
    }
}

/// Creates one instance of the pipe `name`, NUL-terminated UTF-16. The error is the
/// OS code.
fn create_instance(
    name: &[u16],
    security: &SecurityDescriptor,
    first: bool,
) -> std::result::Result<OwnedHandle, u32> {
    let first = if first {
        FILE_FLAG_FIRST_PIPE_INSTANCE
    } else {
        0
    };
    let attributes = security.attributes();
    // SAFETY: `name` is NUL-terminated, and the attributes and their descriptor stay
    // alive for the call.
    let pipe = unsafe {
        CreateNamedPipeW(
            name.as_ptr(),
            PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED | first,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
            MAX_INSTANCES,
            BUFFER_BYTES,
            BUFFER_BYTES,
            0,
            &attributes,
        )
    };
    if pipe == INVALID_HANDLE_VALUE {
        // SAFETY: No preconditions; read right after the failed call.
        return Err(unsafe { GetLastError() });
    }
    // SAFETY: CreateNamedPipeW succeeded and transferred this handle.
    Ok(unsafe { OwnedHandle::from_raw_handle(pipe) })
}

pub(crate) struct Listener {
    endpoint: String,
    /// The pipe name, NUL-terminated UTF-16.
    name: Vec<u16>,
    /// Outlives every `CreateNamedPipeW` of this listener.
    security: SecurityDescriptor,
    user: Sid,
    /// Manual-reset; set once by [`Listener::close`].
    stop: OwnedHandle,
    stopped: AtomicBool,
    /// Set once no instance can be created; accept then fails with `Internal`.
    failed: AtomicBool,
    /// The accept lane.
    pending: Mutex<Pending>,
}

/// The listening instance and the accept lane's operation context.
struct Pending {
    /// `None` only once the listener failed.
    pipe: Option<OwnedHandle>,
    context: Context,
}

/// How one `ConnectNamedPipe` on the pending instance ended.
enum Connect {
    Connected,
    /// A client connected and closed before accept returned it.
    Vanished,
    Stopped,
    Failed(u32),
}

impl Listener {
    /// Creates instance 0 of [`WindowsName::pipe_name`], which becomes the pending
    /// (listening) instance. `name` is validated, length included.
    ///
    /// - The current user's SID is built once (`OpenProcessToken`, `TokenUser` with
    ///   a bounded buffer). The security descriptor `O:<sid>D:P(A;;GA;;;<sid>)`
    ///   comes from `ConvertStringSecurityDescriptorToSecurityDescriptorW` and is
    ///   owned by the listener, so it outlives every `CreateNamedPipeW`.
    /// - `CreateNamedPipeW(name, PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED |
    ///   FILE_FLAG_FIRST_PIPE_INSTANCE, PIPE_TYPE_BYTE | PIPE_READMODE_BYTE |
    ///   PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS, 255, 65536, 65536, 0, &sa)`.
    ///   `FILE_FLAG_FIRST_PIPE_INSTANCE` is for instance 0 only.
    ///   `ERROR_ACCESS_DENIED`, or `ERROR_PIPE_BUSY` for a pipe whose creator
    ///   allowed fewer instances, is `Permission "name_in_use"`.
    /// - A manual-reset, unnamed, non-inheritable stop event, and a `failed` flag.
    ///
    /// Connectable on return.
    pub(crate) fn bind(name: WindowsName<'_>) -> Result<Self> {
        let endpoint = name.pipe_name()?;
        let wide: Vec<u16> = endpoint.encode_utf16().chain(iter::once(0)).collect();
        let user = Sid::current_user()?;
        let security = SecurityDescriptor::owner_only(&user)?;
        let stop = event("listen")?;
        let context = Context::new("listen")?;
        let pipe = create_instance(&wide, &security, true).map_err(|code| {
            // With the first-instance flag, both mean that the name exists: busy when
            // its creator allowed no further instance.
            let failure = if code == ERROR_ACCESS_DENIED || code == ERROR_PIPE_BUSY {
                Failure::new(Error::Permission, "listen", "name_in_use")
            } else {
                Failure::new(Error::Io, "listen", "create_named_pipe")
            };
            failure.with_os_code(code as i32)
        })?;
        Ok(Self {
            endpoint,
            name: wide,
            security,
            user,
            stop,
            stopped: AtomicBool::new(false),
            failed: AtomicBool::new(false),
            pending: Mutex::new(Pending {
                pipe: Some(pipe),
                context,
            }),
        })
    }

    /// The pipe name, `\\.\pipe\<name>`.
    pub(crate) fn endpoint(&self) -> &str {
        &self.endpoint
    }

    /// Accepts one connection on the pending instance.
    ///
    /// A busy guard: a concurrent accept is `Busy`. `Closed` once stopped,
    /// `Internal` once `failed`. `ConnectNamedPipe` on the pending instance:
    ///
    /// - nonzero, or `ERROR_PIPE_CONNECTED`: connected.
    /// - `ERROR_IO_PENDING`: arm the guard and wait on `{stop event, op event}`.
    ///   Stop: drain, keep the instance pending (the name stays alive), `Closed`.
    ///   Event: `GetOverlappedResult(FALSE)`; success is connected, `ERROR_NO_DATA`,
    ///   `ERROR_BROKEN_PIPE` or `ERROR_PIPE_NOT_CONNECTED` is vanished.
    /// - `ERROR_NO_DATA`, `ERROR_BROKEN_PIPE`, `ERROR_PIPE_NOT_CONNECTED`: vanished.
    /// - Anything else: create the replacement first. On success close the failed
    ///   instance, make the replacement pending and return `Io`; on failure keep the
    ///   failed instance pending, set `failed` and return `Internal`.
    ///
    /// Vanished: `DisconnectNamedPipe` on the same instance (nothing was written to
    /// it), check `stopped`, and `ConnectNamedPipe` again on the same handle, so the
    /// name never has zero instances.
    ///
    /// Connected: create the next instance, without the first-instance flag, before
    /// anything else. If that fails, set `failed`, close this unverified instance and
    /// return `Internal`. Otherwise it becomes pending; then take the peer snapshot
    /// ([`security`]) and return the connection. A snapshot failure is the
    /// connection's `peer_pid`, never an accept failure. Only a lack of resources for
    /// the connection's events closes it and returns `Io`, as `EMFILE` does on Unix.
    pub(crate) fn accept(&self) -> Result<Connection> {
        let mut pending = lane(&self.pending, "accept")?;
        let pending = &mut *pending;
        loop {
            if self.stopped.load(Ordering::SeqCst) {
                return Err(closed("accept"));
            }
            let (false, Some(pipe)) = (self.failed.load(Ordering::SeqCst), pending.pipe.as_ref())
            else {
                return Err(Failure::new(Error::Internal, "accept", "listener_failed"));
            };
            match connect(pipe, &mut pending.context, &self.stop)? {
                Connect::Connected => break,
                Connect::Vanished => {
                    // SAFETY: The instance is open, and nothing was written to it.
                    if unsafe { DisconnectNamedPipe(pipe.as_raw_handle()) } == 0 {
                        // SAFETY: No preconditions; read right after the failed call.
                        let code = unsafe { GetLastError() };
                        return Err(self.replace_failed(pending, code));
                    }
                }
                Connect::Stopped => return Err(closed("accept")),
                Connect::Failed(code) => return Err(self.replace_failed(pending, code)),
            }
        }
        let next = match create_instance(&self.name, &self.security, false) {
            Ok(next) => next,
            Err(code) => {
                self.failed.store(true, Ordering::SeqCst);
                // Closes the connected, unverified instance: nothing was read or written.
                pending.pipe = None;
                return Err(Failure::new(Error::Internal, "accept", "create_named_pipe")
                    .with_os_code(code as i32));
            }
        };
        let Some(pipe) = pending.pipe.replace(next) else {
            return Err(Failure::new(Error::Internal, "accept", "listener_failed"));
        };
        Connection::new(pipe, &self.user)
    }

    /// Replaces the pending instance after `ConnectNamedPipe` failed with `code`.
    /// The replacement is created first, so the name never lapses.
    fn replace_failed(&self, pending: &mut Pending, code: u32) -> Failure {
        match create_instance(&self.name, &self.security, false) {
            Ok(replacement) => {
                // Closes the failed instance.
                pending.pipe = Some(replacement);
                Failure::new(Error::Io, "accept", "connect_named_pipe").with_os_code(code as i32)
            }
            Err(create) => {
                self.failed.store(true, Ordering::SeqCst);
                Failure::new(Error::Internal, "accept", "create_named_pipe")
                    .with_os_code(create as i32)
            }
        }
    }

    /// Idempotent and non-blocking: `stopped = true`, then `SetEvent(stop event)`.
    /// Instances close at the last drop.
    pub(crate) fn close(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        signal(&self.stop);
    }
}

/// One `ConnectNamedPipe` on `pipe`, woken by `stop`.
fn connect(pipe: &OwnedHandle, context: &mut Context, stop: &OwnedHandle) -> Result<Connect> {
    let mut operation = Operation::new(pipe, context, "accept")?;
    let overlapped = operation.overlapped();
    // SAFETY: The pipe is overlapped, and the guard keeps the OVERLAPPED in place
    // until the call drains.
    let submitted = unsafe { ConnectNamedPipe(pipe.as_raw_handle(), overlapped) };
    Ok(match operation.finish(submitted, stop) {
        Completion::Done(_) | Completion::Failed(ERROR_PIPE_CONNECTED) => Connect::Connected,
        Completion::Failed(code) if is_disconnected(code) => Connect::Vanished,
        Completion::Failed(code) => Connect::Failed(code),
        // A client that connected during the drain stays on the pending instance.
        Completion::Woken(_) => Connect::Stopped,
    })
}

/// A connected instance with a manual-reset close event, an inbound and an
/// outbound operation context, a zeroized 1-byte pushback, and a `closed` flag
/// checked before every submission.
pub(crate) struct Connection {
    pipe: OwnedHandle,
    /// The accept snapshot.
    peer: Result<u32>,
    closed: AtomicBool,
    /// Manual-reset; set once by [`Connection::close`].
    close_event: OwnedHandle,
    /// The read and observe lane.
    inbound: Mutex<Inbound>,
    /// The write lane.
    outbound: Mutex<Context>,
}

struct Inbound {
    context: Context,
    /// The observer's buffer. While `pushback` is set it holds the first unread byte.
    byte: Zeroizing<[u8; 1]>,
    pushback: bool,
}

impl Connection {
    fn new(pipe: OwnedHandle, user: &Sid) -> Result<Self> {
        let peer = security::peer(&pipe, user);
        Ok(Self {
            close_event: event("accept")?,
            inbound: Mutex::new(Inbound {
                context: Context::new("accept")?,
                byte: Zeroizing::new([0]),
                pushback: false,
            }),
            outbound: Mutex::new(Context::new("accept")?),
            pipe,
            peer,
            closed: AtomicBool::new(false),
        })
    }

    /// The pid from the accept snapshot (`GetNamedPipeClientProcessId`, then the
    /// client's token SID equal to the cached SID), or `Permission` with its reason.
    pub(crate) fn peer_pid(&self) -> Result<u32> {
        self.peer
    }

    fn check_open(&self, operation: &'static str) -> Result<()> {
        if self.closed.load(Ordering::SeqCst) {
            return Err(closed(operation));
        }
        Ok(())
    }

    /// `ERROR_OPERATION_ABORTED` after a local close is `Closed`; any other failure
    /// is `Io` with its code.
    fn failure(&self, code: u32, operation: &'static str, reason: &'static str) -> Failure {
        if code == ERROR_OPERATION_ABORTED && self.closed.load(Ordering::SeqCst) {
            return closed(operation);
        }
        Failure::new(Error::Io, operation, reason).with_os_code(code as i32)
    }

    /// Inbound lane; `out` is not empty. The pushback byte first; otherwise
    /// `ReadFile` into the guard's zeroized scratch, then copy out.
    ///
    /// - Nonzero: the count from `GetOverlappedResult(FALSE)`; a 0-byte success (a
    ///   client's zero-length write) is re-issued.
    /// - `ERROR_IO_PENDING`: armed; wait on `{close event, op event}`. Close: drain,
    ///   then `Closed`.
    /// - `ERROR_BROKEN_PIPE`, `ERROR_PIPE_NOT_CONNECTED`, `ERROR_NO_DATA`,
    ///   `ERROR_HANDLE_EOF`: end of stream (0).
    /// - `ERROR_OPERATION_ABORTED`: `Closed` if closed, else `Io`.
    pub(crate) fn read(&self, out: &mut [u8]) -> Result<usize> {
        let mut inbound = lane(&self.inbound, "read")?;
        let inbound = &mut *inbound;
        loop {
            self.check_open("read")?;
            if inbound.pushback {
                out[0] = inbound.byte[0];
                inbound.byte.zeroize();
                inbound.pushback = false;
                return Ok(1);
            }
            let length = out.len().min(MAX_READ_BYTES);
            let mut operation =
                Operation::with_scratch(&self.pipe, &mut inbound.context, length, "read")?;
            let buffer = operation.scratch_ptr();
            let overlapped = operation.overlapped();
            // SAFETY: The guard owns the scratch and keeps it and the OVERLAPPED in
            // place until the call drains; `length` fits both the scratch and u32.
            let submitted = unsafe {
                ReadFile(
                    self.pipe.as_raw_handle(),
                    buffer,
                    length as u32,
                    ptr::null_mut(),
                    overlapped,
                )
            };
            match operation.finish(submitted, &self.close_event) {
                Completion::Done(0) => {}
                Completion::Done(count) => {
                    let count = (count as usize).min(length);
                    out[..count].copy_from_slice(&operation.scratch()[..count]);
                    return Ok(count);
                }
                Completion::Failed(code) if is_disconnected(code) || code == ERROR_HANDLE_EOF => {
                    return Ok(0);
                }
                Completion::Failed(code) => return Err(self.failure(code, "read", "read_file")),
                // The connection is closed: a byte read during the drain is dropped
                // with the scratch, zeroized.
                Completion::Woken(_) => return Err(closed("read")),
            }
        }
    }

    /// Outbound lane; `data` is not empty. Loops `WriteFile` over the remainder;
    /// pending waits on `{close event, op event}`. `ERROR_NO_DATA`,
    /// `ERROR_BROKEN_PIPE` or `ERROR_PIPE_NOT_CONNECTED` is `Closed` if closed, else
    /// `PeerClosed`. No `FlushFileBuffers`.
    pub(crate) fn write_all(&self, data: &[u8]) -> Result<()> {
        let mut outbound = lane(&self.outbound, "write")?;
        let mut remaining = data;
        while !remaining.is_empty() {
            self.check_open("write")?;
            let length = u32::try_from(remaining.len()).unwrap_or(u32::MAX);
            let mut operation = Operation::new(&self.pipe, &mut outbound, "write")?;
            let overlapped = operation.overlapped();
            // SAFETY: `remaining` outlives the guard, which keeps the OVERLAPPED in
            // place and drains the call before it returns; `length` fits `remaining`.
            let submitted = unsafe {
                WriteFile(
                    self.pipe.as_raw_handle(),
                    remaining.as_ptr(),
                    length,
                    ptr::null_mut(),
                    overlapped,
                )
            };
            match operation.finish(submitted, &self.close_event) {
                Completion::Done(0) => {
                    return Err(Failure::new(Error::Io, "write", "write_zero"));
                }
                Completion::Done(count) => {
                    remaining = &remaining[(count as usize).min(remaining.len())..];
                }
                Completion::Failed(code) if is_disconnected(code) => {
                    if self.closed.load(Ordering::SeqCst) {
                        return Err(closed("write"));
                    }
                    return Err(Failure::new(Error::PeerClosed, "write", "peer_closed")
                        .with_os_code(code as i32));
                }
                Completion::Failed(code) => return Err(self.failure(code, "write", "write_file")),
                Completion::Woken(_) => return Err(closed("write")),
            }
        }
        Ok(())
    }

    /// Inbound lane. A pushback byte is `Input`. Otherwise a 1-byte overlapped read:
    /// a byte goes into the pushback and is `Input`; the broken-pipe family is
    /// `Eof`; a byte that arrives during a close drain also goes into the pushback.
    pub(crate) fn await_input(&self) -> Result<Readiness> {
        self.observe(&self.close_event)
    }

    /// [`Connection::await_input`], woken by `wake`: the close event, or a test's.
    fn observe(&self, wake: &OwnedHandle) -> Result<Readiness> {
        let mut inbound = lane(&self.inbound, "await_input")?;
        let Inbound {
            context,
            byte,
            pushback,
        } = &mut *inbound;
        loop {
            self.check_open("await_input")?;
            if *pushback {
                return Ok(Readiness::Input);
            }
            let mut operation = Operation::new(&self.pipe, context, "await_input")?;
            let overlapped = operation.overlapped();
            // SAFETY: The observer byte lives in this lane, which the guard borrows
            // through `context`'s lock; the guard drains the call before the lock is
            // released, and keeps the OVERLAPPED in place until then.
            let submitted = unsafe {
                ReadFile(
                    self.pipe.as_raw_handle(),
                    byte.as_mut_ptr(),
                    1,
                    ptr::null_mut(),
                    overlapped,
                )
            };
            match operation.finish(submitted, wake) {
                Completion::Done(0) => {}
                Completion::Done(_) => {
                    *pushback = true;
                    return Ok(Readiness::Input);
                }
                Completion::Failed(code) if is_disconnected(code) || code == ERROR_HANDLE_EOF => {
                    return Ok(Readiness::Eof);
                }
                Completion::Failed(code) => {
                    return Err(self.failure(code, "await_input", "read_file"));
                }
                Completion::Woken(drained) => {
                    if matches!(drained, Ok(count) if count > 0) {
                        *pushback = true;
                    }
                    return Err(closed("await_input"));
                }
            }
        }
    }

    /// Idempotent and non-blocking: `closed = true`, then `SetEvent(close event)`.
    /// No `DisconnectNamedPipe` and no `FlushFileBuffers`, so a reply written before
    /// close stays readable. `CloseHandle` runs at the last drop.
    pub(crate) fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        signal(&self.close_event);
    }
}

#[cfg(test)]
mod tests {
    use std::{
        fs::{File, OpenOptions},
        io::{Read, Write},
        os::windows::fs::OpenOptionsExt,
        sync::Arc,
        thread,
        time::{Duration, Instant},
    };

    use windows_sys::Win32::{
        Foundation::{
            GENERIC_READ, GENERIC_WRITE, GetHandleInformation, HANDLE_FLAG_INHERIT, WAIT_OBJECT_0,
        },
        Storage::FileSystem::WRITE_DAC,
        System::Threading::{ResetEvent, WaitForSingleObject},
    };

    use super::*;

    fn bind() -> Listener {
        Listener::bind(WindowsName::new("kg-agent-ipc-unit-", None).unwrap()).unwrap()
    }

    fn connect(endpoint: &str) -> File {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            match OpenOptions::new().read(true).write(true).open(endpoint) {
                Ok(client) => return client,
                Err(error)
                    if error.raw_os_error() == Some(ERROR_PIPE_BUSY as i32)
                        && Instant::now() < deadline =>
                {
                    thread::sleep(Duration::from_millis(5));
                }
                Err(error) => panic!("connect failed: {error}"),
            }
        }
    }

    fn pending_handle(listener: &Listener) -> isize {
        listener
            .pending
            .lock()
            .unwrap()
            .pipe
            .as_ref()
            .unwrap()
            .as_raw_handle() as isize
    }

    fn inheritable(handle: &impl AsRawHandle) -> bool {
        let mut flags = 0;
        // SAFETY: The handle is open and `flags` is writable.
        let read = unsafe { GetHandleInformation(handle.as_raw_handle(), &mut flags) };
        assert_ne!(read, 0);
        flags & HANDLE_FLAG_INHERIT != 0
    }

    #[test]
    fn handles_are_not_inheritable() {
        let listener = bind();
        let client = connect(listener.endpoint());
        let connection = listener.accept().unwrap();
        {
            let pending = listener.pending.lock().unwrap();
            assert!(!inheritable(pending.pipe.as_ref().unwrap()));
            assert!(!inheritable(pending.context.event()));
        }
        assert!(!inheritable(&listener.stop));
        assert!(!inheritable(&connection.pipe));
        assert!(!inheritable(&connection.close_event));
        assert!(!inheritable(
            connection.inbound.lock().unwrap().context.event()
        ));
        assert!(!inheritable(connection.outbound.lock().unwrap().event()));
        drop(client);
    }

    #[test]
    fn a_vanished_client_recycles_the_same_instance() {
        let listener = Arc::new(bind());
        let first = pending_handle(&listener);
        // Connects and closes before accept runs: `ConnectNamedPipe` then fails with
        // `ERROR_NO_DATA` on this instance.
        drop(connect(listener.endpoint()));
        let accepting = Arc::clone(&listener);
        let accepted = thread::spawn(move || accepting.accept());
        let mut client = connect(listener.endpoint());
        let connection = accepted.join().unwrap().unwrap();
        assert_eq!(connection.pipe.as_raw_handle() as isize, first);
        assert_ne!(pending_handle(&listener), first);
        client.write_all(b"x").unwrap();
        let mut byte = [0];
        assert_eq!(connection.read(&mut byte), Ok(1));
        assert_eq!(byte, *b"x");
    }

    #[test]
    fn a_failed_instance_is_replaced_before_it_closes() {
        let listener = bind();
        let first = pending_handle(&listener);
        let failure = {
            let mut pending = listener.pending.lock().unwrap();
            listener.replace_failed(&mut pending, 1)
        };
        assert_eq!(failure.kind(), Error::Io);
        assert_eq!(failure.to_string(), "accept: connect_named_pipe (os 1)");
        assert_ne!(pending_handle(&listener), first);
        let mut client = connect(listener.endpoint());
        let connection = listener.accept().unwrap();
        client.write_all(b"y").unwrap();
        let mut byte = [0];
        assert_eq!(connection.read(&mut byte), Ok(1));
        assert_eq!(byte, *b"y");
    }

    /// A client of the pending instance that takes `FILE_CREATE_PIPE_INSTANCE` away
    /// from the pipe's DACL, so the listener can create no further instance.
    fn deny_new_instances(listener: &Listener) -> File {
        let client = OpenOptions::new()
            .access_mode(GENERIC_READ | GENERIC_WRITE | WRITE_DAC)
            .open(listener.endpoint())
            .unwrap();
        security::deny_new_instances(&client, &listener.user);
        client
    }

    #[test]
    fn a_replacement_that_cannot_be_created_fails_the_listener() {
        let listener = bind();
        let first = pending_handle(&listener);
        let _client = deny_new_instances(&listener);
        let failure = {
            let mut pending = listener.pending.lock().unwrap();
            listener.replace_failed(&mut pending, 1)
        };
        assert_eq!(failure.kind(), Error::Internal);
        assert_eq!(
            failure.to_string(),
            format!("accept: create_named_pipe (os {ERROR_ACCESS_DENIED})")
        );
        assert!(listener.failed.load(Ordering::SeqCst));
        // The failed instance stays pending, so the name stays alive.
        assert_eq!(pending_handle(&listener), first);
        let failure = listener.accept().err().unwrap();
        assert_eq!(failure.to_string(), "accept: listener_failed");
    }

    #[test]
    fn a_next_instance_that_cannot_be_created_fails_the_listener() {
        let listener = bind();
        let mut client = deny_new_instances(&listener);
        let failure = listener.accept().err().unwrap();
        assert_eq!(failure.kind(), Error::Internal);
        assert_eq!(
            failure.to_string(),
            format!("accept: create_named_pipe (os {ERROR_ACCESS_DENIED})")
        );
        // The unverified instance is closed before any I/O.
        assert!(listener.pending.lock().unwrap().pipe.is_none());
        let mut byte = [0];
        assert_eq!(client.read(&mut byte).unwrap(), 0);
        let failure = listener.accept().err().unwrap();
        assert_eq!(failure.to_string(), "accept: listener_failed");
        listener.close();
        assert_eq!(listener.accept().err().unwrap().kind(), Error::Closed);
    }

    /// The well-known SID of LocalSystem, `S-1-5-18`.
    fn local_system() -> Sid {
        let words: [u32; 3] = [
            u32::from_le_bytes([1, 1, 0, 0]),
            u32::from_le_bytes([0, 0, 0, 5]),
            18,
        ];
        // SAFETY: `words` holds a valid 12-byte SID and stays alive for the copy.
        unsafe { Sid::copy(words.as_ptr().cast_mut().cast()) }.unwrap()
    }

    #[test]
    fn the_peer_snapshot_requires_the_same_user() {
        let listener = bind();
        let _client = connect(listener.endpoint());
        let connection = listener.accept().unwrap();
        assert_eq!(connection.peer_pid(), Ok(std::process::id()));
        assert_eq!(
            security::peer(&connection.pipe, &Sid::current_user().unwrap()),
            Ok(std::process::id())
        );
        let failure = security::peer(&connection.pipe, &local_system()).unwrap_err();
        assert_eq!(failure.kind(), Error::Permission);
        assert_eq!(failure.to_string(), "peer_pid: user_mismatch");
    }

    /// A byte that completes the observer's read while a wake cancels it is kept in
    /// the pushback: reads see every byte once, in order.
    #[test]
    fn the_pushback_survives_a_cancel_and_drain_race() {
        const ROUNDS: usize = 10_000;
        let listener = bind();
        let mut client = connect(listener.endpoint());
        let connection = listener.accept().unwrap();
        // Signalled before every observe: a read that pends is cancelled at once,
        // racing the client's next byte.
        let wake = event("test").unwrap();
        signal(&wake);
        let writer = thread::spawn(move || {
            for round in 0..ROUNDS {
                client.write_all(&[round as u8]).unwrap();
                // 0 to 45 µs apart, so that many observers find the pipe empty and pend.
                let until = Instant::now() + Duration::from_micros((round % 10) as u64 * 5);
                while Instant::now() < until {
                    std::hint::spin_loop();
                }
            }
            client
        });
        let (mut woken, mut kept) = (0, 0);
        for round in 0..ROUNDS {
            match connection.observe(&wake) {
                Ok(Readiness::Input) => {}
                Err(failure) if failure.kind() == Error::Closed => {
                    woken += 1;
                    kept += usize::from(connection.inbound.lock().unwrap().pushback);
                }
                other => panic!("observe: {other:?}"),
            }
            let mut byte = [0];
            assert_eq!(connection.read(&mut byte), Ok(1));
            assert_eq!(byte[0], round as u8, "round {round}");
        }
        drop(writer.join().unwrap());
        // Not asserted: how often a byte lands during a drain depends on timing.
        eprintln!("observers cancelled: {woken}, bytes kept from a drain: {kept}, of {ROUNDS}");
    }

    #[test]
    fn close_is_sticky() {
        let listener = bind();
        let _client = connect(listener.endpoint());
        let connection = listener.accept().unwrap();
        connection.close();
        connection.close();
        for _ in 0..2 {
            assert_eq!(connection.await_input().unwrap_err().kind(), Error::Closed);
            // SAFETY: The event is open; a zero timeout only samples it.
            let state = unsafe { WaitForSingleObject(connection.close_event.as_raw_handle(), 0) };
            assert_eq!(state, WAIT_OBJECT_0);
        }
        // Resetting it by hand still leaves the closed flag, checked before every call.
        // SAFETY: The event is open.
        unsafe { ResetEvent(connection.close_event.as_raw_handle()) };
        let mut byte = [0];
        assert_eq!(
            connection.read(&mut byte).unwrap_err().kind(),
            Error::Closed
        );
    }
}
